package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class XtreamStreamFormatTest {
    @Test fun autoFollowsAccountCapabilitiesWithoutGuessing() {
        assertEquals("ts", xtreamLiveExtension("auto", emptySet()))
        assertEquals("ts", xtreamLiveExtension("auto", setOf("ts", "m3u8")))
        assertEquals("m3u8", xtreamLiveExtension("auto", setOf("m3u8")))
        assertEquals("ts", xtreamLiveExtension("auto", setOf("ts")))
        assertEquals("ts", xtreamLiveExtension("unknown", emptySet()))
    }
    @Test fun explicitPerPlaylistChoicesRemainIndependent() {
        val account = setOf("ts", "m3u8")
        assertEquals("ts", xtreamLiveExtension("ts", account))
        assertEquals("m3u8", xtreamLiveExtension("m3u8", account))
        assertEquals("ts", xtreamLiveExtension("auto", account))
    }
}
