package com.nuvio.tv.livetv.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DriveBackupPayloadTest {
    @Test fun validSettingsRemainTyped() {
        val data = driveBackupData(JSONObject("""{"format":1,"data":{"flag":{"t":"b","v":true},"favorites":{"t":"s","v":["a"]},"count":{"t":"l","v":100}}}"""))
        assertTrue(data.getJSONObject("flag").getBoolean("v"))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownVersionCannotClearLocalPreferences() {
        driveBackupData(JSONObject("""{"format":99,"data":{}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedSettingCannotPartiallyRestore() {
        driveBackupData(JSONObject("""{"format":1,"data":{"bad":{"t":"s","v":[1]}}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateSourcesCannotSilentlyDisappear() {
        driveBackupData(JSONObject().put("format", 1).put("data", JSONObject().put("playlists",
            JSONObject().put("t", "str").put("v", """[{"id":"one"},{"id":"one"}]"""))))
    }
    @Test(expected = org.json.JSONException::class) fun malformedNestedSourcesAreRejectedBeforeRestore() {
        driveBackupData(JSONObject().put("format", 1).put("data", JSONObject().put("epg_sources",
            JSONObject().put("t", "str").put("v", "not json"))))
    }
    @Test(expected = IllegalArgumentException::class) fun outOfRangeProfileCannotBecomeAnOrphanedOrder() {
        driveBackupData(JSONObject("""{"format":1,"data":{"reseq_tvdb_version":{"t":"i","v":1},"reseq_tvdb:7:order:tt123":{"t":"str","v":"dvd"}}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun versionMarkerCannotMasqueradeAsAStringPreference() {
        driveBackupData(JSONObject("""{"format":1,"data":{"reseq_tvdb_version":{"t":"str","v":"1"}}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun shuffleSectionsNeedTheirVersionMarker() {
        driveBackupData(JSONObject("""{"format":1,"data":{"reseq_shuffle:1:tmdb:123":{"t":"str","v":"{}"}}}"""))
    }
    @Test fun legacyBackupsRemainReadableWithoutInventedShuffleChoices() {
        val data = driveBackupData(JSONObject("""{"format":1,"data":{}}"""))
        assertFalse(data.has("reseq_shuffle_version"))
    }
    @Test(expected = IllegalStateException::class) fun conflictingShuffleEditsDoNotCombineDifferentPools() {
        fun snapshot(enabled: Boolean, watched: Boolean) = JSONObject().put("reseq_shuffle:1:tmdb:123", JSONObject().put("t", "str")
            .put("v", JSONObject().put("enabled", enabled).put("includeWatched", watched).toString()))
        DriveSyncConflict.requireCompatible(snapshot(true, false), snapshot(true, true), snapshot(false, false), emptySet())
    }

    @Test fun independentFavoriteEditsKeepAdditionsAndExplicitRemovals() {
        fun entry(values: String) = JSONObject("""{"t":"s","v":$values}""")
        val merged = mergedDriveSet(entry("""["a","b"]"""), entry("""["a","c"]"""), entry("""["a","b","d"]"""))!!
        assertEquals("[\"a\",\"c\",\"d\"]", merged.getJSONArray("v").toString())
    }
    @Test fun unorderedSetValuesDoNotRaiseFalseConflicts() {
        DriveSyncConflict.requireCompatible(JSONObject("""{"f":{"t":"s","v":["a","b"]}}"""),
            JSONObject("""{"f":{"t":"s","v":["b","a","c"]}}"""), JSONObject("""{"f":{"t":"s","v":["a","b","d"]}}"""), emptySet())
    }
}
