package com.nuvio.tv.livetv.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsViewModel @Inject constructor(
    val repo: SportsRepository,
    val gemini: SportsGemini,
    val playback: LiveTvPlaybackController,
    private val livePrefs: LiveTvPreferences,
    liveRepo: LiveTvRepository,
    private val recordings: com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
) : ViewModel() {
    val settings = repo.settings
    val follows = repo.follows
    val events = repo.events
    /** The page's days plus followed teams' whole seasons (My Teams, game pages). */
    val known = repo.known
    val matches = repo.matches
    val loading = repo.loading
    val matching = repo.matching
    val problem = repo.problem

    val use24h: StateFlow<Boolean> = livePrefs.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The guide's chosen backdrop (Settings → Live TV), shared so Sports matches it. */
    val backdrop: StateFlow<String> = livePrefs.settings.map { it.guideBackdrop }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    /** Live TV has channels at all (otherwise the page explains channels need a playlist). */
    val hasChannels: StateFlow<Boolean> = liveRepo.channels.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    /** Live TV's "record early" and "record late" minutes (Settings → Live TV → Recordings). */
    val recordPadding: StateFlow<Pair<Int, Int>> = livePrefs.settings.map { it.recordPreMinutes to it.recordPostMinutes }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2 to 5)

    init {
        viewModelScope.launch { recordings.schedules.collect { SportsRecordings.update(it) } }
        repo.start()
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    /** Tunes Live TV to the channel (the screen then opens it full screen). */
    fun watch(m: ChannelMatch) {
        playback.play(m.channel)
    }

    // ---- the video window (the same player as Live TV's preview and full screen)

    /** The channel playing now (null: nothing), for the PLAYING tags and the window. */
    val playingKey: StateFlow<String?> = playback.state
        .map { if (it.catchupTitle == null) it.channelKey else null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The window shares the controller with Live TV, including catch-up and recordings.
    // playingKey is intentionally live-only for event badges; it cannot describe an empty window.
    val previewState = playback.state

    /** Something is playing (or starting) in the window, not paused: the TV stays awake. */
    val playingNow: StateFlow<Boolean> = playback.state
        .map { it.channelKey != null && (it.isPlaying || it.isBuffering) && !it.userPaused }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val player: androidx.media3.common.Player? get() = playback.player
    fun attachPlayer(): androidx.media3.common.Player = playback.attach()
    fun detachPlayer() { playback.detach() }
    fun onSurfaceAttached() = playback.onSurfaceAttached()

    /** Plays the channel in the Sports page's window (no full screen), remembered as last watched. */
    fun playHere(m: ChannelMatch) {
        if (playback.playingChannel?.key == m.channel.key && playingKey.value != null) return
        playback.play(m.channel)
        viewModelScope.launch { runCatching { livePrefs.recordWatched(m.channel.key) } }
    }

    /**
     * From the start (catch-up): handed to Live TV, which starts it when it opens. False when
     * the channel can't replay it.
     */
    fun watchFromStart(ev: SportsEvent, m: ChannelMatch): Boolean {
        if (!m.catchup) return false
        val league = SportsCatalog.league(ev.leagueId)
        val program = m.program?.takeIf { it.startMs <= ev.startMs + 30 * 60_000L }
            ?: EpgProgram(
                startMs = ev.startMs,
                stopMs = ev.endMs ?: (ev.startMs + (league?.minutes ?: 180) * 60_000L),
                title = ev.title
            )
        SportsLaunch.catchup(SportsLaunch.Catchup(m.channel.key, program))
        return true
    }

    /** Nothing followed yet and Pick Your Teams never seen: it opens by itself. */
    suspend fun isFirstVisit(): Boolean {
        val f = SportsFollows.fromJson(livePrefs.sportsMineJson.first())
        return !f.setUp && f.isEmpty
    }

    /**
     * Records [ev] on [m]'s channel: from 5 minutes before the start (or Live TV's own "record
     * early", if that's more) for [minutes] from the start, plus Live TV's "record late".
     * Returns the message to show.
     */
    fun record(ev: SportsEvent, m: ChannelMatch, minutes: Int): String {
        val pre = recordPadding.value.first * 60_000L
        val extraEarly = (SportsRecordings.EARLY_MS - pre).coerceAtLeast(0L)
        val league = SportsCatalog.league(ev.leagueId)
        val program = EpgProgram(
            startMs = ev.startMs - extraEarly,
            stopMs = ev.startMs + minutes * 60_000L,
            title = ev.title,
            description = listOfNotNull(league?.name, ev.subtitle).joinToString(" · ").ifBlank { null }
        )
        return recordings.add(m.channel, program)
    }

    fun cancelRecording(ev: SportsEvent) {
        SportsRecordings.forEvent(ev)?.let { recordings.cancel(it.id) }
    }

    suspend fun teams(league: League): List<TeamInfo> = repo.teams(league)
    suspend fun plays(ev: SportsEvent): List<ScoringPlay> = repo.scoringPlays(ev)
    suspend fun extras(ev: SportsEvent): GameExtras? = repo.gameExtras(ev)
}
