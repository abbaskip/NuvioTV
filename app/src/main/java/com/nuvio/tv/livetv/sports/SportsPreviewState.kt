package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState

/** A selected, buffering, paused or failed source is occupied; only an empty player invites selection. */
internal val LivePlaybackState.hasPreviewContent: Boolean
    get() = channelKey != null || isPlaying || isBuffering
