package com.nuvio.tv.data.tvdb

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.*
import com.nuvio.tv.domain.model.EpisodeCoordinates
import javax.inject.Inject
import javax.inject.Singleton

data class TvdbOrderPreference(val seriesId: Long, val orderType: String)
data class TvdbSourceReference(val episodeId: Long, val videoId: String, val coordinates: EpisodeCoordinates,
    val titleKey: String? = null, val canonicalEpisodeId: Long? = null,
    val canonicalEpisodeIds: List<Long> = emptyList()) {
    val trackingEpisodeIds: List<Long> get() = canonicalEpisodeIds.ifEmpty { listOf(canonicalEpisodeId ?: episodeId) }
    val trackingEpisodeId: Long get() = trackingEpisodeIds.first()
}

/** Numeric slots are scoped to their native catalogue, not the TVDB series that
 * may combine several native shows. Opaque IDs retain the conservative old guard. */
internal fun episodeSourceParent(videoId: String, coordinates: EpisodeCoordinates): String? =
    videoId.takeIf { it.endsWith(":${coordinates.season}:${coordinates.episode}") }
        ?.removeSuffix(":${coordinates.season}:${coordinates.episode}")

internal fun sameEpisodeSourceParent(first: TvdbSourceReference, second: TvdbSourceReference): Boolean {
    val a = episodeSourceParent(first.videoId, first.coordinates)
    val b = episodeSourceParent(second.videoId, second.coordinates)
    return a == null || b == null || a == b
}

/** All aliases are learned at explicit selection time; reading never resolves IDs online. */
@Singleton
class TvdbOrderStore @Inject constructor(private val factory: ProfileDataStoreFactory) {
    private val snapshots = java.util.concurrent.ConcurrentHashMap<Int, androidx.datastore.preferences.core.Preferences>()

    /** Unknown snapshots cannot safely expose raw cached episode streams. */
    fun cachedSelection(profileId: Int, alias: String): TvdbOrderPreference? {
        val prefs = snapshots[profileId] ?: return null
        val series = prefs[longPreferencesKey("alias:$alias")] ?: return null
        return prefs[stringPreferencesKey("order:$series")]?.let { TvdbOrderPreference(series, it) }
    }
    fun hasSnapshot(profileId: Int) = snapshots.containsKey(profileId)

    fun observeSelection(profileId: Int, alias: String) = factory.get(profileId, "tvdb_orders").data.map { prefs ->
        snapshots[profileId] = prefs
        val series = prefs[longPreferencesKey("alias:$alias")]
        series?.let { prefs[stringPreferencesKey("order:$it")]?.let { type -> TvdbOrderPreference(series, type) } }
    }.distinctUntilChanged()

    fun observeSelectedSeries(profileId: Int) = factory.get(profileId, "tvdb_orders").data.map { prefs ->
        prefs.asMap().keys.mapNotNull { key -> key.name.takeIf { it.startsWith("order:") }?.substringAfter(':')?.toLongOrNull() }.toSet()
    }.distinctUntilChanged()

    /** Only durable user choices and their aliases; never credentials or learned playback caches. */
    suspend fun exportSelections(profileId: Int): Map<String, String> {
        val prefs = factory.get(profileId, "tvdb_orders").data.first()
        val orders = prefs.asMap().filterKeys { (it.name.startsWith("order:") || it.name.startsWith("reset:")) }
        val selected = orders.keys.map { it.name.substringAfter(':') }.toSet()
        return prefs.asMap().filter { (key, value) ->
            key in orders || key.name.startsWith("alias:") && value.toString() in selected
        }.mapKeys { it.key.name }.mapValues { it.value.toString() }
    }

    fun selectionChanges(profileId: Int) = factory.get(profileId, "tvdb_orders").data.map { prefs ->
        prefs.asMap().filterKeys { it.name.startsWith("order:") || it.name.startsWith("reset:") || it.name.startsWith("alias:") }
    }.distinctUntilChanged().map { Unit }

