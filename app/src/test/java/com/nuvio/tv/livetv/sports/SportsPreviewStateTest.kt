package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState
import org.junit.Assert.*
import org.junit.Test

class SportsPreviewStateTest {
    @Test fun placeholderOnlyWhenPlayerIsEmpty() {
        assertFalse(LivePlaybackState().hasPreviewContent)
        val selected = LivePlaybackState(channelKey = "channel")
        for (state in listOf(selected, selected.copy(isPlaying = true), selected.copy(isBuffering = true),
            selected.copy(catchupTitle = "Replay"), selected.copy(userPaused = true), selected.copy(error = "Retrying"),
            LivePlaybackState(isPlaying = true), LivePlaybackState(isBuffering = true))) {
            assertTrue(state.toString(), state.hasPreviewContent)
        }
        assertFalse(selected.copy(channelKey = null).hasPreviewContent)
    }
}
