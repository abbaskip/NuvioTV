package com.nuvio.tv.core.streams

import java.security.MessageDigest

/** Ordering must identify a configured addon without copying its potentially credentialed URL. */
fun streamSourceKey(value: String): String = if (value.startsWith("https://") || value.startsWith("http://")) {
    "addon:" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
} else value
