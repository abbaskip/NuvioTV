package com.nuvio.tv.livetv.data

/** Used only while generating provider-authorised Xtream live links, never arbitrary playlist URLs. */
internal fun xtreamLiveExtension(preference: String, allowedFormats: Set<String>): String = when (preference) {
    "ts" -> "ts"
    "m3u8" -> "m3u8"
    // Preserve Auto negotiation: TS unless this account advertises HLS without TS.
    else -> if (allowedFormats.isNotEmpty() && "ts" !in allowedFormats && "m3u8" in allowedFormats) "m3u8" else "ts"
}
