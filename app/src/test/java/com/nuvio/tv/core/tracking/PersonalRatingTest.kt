package com.nuvio.tv.core.tracking

import org.junit.Assert.*
import org.junit.Test

class PersonalRatingTest {
    @Test fun defaultMovieAndSeriesUseNativeIds() {
        assertEquals("tt1234567", personalRatingTarget("tt1234567", "movie")!!.ids.imdb)
        assertEquals("shows", personalRatingTarget("tmdb:1437", "series")!!.group)
    }
    @Test fun conflictingIdsAreRejected() {
        assertNull(personalRatingTarget("tt1234567", "movie", "tt7654321"))
        assertNull(personalRatingTarget("provider:item", "movie"))
        assertNull(personalRatingTarget("tvdb:80552", "series"))
    }
    @Test fun nativeTrackerIdsAreNotInventedForOtherServices() {
        val target = personalRatingTarget("simkl:123", "movie")!!
        assertTrue(target.supports(PersonalRatingProvider.SIMKL))
        assertFalse(target.supports(PersonalRatingProvider.TRAKT))
        assertFalse(target.supports(PersonalRatingProvider.MDBLIST))
    }
    @Test fun animeUsesSimklBucketAndReliableAnimeIds() {
        val target = personalRatingTarget("mal:123", "anime")!!
        assertEquals("anime", target.groupFor(PersonalRatingProvider.SIMKL))
        assertTrue(target.supports(PersonalRatingProvider.SIMKL))
        assertFalse(target.supports(PersonalRatingProvider.TRAKT))
    }
    @Test fun genuineMovieCompletionMayPrompt() {
        assertTrue(shouldRequestMovieRating(true, "movie", true, false))
    }
    @Test fun earlyStopsErrorsAndEpisodesCannotPrompt() {
        assertFalse(shouldRequestMovieRating(true, "movie", false, false))
        assertFalse(shouldRequestMovieRating(true, "movie", true, true))
        assertFalse(shouldRequestMovieRating(true, "series", true, false))
    }
    @Test fun promptRemainsOptIn() {
        assertFalse(shouldRequestMovieRating(false, "movie", true, false))
        assertFalse(com.nuvio.tv.data.local.PlayerSettings().promptToRateAfterWatching)
    }
}
