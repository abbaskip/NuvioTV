package com.nuvio.tv.livetv.sync

import org.json.JSONArray
import org.json.JSONObject

/** Refuse competing edits instead of silently choosing a device and losing configuration. */
internal object DriveSyncConflict {
    fun requireCompatible(base: JSONObject, local: JSONObject, remote: JSONObject, sourceLists: Set<String>) {
        val keys = (base.keys().asSequence() + local.keys().asSequence() + remote.keys().asSequence()).toSet()
        for (key in keys) {
            if (key in sourceLists) {
                fun sources(data: JSONObject): Map<String, JSONObject> {
                    val raw = data.optJSONObject(key)?.optString("v") ?: "[]"
                    val array = JSONArray(raw)
                    return (0 until array.length()).associate { i ->
                        val value = array.getJSONObject(i)
                        value.getString("id") to value
                    }
                }
                val b = sources(base); val l = sources(local); val r = sources(remote)
                for (id in b.keys + l.keys + r.keys) {
                    val before = b[id]; val here = l[id]; val there = r[id]
                    if (before != null && (here == null || there == null)) {
                        conflict(before, here, there)
                    } else {
                        for (field in (before?.keys()?.asSequence().orEmpty() + here?.keys()?.asSequence().orEmpty() + there?.keys()?.asSequence().orEmpty()).toSet()) {
                            conflict(before?.opt(field), here?.opt(field), there?.opt(field))
                        }
                    }
                }
            } else conflict(base.opt(key), local.opt(key), remote.opt(key))
        }
    }

    private fun conflict(base: Any?, local: Any?, remote: Any?) {
        check(same(local, base) || same(remote, base) || same(local, remote)) {
            "Both TVs changed the same setting. No backup was overwritten; choose Restore to use the saved setup."
        }
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> {
            val keys = a.keys().asSequence().toSet()
            keys == b.keys().asSequence().toSet() && keys.all { same(a.opt(it), b.opt(it)) }
        }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        else -> a == b
    }
}
