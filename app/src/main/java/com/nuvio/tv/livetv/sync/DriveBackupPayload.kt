package com.nuvio.tv.livetv.sync

import org.json.JSONArray
import org.json.JSONObject

/** Validate the whole envelope before any preferences are changed. */
internal fun driveBackupData(envelope: JSONObject): JSONObject {
    require(envelope.optInt("format") == 1) { "Unsupported Drive backup format" }
    val data = requireNotNull(envelope.optJSONObject("data")) { "Invalid Drive backup" }
    data.keys().forEach { key ->
        val entry = requireNotNull(data.optJSONObject(key)) { "Invalid Drive setting" }
        val value = entry.opt("v")
        val valid = when (entry.optString("t")) {
            "b" -> value is Boolean
            "i" -> value is Number && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE && value.toDouble() == value.toLong().toDouble()
            "l" -> value is Number && value.toDouble() == value.toLong().toDouble()
            "str" -> value is String
            "s" -> value is JSONArray && (0 until value.length()).all { value.opt(it) is String }
            else -> false
        }
        require(valid) { "Invalid Drive setting type" }
    }
    if (data.has("reseq_tvdb_version")) require(data.getJSONObject("reseq_tvdb_version").getString("t") == "i" && data.getJSONObject("reseq_tvdb_version").getInt("v") == 1) {
        "Unsupported TVDB backup format"
    }
    for (key in listOf("playlists", "epg_sources")) {
        if (!data.has(key)) continue
        val entry = data.getJSONObject(key)
        require(entry.getString("t") == "str") { "Invalid source list" }
        val sources = JSONArray(entry.getString("v"))
        val ids = mutableSetOf<String>()
        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val id = source.get("id")
            require(id is String && id.isNotBlank() && ids.add(id)) { "Invalid or duplicate source ID" }
        }
    }
    data.keys().forEach { key ->
        if (key.startsWith("reseq_tvdb:")) {
            require(data.has("reseq_tvdb_version") && key.substringAfter(":").substringBefore(":").toIntOrNull() in 1..6 &&
                data.getJSONObject(key).getString("t") == "str") { "Invalid profile episode-order preference" }
        }
        if (key.startsWith("reseq_profile:")) {
            require(key.substringAfter(":").toIntOrNull() in 1..6 && data.getJSONObject(key).getString("t") == "str" &&
                data.getJSONObject(key).getString("v").isNotBlank()) { "Invalid backup profile" }
        }
    }
    return data
}

internal fun mergedDriveSet(base: JSONObject?, local: JSONObject?, remote: JSONObject?): JSONObject? {
    if (base?.optString("t") != "s" || local?.optString("t") != "s" || remote?.optString("t") != "s") return null
    fun members(entry: JSONObject): Set<String> {
        val array = entry.getJSONArray("v")
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
    val b = members(base); val l = members(local); val r = members(remote)
    val merged = (b - (b - l) - (b - r)) + (l - b) + (r - b)
    return JSONObject().put("t", "s").put("v", JSONArray(merged.sorted()))
}
