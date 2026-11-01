package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.simkl.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PersonalRatingsServiceTest {
    private val profileId = MutableStateFlow(1)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profileId }
    private val trakt = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val store = mockk<TraktAuthDataStore> { every { state } returns MutableStateFlow(TraktAuthState()) }
    private val simkl = mockk<SimklApiClient>()
    private val simklStore = mockk<SimklAuthStorage>(relaxed = true)
    private val mdb = mockk<MdbListApiClient>()
    private val mdbStore = mockk<MdbListAuthStore>(relaxed = true)
    private val target = PersonalRatingTarget(true, TrackingExternalIds(imdb = "tt1234567"))
    private val session = PersonalRatingSession(1, "fixture", SimklAuthScope(1, 1), MdbListAuthScope(1, 1), PersonalRatingProvider.entries.toSet())
    private fun service(): PersonalRatingsService {
        every { simklStore.state } returns MutableStateFlow(SimklAuthState())
        every { mdbStore.state } returns MutableStateFlow(MdbListAuthState(scope = MdbListAuthScope(1, 1)))
        every { simklStore.isCurrent(any()) } returns true
        coEvery { auth.getCurrentAuthState() } returns TraktAuthState(accessToken = "fixture", refreshToken = "fixture", username = "fixture")
        coEvery { auth.executeAuthorizedWriteRequest<ResponseBody>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<ResponseBody>>().invoke("Bearer fixture")
        }
        coEvery { auth.executeAuthorizedRequest<ResponseBody>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<ResponseBody>>().invoke("Bearer fixture")
        }
        return PersonalRatingsService(profiles, trakt, auth, store, simkl, simklStore, mdb, mdbStore)
    }

    @Test fun partialFailuresDoNotSuppressOtherTrackers() = runTest {
        val service = service()
        coEvery { trakt.setPersonalRating(any(), any()) } throws IllegalStateException("fixture failure")
        coEvery { simkl.execute(any(), any()) } returns SimklApiResponse(200, """{"added":{"movies":1}}""", emptyMap())
        coEvery { mdb.post("/sync/ratings", any(), any(), any()) } returns MdbListHttpResponse(200, """{"updated":{"movies":1}}""")
        val result = service.write(target, session, 8).associateBy { it.provider }
        assertFalse(result.getValue(PersonalRatingProvider.TRAKT).confirmed)
        assertTrue(result.getValue(PersonalRatingProvider.SIMKL).confirmed)
        assertTrue(result.getValue(PersonalRatingProvider.MDBLIST).confirmed)
    }

    @Test fun unacknowledgedSuccessMustMatchDestinationReadback() = runTest {
        val service = service()
        coEvery { trakt.setPersonalRating(any(), any()) } returns Response.success("{}".toResponseBody())
        coEvery { trakt.getPersonalRatings(any(), "movies", 1, any()) } returns Response.success(
            """[{"rating":8,"movie":{"ids":{"imdb":"tt1234567"}}}]""".toResponseBody())
        assertTrue(service.write(target, session.copy(providers = setOf(PersonalRatingProvider.TRAKT)), 8).single().confirmed)
        assertFalse(service.write(target, session.copy(providers = setOf(PersonalRatingProvider.TRAKT)), 9).single().confirmed)
    }

    @Test fun removalUsesRatingEndpointWithoutWatchedHistorySideEffects() = runTest {
        val service = service()
        coEvery { trakt.removePersonalRating(any(), any()) } returns Response.success("""{"deleted":{"movies":1}}""".toResponseBody())
        assertTrue(service.write(target, session.copy(providers = setOf(PersonalRatingProvider.TRAKT)), null).single().confirmed)
        coVerify(exactly = 1) { trakt.removePersonalRating(any(), any()) }
        confirmVerified(trakt)
    }

    @Test fun changedProfileCannotWrite() = runTest {
        val service = service()
        profileId.value = 2
        try {
            service.write(target, session, 8)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        coVerify(exactly = 0) { simkl.execute(any(), any()) }
        coVerify(exactly = 0) { mdb.post(any(), any(), any(), any()) }
    }

    @Test fun staleTrackerProfileCannotReceiveTheCurrentProfilesRating() = runTest {
        val service = service()
        val stale = session.copy(simkl = SimklAuthScope(2, 1), providers = setOf(PersonalRatingProvider.SIMKL))
        try { service.write(target, stale, 8); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        coVerify(exactly = 0) { simkl.execute(any(), any()) }
    }

    @Test fun errorResponseCannotConfirmARemoval() = runTest {
        val service = service()
        coEvery { simkl.execute(any(), any()) } coAnswers {
            val request = firstArg<SimklApiRequest>()
            SimklApiResponse(200, if (request.method == SimklHttpMethod.POST) "{}" else """{"error":"fixture"}""", emptyMap())
        }
        assertFalse(service.write(target, session.copy(providers = setOf(PersonalRatingProvider.SIMKL)), null).single().confirmed)
    }

    @Test fun emptySimklAnimeBucketMeansNotRated() = runTest {
        val service = service()
        coEvery { simkl.execute(any(), any()) } coAnswers {
            val request = firstArg<SimklApiRequest>()
            SimklApiResponse(200, if ("/anime/" in request.path) "null" else "{\"shows\":[]}", emptyMap())
        }
        val result = service.read(target.copy(movie = false), session.copy(providers = setOf(PersonalRatingProvider.SIMKL))).single()
        assertTrue(result.confirmed)
        assertNull(result.rating)
    }

    @Test fun simklStopsOnceTheVerifiedTitleIsFound() = runTest {
        val service = service()
        coEvery { simkl.execute(any(), any()) } returns SimklApiResponse(200,
            """{"shows":[{"user_rating":9,"show":{"ids":{"imdb":"tt1234567"}}}]}""", emptyMap())
        val result = service.read(target.copy(movie = false), session.copy(providers = setOf(PersonalRatingProvider.SIMKL))).single()
        assertTrue(result.confirmed)
        assertEquals(9, result.rating)
        coVerify(exactly = 1) { simkl.execute(any(), any()) }
    }

    @Test fun simklEmptyBucketsDoNotTurnErrorEnvelopesIntoAbsentRatings() {
        assertEquals(0, simklRatingRows("null", "anime").length())
        assertEquals(0, simklRatingRows("{}", "shows").length())
        assertEquals(0, simklRatingRows("{\"anime\":null}", "anime").length())
        try { simklRatingRows("{\"error\":\"fixture\"}", "shows"); fail("Expected API failure") }
        catch (_: IllegalStateException) { }
    }

    @Test fun payloadUsesWholeTitleAndTenPointScale() {
        for (provider in PersonalRatingProvider.entries) {
            val body = ratingBody(target, provider, 9)
            assertEquals(9, body.getJSONArray("movies").getJSONObject(0).getInt("rating"))
            assertFalse(body.toString().contains("season"))
            assertFalse(ratingBody(target, provider, null).getJSONArray("movies").getJSONObject(0).has("rating"))
        }
    }

    @Test fun simklAnimeRemovalUsesDocumentedShowsBucket() {
        val anime = PersonalRatingTarget(false, TrackingExternalIds(mal = 123), anime = true)
        assertTrue(ratingBody(anime, PersonalRatingProvider.SIMKL, 8).has("anime"))
        assertTrue(ratingBody(anime, PersonalRatingProvider.SIMKL, null).has("shows"))
    }

    @Test fun conflictingExternalIdsNeverMatch() {
        assertFalse(ratingIdsMatch(TrackingExternalIds(imdb = "tt123", tmdb = 12), JSONObject("""{"imdb":"tt123","tmdb":99}""")))
        assertFalse(ratingIdsMatch(target.ids, JSONObject("""{"tmdb":99}""")))
        assertTrue(ratingIdsMatch(target.ids, JSONObject("""{"imdb":"tt1234567"}""")))
    }

    @Test fun errorsAndMissingItemsCannotBecomeSuccessfulRatings() {
        assertTrue(hasRatingErrors(JSONObject("""{"not_found":{"movies":[{"ids":{"imdb":"tt123"}}]}}""")))
        assertTrue(hasRatingErrors(JSONObject("""{"errors":"unavailable"}""")))
        assertFalse(hasRatingErrors(JSONObject("""{"not_found":{"movies":[]},"added":{"movies":1}}""")))
    }
}
