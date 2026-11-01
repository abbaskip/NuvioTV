package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.simkl.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class PersonalRatingSession(val profileId: Int, val traktActor: String?,
    val simkl: SimklAuthScope?, val mdblist: MdbListAuthScope?, val providers: Set<PersonalRatingProvider>)
internal data class PersonalRatingResult(val provider: PersonalRatingProvider, val rating: Int?, val confirmed: Boolean)

/* Rating API behavior informed by Nuvio Mobile Enhanced (GPL-3.0):
 * https://github.com/luqmanfadlli/NuvioMobile-Enhanced
 * Implemented with NuvioTV's authenticated clients and account scopes.
 */
/** Reuses each integration's authentication, rate limits and account scopes; no ratings database. */
@Singleton
internal class PersonalRatingsService @Inject constructor(
    private val profiles: ProfileManager,
    private val trakt: TraktApi,
    private val traktAuth: TraktAuthService,
    private val traktStore: TraktAuthDataStore,
    private val simkl: SimklApiClient,
    private val simklStore: SimklAuthStorage,
    private val mdblist: MdbListApiClient,
    private val mdbStore: MdbListAuthStore
) {
    val connected = combine(traktStore.state, simklStore.state, mdbStore.state) { t, s, m ->
        buildSet {
            if (t.isAuthenticated) add(PersonalRatingProvider.TRAKT)
            if (s.isAuthenticated) add(PersonalRatingProvider.SIMKL)
            if (m.isAuthenticated) add(PersonalRatingProvider.MDBLIST)
        }
    }

    suspend fun session(target: PersonalRatingTarget): PersonalRatingSession {
        val t = traktAuth.getCurrentAuthState()
        val providers = buildSet {
            if (t.isAuthenticated) add(PersonalRatingProvider.TRAKT)
            if (simklStore.state.value.isAuthenticated) add(PersonalRatingProvider.SIMKL)
            if (mdbStore.state.value.isAuthenticated) add(PersonalRatingProvider.MDBLIST)
        }.filterTo(mutableSetOf(), target::supports)
        return PersonalRatingSession(profiles.activeProfileId.value,
            t.takeIf { it.isAuthenticated }?.let { it.userSlug ?: it.username ?: it.accessToken },
            simklStore.currentScope(), mdbStore.scope(), providers)
    }

    suspend fun read(target: PersonalRatingTarget, session: PersonalRatingSession) = batch(session) { provider ->
        PersonalRatingResult(provider, findRating(provider, target, session), true)
    }

    suspend fun write(target: PersonalRatingTarget, session: PersonalRatingSession, rating: Int?) = batch(session) { provider ->
        require(rating == null || rating in 1..10)
        checkAccount(provider, session)
        val path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        val payload = ratingBody(target, provider, rating)
        val response = when (provider) {
            PersonalRatingProvider.TRAKT -> {
                val result = traktAuth.executeAuthorizedWriteRequest { authorization ->
                    checkAccount(provider, session)
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    if (rating == null) trakt.removePersonalRating(authorization, body) else trakt.setPersonalRating(authorization, body)
                }
                check(result?.isSuccessful == true) { "Rating request failed" }
                JSONObject(result.body()?.string().orEmpty())
            }
            PersonalRatingProvider.SIMKL -> JSONObject(simkl.execute(SimklApiRequest(
                SimklHttpMethod.POST, path, body = payload.toString(), retryPolicy = SimklRetryPolicy.NEVER), session.simkl).body)
            PersonalRatingProvider.MDBLIST -> JSONObject(mdblist.post(path, payload.toString(), requireNotNull(session.mdblist)).body)
        }
        checkAccount(provider, session)
        check(!hasRatingErrors(response)) { "Tracker did not accept this title" }
        val count = if (rating == null) ratingCounters(response.optJSONObject("deleted"))
            else ratingCounters(response.optJSONObject("added")) + ratingCounters(response.optJSONObject("updated"))
        // A 2xx response with no acknowledgement is insufficient: verify the destination state.
        check(count > 0 || findRating(provider, target, session) == rating) { "Tracker did not confirm the rating" }
        PersonalRatingResult(provider, rating, true)
    }

    private suspend fun batch(session: PersonalRatingSession, operation: suspend (PersonalRatingProvider) -> PersonalRatingResult) = withContext(Dispatchers.IO) {
        coroutineScope { session.providers.map { provider -> async {
            try { operation(provider) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PersonalRatingResult(provider, null, false) }
        } }.awaitAll() }
    }

    private suspend fun checkAccount(provider: PersonalRatingProvider, session: PersonalRatingSession) {
        if (profiles.activeProfileId.value != session.profileId) throw CancellationException("Profile changed")
        when (provider) {
            PersonalRatingProvider.TRAKT -> {
                val state = traktAuth.getCurrentAuthState()
                if (!state.isAuthenticated || (state.userSlug ?: state.username ?: state.accessToken) != session.traktActor)
                    throw CancellationException("Trakt account changed")
            }
            PersonalRatingProvider.SIMKL -> if (session.simkl?.profileId != session.profileId || !simklStore.isCurrent(requireNotNull(session.simkl))) throw CancellationException("Simkl account changed")
            PersonalRatingProvider.MDBLIST -> {
                if (session.mdblist?.profileId != session.profileId) throw CancellationException("MDBList profile changed")
                mdbStore.checkScope(requireNotNull(session.mdblist))
            }
        }
    }

    private suspend fun findRating(provider: PersonalRatingProvider, target: PersonalRatingTarget, session: PersonalRatingSession): Int? {
        checkAccount(provider, session)
        var page = 1
        var offset = 0
        do {
            var more = false
            val rows = when (provider) {
                PersonalRatingProvider.TRAKT -> {
                    val response = traktAuth.executeAuthorizedRequest { authorization ->
                        checkAccount(provider, session)
                        trakt.getPersonalRatings(authorization, target.group, page)
                    }
                    check(response?.isSuccessful == true) { "Rating lookup failed" }
                    more = page < (response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: page)
                    JSONArray(response.body()?.string().orEmpty())
                }
                PersonalRatingProvider.SIMKL -> {
                    val types = if (target.movie) listOf("movies") else if (target.anime) listOf("anime") else listOf("shows", "anime")
                    val out = JSONArray()
                    for (type in types) {
                        val response = simkl.execute(SimklApiRequest(SimklHttpMethod.GET,
                            "/sync/ratings/$type/1,2,3,4,5,6,7,8,9,10"), session.simkl)
                        val body = org.json.JSONTokener(response.body).nextValue()
                        val list = if (body is JSONArray) body else {
                            check(!hasRatingErrors(body as JSONObject)) { "Rating lookup failed" }
                            requireNotNull(body.optJSONArray(type)) { "Invalid ratings response" }
                        }
                        for (i in 0 until list.length()) out.put(list.getJSONObject(i))
                    }
                    out
                }
                PersonalRatingProvider.MDBLIST -> {
                    val response = mdblist.get("/sync/ratings", mapOf("offset" to "$offset", "limit" to "1000"), requireNotNull(session.mdblist))
                    val body = JSONObject(response.body)
                    check(!hasRatingErrors(body) && listOf("movies", "shows", "seasons", "episodes").any(body::has)) { "Invalid ratings response" }
                    more = body.optJSONObject("pagination")?.optBoolean("has_more") == true
                    val result = body.optJSONArray(target.group) ?: JSONArray()
                    check(!more || result.length() > 0 || (body.optJSONArray("movies")?.length() ?: 0) +
                        (body.optJSONArray("shows")?.length() ?: 0) + (body.optJSONArray("episodes")?.length() ?: 0) +
                        (body.optJSONArray("seasons")?.length() ?: 0) > 0) { "Incomplete ratings page" }
                    result
                }
            }
            checkAccount(provider, session)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                check(!hasRatingErrors(row)) { "Invalid rating record" }
                val media = row.optJSONObject(if (target.movie) "movie" else "show") ?: row.optJSONObject("anime") ?: continue
                if (ratingIdsMatch(target.ids, media.optJSONObject("ids"))) {
                    return (if (row.has("user_rating")) row.optInt("user_rating") else row.optInt("rating")).takeIf { it in 1..10 }
                }
            }
            page++
            offset += 1000
        } while (more)
        return null
    }
}

