package com.nuvio.tv.livetv.ui

import org.junit.Assert.*
import org.junit.Test

class GuideLeftGestureTest {
    @Test fun shortTapIsNotAHold() {
        assertFalse(isGuideLeftHold(0, false))
        assertFalse(isGuideLeftHold(120, false))
        assertFalse(isGuideLeftHold(499, false))
    }
    @Test fun platformLongPressCanArriveBeforeTheFallbackTimeout() {
        assertTrue(isGuideLeftHold(400, true))
    }
    @Test fun remoteWithoutRepeatsIsRecognizedOnRelease() {
        assertTrue(isGuideLeftHold(500, false))
        assertTrue(isGuideLeftHold(1200, false))
    }
}