    suspend fun importSelections(profileId: Int, values: Map<String, String>) {
        require(profileId in 1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES)
        validateTvdbSelections(values)
        factory.get(profileId, "tvdb_orders").edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("order:") || it.name.startsWith("reset:") }.forEach {
                @Suppress("UNCHECKED_CAST")
                prefs.remove(it as androidx.datastore.preferences.core.Preferences.Key<Any>)
            }
            values.forEach { (key, value) ->
                if (key.startsWith("alias:")) prefs[longPreferencesKey(key)] = value.toLong()
                else prefs[stringPreferencesKey(key)] = value
            }
        }.also { snapshots[profileId] = it }
    }

    suspend fun sources(profileId: Int, seriesId: Long): Map<Long, TvdbSourceReference> =
        decodeSources(factory.get(profileId, "tvdb_orders").data.first()[stringPreferencesKey("sources:$seriesId")])

    /** First verified references win; remapping one numeric history slot is unsafe. */
    suspend fun rememberSources(profileId: Int, seriesId: Long, candidates: Collection<TvdbSourceReference>): Map<Long, TvdbSourceReference> {
        val existing = sources(profileId, seriesId)
        if (candidates.all { candidate -> existing[candidate.episodeId]?.let {
            (it.titleKey != null || candidate.titleKey == null) && it.trackingEpisodeIds == candidate.trackingEpisodeIds
        } == true }) return existing
        var result = existing
        factory.get(profileId, "tvdb_orders").edit { prefs ->
            val key = stringPreferencesKey("sources:$seriesId")
            val merged = decodeSources(prefs[key]).toMutableMap()
            for (candidate in candidates) {
                val previous = merged[candidate.episodeId]
                if (previous != null) {
                    if (previous.titleKey == null && previous.videoId == candidate.videoId && previous.coordinates == candidate.coordinates) {
                        merged[candidate.episodeId] = previous.copy(titleKey = candidate.titleKey)
                    }
                    if (previous.videoId == candidate.videoId && previous.coordinates == candidate.coordinates &&
                        (candidate.trackingEpisodeIds.containsAll(previous.trackingEpisodeIds) ||
                            candidate.titleKey != null && candidate.titleKey == previous.titleKey)) {
                        merged[candidate.episodeId] = merged.getValue(candidate.episodeId).copy(
                            canonicalEpisodeId = candidate.canonicalEpisodeId, canonicalEpisodeIds = candidate.canonicalEpisodeIds)
                    }
                    continue
                }
                if (merged.values.any { (it.videoId == candidate.videoId ||
                    it.coordinates == candidate.coordinates && sameEpisodeSourceParent(it, candidate)) &&
                    !candidate.trackingEpisodeIds.containsAll(it.trackingEpisodeIds) &&
                    !(candidate.canonicalEpisodeIds.isNotEmpty() && candidate.titleKey != null && candidate.titleKey == it.titleKey) }) continue
                merged[candidate.episodeId] = candidate
            }
            prefs[key] = buildJsonArray {
                merged.values.forEach { ref -> add(buildJsonObject {
                    put("tvdb", ref.episodeId); put("video", ref.videoId)
                    put("season", ref.coordinates.season); put("episode", ref.coordinates.episode)
                    ref.titleKey?.let { put("titleKey", it) }
                    ref.canonicalEpisodeId?.let { put("canonical", it) }
                    if (ref.canonicalEpisodeIds.isNotEmpty()) put("coverage", buildJsonArray { ref.canonicalEpisodeIds.forEach { add(it) } })
                }) }
            }.toString()
            result = merged
        }
        return result
    }
    suspend fun knownSeries(profileId: Int, aliases: Collection<String>): Long? {
        val prefs = factory.get(profileId, "tvdb_orders").data.first()
        return aliases.mapNotNull { prefs[longPreferencesKey("alias:$it")] }.distinct().singleOrNull()
    }
    suspend fun selected(profileId: Int, aliases: Collection<String>): TvdbOrderPreference? {
        val prefs = factory.get(profileId, "tvdb_orders").data.first()
        snapshots[profileId] = prefs
        val ids = aliases.mapNotNull { prefs[longPreferencesKey("alias:$it")] }.distinct()
        val id = ids.singleOrNull() ?: return null
        val order = prefs[stringPreferencesKey("order:$id")] ?: return null
        return TvdbOrderPreference(id, order)
    }

    suspend fun save(profileId: Int, seriesId: Long, order: String?, aliases: Collection<String>) {
        require(seriesId > 0)
        require(order == null || order.matches(Regex("[A-Za-z0-9_-]+")))
        factory.get(profileId, "tvdb_orders").edit { prefs ->
            val key = stringPreferencesKey("order:$seriesId")
            if (order == null) {
                prefs.remove(key)
                prefs[stringPreferencesKey("reset:$seriesId")] = "true"
            } else {
                prefs[key] = order
                prefs.remove(stringPreferencesKey("reset:$seriesId"))
            }
            aliases.filter(String::isNotBlank).forEach { prefs[longPreferencesKey("alias:$it")] = seriesId }
        }.also { snapshots[profileId] = it }
    }
}

private fun decodeSources(raw: String?): Map<Long, TvdbSourceReference> = try {
    if (raw == null) emptyMap() else Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
        val value = element.jsonObject
        val id = value["tvdb"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val video = value["video"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val season = value["season"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
        val episode = value["episode"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        id to TvdbSourceReference(id, video, EpisodeCoordinates(season, episode), value["titleKey"]?.jsonPrimitive?.contentOrNull,
            value["canonical"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
            (value["coverage"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull?.takeIf { id -> id > 0 } })
    }.toMap()
} catch (_: Exception) { emptyMap() }

internal fun validateTvdbSelections(values: Map<String, String>) {
    values.forEach { (key, value) ->
        val valid = when {
            key.startsWith("order:") -> key.substringAfter(':').toLongOrNull()?.let { it > 0 } == true &&
                value.matches(Regex("[A-Za-z0-9_-]+")) && "reset:${key.substringAfter(':')}" !in values
            key.startsWith("reset:") -> key.substringAfter(':').toLongOrNull()?.let { it > 0 } == true && value == "true"
            key.startsWith("alias:") -> key.length > 6 && value.toLongOrNull()?.let { it > 0 } == true &&
                ("order:$value" in values || "reset:$value" in values)
            else -> false
        }
        require(valid) { "Invalid TVDB order backup" }
    }
}