internal fun ratingBody(target: PersonalRatingTarget, provider: PersonalRatingProvider, rating: Int?): JSONObject {
    require(target.supports(provider))
    require(rating == null || rating in 1..10)
    val ids = JSONObject().apply {
        target.ids.imdb?.let { put("imdb", it) }
        target.ids.tmdb?.let { put("tmdb", it) }
        if (provider == PersonalRatingProvider.TRAKT) target.ids.trakt?.let { put("trakt", it) }
        if (provider == PersonalRatingProvider.SIMKL) {
            target.ids.simkl?.let { put("simkl", it) }
            target.ids.mal?.let { put("mal", it) }
        }
    }
    val item = JSONObject().put("ids", ids)
    rating?.let { item.put("rating", it) }
    // Simkl accepts anime on add, but its removal schema uses shows for anime too.
    val group = if (provider == PersonalRatingProvider.SIMKL && rating == null && !target.movie) "shows" else target.groupFor(provider)
    return JSONObject().put(group, JSONArray().put(item))
}

internal fun ratingIdsMatch(target: TrackingExternalIds, ids: JSONObject?): Boolean {
    if (ids == null) return false
    val comparisons = listOfNotNull(target.imdb?.let { "imdb" to it }, target.tmdb?.let { "tmdb" to "$it" },
        target.trakt?.let { "trakt" to "$it" }, target.simkl?.let { "simkl" to "$it" }, target.mal?.let { "mal" to "$it" })
        .filter { ids.has(it.first) && !ids.isNull(it.first) }
    return comparisons.isNotEmpty() && comparisons.all { (key, value) -> ids.optString(key) == value }
}
internal fun ratingCounters(value: JSONObject?): Int = value?.keys()?.asSequence()?.sumOf { value.optInt(it).coerceAtLeast(0) } ?: 0
internal fun hasRatingErrors(value: JSONObject): Boolean {
    fun nonempty(item: Any?): Boolean = when (item) {
        null, JSONObject.NULL -> false
        is JSONArray -> item.length() > 0
        is JSONObject -> item.keys().asSequence().any { nonempty(item.opt(it)) }
        is Number -> item.toDouble() > 0
        is String -> item.isNotBlank()
        else -> true
    }
    return nonempty(value.opt("errors")) || nonempty(value.opt("not_found")) || nonempty(value.opt("error"))
}
