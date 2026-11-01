package com.nuvio.tv.domain.model

/** Captured content selection for catalogue sources; never changes an addon's search coordinates. */
data class StreamContentContext(
    val contentId: String,
    val title: String,
    val year: Int? = null,
    val episodeTitle: String? = null,
    val tvdb: TvdbPlaybackContext? = null
)
