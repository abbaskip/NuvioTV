package com.nuvio.tv.livetv.sync

import org.json.JSONObject
import org.junit.Test

class DriveSyncConflictTest {
    private fun setup(vararg pairs: Pair<String, String>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, JSONObject().put("t", "str").put("v", value)) }
    }

    @Test fun independentSeriesAndProfileEditsDoNotConflict() {
        val base = setup("reseq_tvdb:1:order:78874" to "dvd")
        val local = setup("reseq_tvdb:1:order:78874" to "alternate", "reseq_tvdb:2:order:78874" to "dvd")
        val remote = setup("reseq_tvdb:1:order:78874" to "dvd", "reseq_tvdb:1:order:73244" to "alternate_2")
        DriveSyncConflict.requireCompatible(base, local, remote, emptySet())
    }

    @Test(expected = IllegalStateException::class) fun competingOrderEditsAreReported() {
        DriveSyncConflict.requireCompatible(setup("order" to "dvd"), setup("order" to "alternate"), setup("order" to "official"), emptySet())
    }

    @Test fun sameEditOnBothDevicesIsSafe() {
        DriveSyncConflict.requireCompatible(setup("order" to "dvd"), setup("order" to "alternate"), setup("order" to "alternate"), emptySet())
    }

    @Test(expected = IllegalStateException::class) fun removalVersusEditIsNotSilentlyLost() {
        DriveSyncConflict.requireCompatible(setup("order" to "dvd"), JSONObject(), setup("order" to "alternate"), emptySet())
    }

    @Test fun sourceFieldEditsMergeIndependently() {
        val base = setup("playlists" to """[{"id":"one","name":"A","url":"old"}]""")
        val local = setup("playlists" to """[{"id":"one","name":"B","url":"old"}]""")
        val remote = setup("playlists" to """[{"id":"one","name":"A","url":"new"}]""")
        DriveSyncConflict.requireCompatible(base, local, remote, setOf("playlists"))
    }
}
