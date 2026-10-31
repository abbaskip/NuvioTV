package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.widget.Toast
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.EpisodeIdentity
import com.nuvio.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class TvdbPlayerSourceGuardTest {
    private val controller = mockk<PlayerRuntimeController>(relaxed = true)
    private val context = mockk<Context>()
    private val toast = mockk<Toast>(relaxed = true)
    private val unresolved = Video(
        id = "tvdb:73244:unresolved",
        title = "Unresolved episode",
        released = null,
        thumbnail = null,
        season = 5,
        episode = 3,
        overview = null,
        episodeIdentity = EpisodeIdentity(390588, 73244, "alternate", EpisodeCoordinates(5, 3), null)
    )

    @Before
    fun setUp() {
        mockkStatic(Toast::class)
        every { controller.context } returns context
        every { Toast.makeText(context, any<CharSequence>(), Toast.LENGTH_LONG) } returns toast
    }

    @After
    fun tearDown() {
        unmockkStatic(Toast::class)
    }

    @Test
    fun `all episode loading entry points reject unresolved sources before searching or autoplay`() {
        controller.loadStreamsForEpisode(unresolved)
        controller.loadStreamsForEpisode(unresolved, forceRefresh = true)
        controller.loadStreamsForEpisode(unresolved, forceRefresh = false, manualSelection = false)
        controller.loadStreamsForEpisode(unresolved, forceRefresh = false, manualSelection = true)

        verify(exactly = 4) { toast.show() }
        verify(exactly = 0) { controller.streamRepository }
        verify(exactly = 0) { controller.playerSettingsDataStore }
    }
}
