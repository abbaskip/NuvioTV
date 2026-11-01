package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LivePlaybackState(
    val channelKey: String? = null,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** The stream's pixel-shape flag (1 = square pixels). */
    val pixelRatio: Float = 1f,
    /** Non-null while an archive (catch-up) program is playing instead of the live stream. */
    val catchupTitle: String? = null,
    /**
     * You paused it (play/pause), as opposed to it just not playing yet: between channels and
     * while reconnecting it isn't playing either, and that must not count as paused.
     */
    val userPaused: Boolean = false
)

data class LiveTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
    /** Closed captions inside the video: which kind ([LiveCaptions.CC1]…), or null for subtitles. */
    val kind: String? = null,
    /** The track's language code ("en", "es"…), if it says. */
    val language: String? = null,
    /** Audio channels (2 stereo, 6 for 5.1), 0 if unknown. */
    val channels: Int = 0
)

/**
 * One ExoPlayer shared by the guide preview window and the full-screen player, so going
 * full screen (and back) never re-buffers the stream — just like TiviMate.
 */
@OptIn(UnstableApi::class)
@Singleton
class LiveTvPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stalker: com.nuvio.tv.livetv.data.StalkerPortalClient,
    private val preferences: com.nuvio.tv.livetv.data.LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var portalLinkJob: Job? = null

    private var _player: ExoPlayer? = null
    val player: ExoPlayer? get() = _player

    private val _state = MutableStateFlow(LivePlaybackState())
    val state: StateFlow<LivePlaybackState> = _state.asStateFlow()

    private var currentChannel: LiveChannel? = null

    // ---------------------------------------------------------------- pause and rewind

    private val timeshift = LiveTvTimeshift(context).also {
        // Nothing is recording when the app starts: whatever's left from last time (the app
        // closed or the box switched off while paused) goes.
        it.cleanLeftovers(listOf(java.io.File(context.cacheDir, "timeshift"), it.baseDir))
    }
    /**
     * Catch-up replays (TS) saved to the device while they play, and played from there: skipping
     * back is instant, and so is skipping ahead as far as the replay has come in. Before, every
     * skip asked the provider for the replay again from the new point. HLS replays can already
     * skip within themselves and are played as before.
     */
    private val replayCache = LiveTvTimeshift(context)
    /** Settings: save catch-up replays while they play (see [replayCache]). */
    var catchupCache: Boolean = true
    @Volatile private var replayLocal = false
    private var replayJob: Job? = null

    private fun stopReplayCache() {
        replayJob?.cancel()
        replayJob = null
        replayLocal = false
        if (replayCache.session != null) replayCache.stop()
    }

    /** Starts saving the catch-up at [url] and plays it from the device once the first part is in. */
    private fun startReplayCache(channel: LiveChannel, url: String): Boolean {
        val p = _player ?: return false
        replayCache.baseDir = timeshift.baseDir
        if (!replayCache.hasRoomFor(120)) return false
        val session = replayCache.start(url, channel.headers, keepMinutes = null, reconnect = false, label = "Catch-up save") ?: return false
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: saving the replay while it plays, for instant skipping")
        p.stop()
        replayJob?.cancel()
        replayJob = scope.launch {
            val f = java.io.File(session.playUri.path ?: "")
            var waited = 0
            while (waited < 15_000 && !(f.exists() && f.length() > 0)) { delay(200); waited += 200 }
            if (replayCache.session != session || currentUrl != url) return@launch
            archiveStartPending = true
            if (!f.exists() || f.length() == 0L) {
                // Nothing came in to save: play it straight from the provider, as before (any
                // other links for this replay are tried from there if it fails).
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: nothing came in to save; playing it directly")
                replayCache.stop()
                p.setMediaSource(buildMediaSource(url, channel.headers, isLive = false))
            } else {
                replayLocal = true
                p.setMediaSource(localRecordingSource(session.playUri), 0L)
            }
            p.prepare()
            p.playWhenReady = true
        }
        return true
    }
    var timeshiftEnabled: Boolean = true
    var timeshiftMinutes: Int = 10
    /** Rewind anytime: full-screen channels play through a rolling buffer (see [startAlwaysBuffer]). */
    var rewindAlways: Boolean = false
    @Volatile private var bufferStorageId: String? = null

    /** The buffer goes on the drive set for recordings (a USB drive has far more room). */
    fun setBufferStorage(id: String) {
        if (id == bufferStorageId) return
        val first = bufferStorageId == null
        bufferStorageId = id
        val old = timeshift.baseDir
        timeshift.baseDir = java.io.File(
            com.nuvio.tv.livetv.record.RecordingStorage.resolve(context, id).dir.parentFile ?: context.cacheDir,
            "timeshift"
        )
        // The drive's own leftovers on first use this session (nothing is recording yet), and
        // the old place's when the setting changes.
        timeshift.cleanLeftovers(listOf(timeshift.baseDir), olderThanMs = if (first) 60_000L else 15 * 60_000L)
        if (old != timeshift.baseDir) timeshift.cleanLeftovers(listOf(old), olderThanMs = 15 * 60_000L)
    }

    /** The current buffer was started by Rewind Anytime (not by pausing). */
    @Volatile private var alwaysBuffer = false
    @Volatile private var bufferStartedAt = 0L
    /** A channel whose buffer kept failing: played directly until another channel is picked. */
    @Volatile private var bufferFailedKey: String? = null

    private fun shouldBuffer(channel: LiveChannel, catchupTitle: String?): Boolean =
        rewindAlways && timeshiftEnabled && catchupTitle == null && channel.drm == null &&
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.value &&
            channel.key != bufferFailedKey && timeshift.hasRoomFor(timeshiftMinutes)

    /**
     * Rewind anytime: the channel is recorded from the moment it starts, and you watch from the
     * recording a few seconds behind live, so you can skip back at any time (up to the buffer
     * length) without having paused. One connection to the provider, as always.
     */
    private fun startAlwaysBuffer(channel: LiveChannel, url: String): Boolean {
        val p = _player ?: return false
        val session = timeshift.start(url, channel.headers, timeshiftMinutes) ?: return false
        alwaysBuffer = true
        bufferStartedAt = System.currentTimeMillis()
        shiftPrepared = false
        _shiftStartedAt.value = session.startedAtMs
        shiftJob?.cancel()
        shiftJob = scope.launch {
            var waited = 0
            val f = java.io.File(session.playUri.path ?: "")
            while (waited < 15_000 && !(f.exists() && f.length() > 0)) { delay(200); waited += 200 }
            if (timeshift.session != session) return@launch
            if (!f.exists()) { bufferFailed(); return@launch }
            p.setMediaSource(localRecordingSource(session.playUri))
            p.prepare()
            p.playWhenReady = true
            shiftPrepared = true
        }
        return true
    }

    /** The buffer couldn't keep up (or the stream won't record): play this channel directly. */
    private fun bufferFailed() {
        val ch = currentChannel ?: return
        bufferFailedKey = ch.key
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Rewind buffer failed on ${ch.name}; playing it directly")
        goLiveFresh()
    }

    /** Drops the buffer and plays the channel again from now (a new buffer, if it's on). */
    private fun goLiveFresh() {
        val ch = currentChannel ?: return
        val url = currentUrl
        stopShift()
        currentUrl = null
        play(ch, overrideUrl = url.takeIf { it != ch.url })
    }

    /** Going full screen with Rewind Anytime on: switch the channel over to the buffer. */
    private fun watchFullscreen() {
        scope.launch {
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.collect { full ->
                val ch = currentChannel ?: return@collect
                if (!full || !rewindAlways || _shiftStartedAt.value != null || _recordingPlayback.value != null) return@collect
                if (_state.value.catchupTitle != null || _player == null) return@collect
                goLiveFresh()
            }
        }
    }

    /**
     * Left / Right should skip in the buffer: always after a pause; with Rewind Anytime, once
     * you're more than a few seconds behind live (otherwise they keep their usual jobs).
     */
    fun shiftIsBehind(): Boolean {
        if (_shiftStartedAt.value == null) return false
        if (!alwaysBuffer || _state.value.userPaused) return true
        val pos = shiftPositionWallMs() ?: return false
        return System.currentTimeMillis() - pos > 20_000
    }

    @Volatile private var shiftReloads = 0

    /** Plays the buffer again from [positionMs] (after it ran dry or a read error). */
    private fun reloadShiftAt(positionMs: Long, why: String) {
        val p = _player ?: return
        val session = timeshift.session ?: return
        shiftReloads++
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: $why; picking up again")
        shiftJob?.cancel()
        shiftJob = scope.launch {
            delay(1_500)
            if (timeshift.session != session) return@launch
            // Never below what's actually still in the buffer (older parts may have been
            // trimmed since): a position earlier than that would just fail the same way again.
            val startAt = positionMs.coerceAtLeast(timeshift.trimmedMs)
            shiftStartPending = startAt == 0L
            p.setMediaSource(localRecordingSource(session.playUri), startAt)
            p.prepare()
            p.playWhenReady = true
            shiftPrepared = true
        }
    }

    /** The earliest point the buffer still has (wall clock), while playing from it. */
    fun shiftEarliestWallMs(): Long? = _shiftStartedAt.value?.let { it + timeshift.trimmedMs }

    private val _shiftStartedAt = MutableStateFlow<Long?>(null)
    /** Non-null while playing from a pause-and-rewind recording: when the recording started. */
    val shiftStartedAt: StateFlow<Long?> = _shiftStartedAt.asStateFlow()
    private var shiftPrepared = false
    /** Pause buffer: a start position of 0 that still has to be applied once the buffer's real timeline is in (see onTimelineChanged). */
    @Volatile private var shiftStartPending = false
    private var shiftJob: Job? = null

    /** Wall-clock time of what's on screen while playing a recording, or null. */
    fun shiftPositionWallMs(): Long? {
        val start = _shiftStartedAt.value ?: return null
        val p = _player ?: return start
        return start + timeshift.trimmedMs + if (shiftPrepared) p.currentPosition.coerceAtLeast(0) else 0L
    }

    /** Skipping back and forward works on every recording (TS ones are saved in pieces too now). */
    fun shiftCanSeek(): Boolean = (timeshift.session != null && shiftPrepared) || _recordingPlayback.value != null

    // ---------------------------------------------------------------- watching while recording

    private val _recordingPlayback = MutableStateFlow<java.io.File?>(null)
    /**
     * Non-null while the channel is playing from its own recording (Record while watching): the
     * recording uses the provider connection, and you watch from it, so one connection does
     * both. Pause and skipping back work as in any recording.
     */
    val recordingPlayback: StateFlow<java.io.File?> = _recordingPlayback.asStateFlow()
    /** True from pressing Record on the channel being watched until its recording starts playing. */
    private val _recordingStarting = MutableStateFlow(false)
    val recordingStarting: StateFlow<Boolean> = _recordingStarting.asStateFlow()

    /** Lets go of the provider connection, so a recording of this channel can use it. */
    fun handOverForRecording() {
        val p = _player ?: return
        if (_shiftStartedAt.value != null) stopShift()
        reconnectJob?.cancel()
        p.stop()
    }

    /** The live link the player is using for [channel] (its own link if something else is on). */
    fun liveUrlFor(channel: LiveChannel): String =
        currentUrl?.takeIf { currentChannel?.key == channel.key && _state.value.catchupTitle == null } ?: channel.url

    /** Plays [playlist] (a recording that's still being made), a few seconds behind live. */
    fun playFromRecording(playlist: java.io.File, fromStart: Boolean = false) {
        val p = _player ?: return
        _recordingPlayback.value = playlist
        _recordingStarting.value = true
        shiftJob?.cancel()
        shiftJob = scope.launch {
          try {
            var waited = 0
            // Wait until about 15 s are saved: playing right behind the newest piece stalls
            // whenever a piece comes in late (the freezes while watching a channel you record).
            fun savedSec(): Double = runCatching {
                playlist.readLines().filter { it.startsWith("#EXTINF:") }
                    .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
            }.getOrDefault(0.0)
            while (waited < 20_000 && !(playlist.exists() && playlist.length() > 0)) { delay(250); waited += 250 }
            while (!fromStart && waited < 20_000 && savedSec() < 15.0) { delay(250); waited += 250 }
            if (_recordingPlayback.value != playlist) return@launch
            if (!playlist.exists()) {
                // Nothing came in: back to the live stream.
                _recordingPlayback.value = null
                currentChannel?.let { ch -> currentUrl = null; play(ch) }
                return@launch
            }
            p.setMediaSource(localRecordingSource(android.net.Uri.fromFile(playlist), 15_000))
            p.prepare()
            p.playWhenReady = true
            if (fromStart) {
                // A growing playlist opens near its newest piece: once it's ready, go back to the start.
                var ready = 0
                while (ready < 8_000 && p.playbackState != androidx.media3.common.Player.STATE_READY) { delay(100); ready += 100 }
                if (_recordingPlayback.value == playlist) p.seekTo(0L)
            }
          } finally {
            _recordingStarting.value = false
          }
        }
    }

    /** Watching a recording that ended (or failed): carry on with the channel live. */
    private fun recordingPlaybackEnded() {
        _recordingPlayback.value = null
        val ch = currentChannel ?: return
        currentUrl = null
        play(ch)
    }

    /**
     * Pause on a live channel without catch-up: record it from here (pause and rewind live TV),
     * so play carries on from this moment. Falls back to a plain pause if recording isn't
     * possible (turned off, not enough storage, encrypted stream).
     */
    fun pauseLive() {
        val p = _player ?: return
        if (_recordingPlayback.value != null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: paused a saved recording"); p.pause(); return }
        val ch = currentChannel
        if (_shiftStartedAt.value != null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: paused again inside the buffer"); p.pause(); return }
        if (ch == null || _state.value.catchupTitle != null || !timeshiftEnabled || !timeshift.hasRoomFor(timeshiftMinutes)) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: plain pause, no recording (channel=${ch != null}, catchup=${_state.value.catchupTitle != null}, setting on=$timeshiftEnabled, room=${timeshift.hasRoomFor(timeshiftMinutes)})")
            p.pause(); return
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: pausing; recording started")
        val url = currentUrl ?: ch.url
        p.pause()
        p.stop() // hand the provider connection over to the recording
        val session = timeshift.start(url, ch.headers, timeshiftMinutes)
        if (session == null) {
            // Couldn't record: back to the live stream, paused.
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: couldn't start recording (no room, or provider refused); paused live instead")
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = true)); p.prepare(); p.playWhenReady = false
            return
        }
        shiftPrepared = false
        _shiftStartedAt.value = session.startedAtMs
        reconnectJob?.cancel()
    }

    /** Play from the recording (first time: once there's something recorded to play). */
    private fun resumeShift() {
        val p = _player ?: return
        val session = timeshift.session
        if (session == null) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: play pressed but there is no recording session"); return }
        if (shiftPrepared) { com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: play pressed (playing from the buffer)"); p.play(); return }
        shiftJob?.cancel()
        shiftJob = scope.launch {
            // Wait until the first part is saved (a few seconds). Never left stuck: if the
            // channel couldn't be recorded (the provider refused, the recording stopped), it goes
            // back to live and says so, instead of staying paused.
            _state.value = _state.value.copy(isBuffering = true)
            var waited = 0
            val f = java.io.File(session.playUri.path ?: "")
            // Up to 25 s: a provider that allows one connection can take a while to let go of
            // the one the player had, and the recording keeps trying meanwhile.
            while (waited < 25_000) {
                if (f.exists() && f.length() > 0) break
                if (!timeshift.isRecording && waited > 2_000) break
                delay(250); waited += 250
            }
            if (!(f.exists() && f.length() > 0)) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: nothing recorded after ${waited / 1000}s; back to live")
                com.nuvio.tv.livetv.record.LiveTvNotice.show("This channel couldn't be recorded to pause it, so it's back to live")
                goLive()
                return@launch
            }
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: first piece saved after ${waited / 1000.0}s; playing from the pause point")
            // Both kinds are a local playlist of pieces now (TS streams are cut up as recorded).
            val source = localRecordingSource(session.playUri)
            // Start where you paused: the recording began the instant you paused, so its very
            // start (0) is where you paused, near enough. The start position is given with the
            // source itself (a seek to 0 made after the player has already read the playlist
            // would be ignored, and a growing playlist would otherwise start near its end) —
            // and it has to be 0 exactly: an offset guessed from the provider's own look-back
            // window could land outside what the player considers the valid live window and
            // get rejected, which is what made resuming slow and land back near live instead of
            // where you paused.
            // A start of 0 given with the source is read as "no start given" and a growing
            // playlist then begins at its newest end (live). So it's set again as a real seek
            // as soon as the stream's own timeline arrives (onTimelineChanged).
            shiftStartPending = true
            p.setMediaSource(source, 0L)
            p.prepare()
            p.playWhenReady = true
            shiftPrepared = true
        }
    }

    /**
     * A local recording (a playlist of pieces) to play. Played at normal speed throughout: as a
     * growing "live" playlist the player would otherwise speed up a little to catch up with the
     * end of it, creeping towards live.
     */
    internal fun localRecordingSource(uri: android.net.Uri, offsetMs: Long = 8_000): androidx.media3.exoplayer.source.MediaSource {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                // "Live" here means a few seconds behind the newest piece (not 3 target lengths).
                MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
                    .setTargetOffsetMs(offsetMs).build()
            )
            .build()
        return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context))
            .createMediaSource(item)
    }

    /** Skip back or forward in the recording. */
    fun shiftSeekBy(deltaMs: Long) {
        val p = _player ?: return
        if (!shiftCanSeek()) return
        val max = if (p.duration > 0) p.duration - 3_000 else p.currentPosition
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, max.coerceAtLeast(0L)))
    }

    /** Go live: drop the recording and play the channel live again. */
    fun goLive() {
        // Rewind anytime: back to the newest part of the buffer (it keeps your rewind history).
        if (alwaysBuffer && _shiftStartedAt.value != null && shiftPrepared) {
            _player?.seekToDefaultPosition(); _player?.play(); return
        }
        // Watching from a recording of this channel: jump to its newest part (live, a few
        // seconds behind), keeping the one connection the recording uses.
        if (_recordingPlayback.value != null) { _player?.seekToDefaultPosition(); _player?.play(); return }
        val ch = currentChannel
        val url = currentUrl
        stopShift()
        if (ch != null) {
            currentUrl = null
            play(ch, overrideUrl = url.takeIf { it != ch.url })
        }
    }

    private fun stopShift() {
        alwaysBuffer = false
        shiftStartPending = false
        shiftReloads = 0
        shiftJob?.cancel()
        shiftJob = null
        if (_shiftStartedAt.value != null || timeshift.session != null) timeshift.stop()
        _shiftStartedAt.value = null
        shiftPrepared = false
    }

    /** The channel the player is on (as it was when playback started). */
    val playingChannel: LiveChannel? get() = currentChannel
    private var currentUrl: String? = null
    private var attachCount = 0
    private var releaseJob: Job? = null
    private var reconnectJob: Job? = null
    var autoReconnect: Boolean = true

    /**
     * Audio passthrough. Off: Dolby / DTS audio is decoded by the app and sent as plain PCM,
     * which is far more robust with Alexa Home Theater / Echo speakers on Fire TV. Changing it
     * rebuilds the player (and carries on with the same channel).
     */
    /**
     * The video's frame rate as measured from the frames themselves (for Match Frame Rate).
     * Many live streams don't state their frame rate, so the player's own value is often
     * missing and the display was never switched.
     */
    @Volatile var measuredFps: Float = -1f
        private set
    private val frameDeltas = LongArray(48)
    private var frameDeltaCount = 0
    private var lastFrameUs = Long.MIN_VALUE
    private val frameListener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        val last = lastFrameUs
        lastFrameUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return@VideoFrameMetadataListener
        val d = presentationTimeUs - last
        if (d !in 5_000L..100_000L) return@VideoFrameMetadataListener // a jump, a seek, a new channel
        frameDeltas[frameDeltaCount % frameDeltas.size] = d
        frameDeltaCount++
        if (frameDeltaCount >= frameDeltas.size && frameDeltaCount % 24 == 0) {
            val median = frameDeltas.sorted()[frameDeltas.size / 2]
            measuredFps = 1_000_000f / median
        }
    }

    /** A new channel: measure its frame rate afresh. */
    private fun resetFrameRate() {
        measuredFps = -1f
        frameDeltaCount = 0
        lastFrameUs = Long.MIN_VALUE
    }

    /** Live TV buffer size ("small", "normal", "large", "xlarge"); changing it rebuilds the player. */
    var bufferSize: String = "normal"
        set(value) {
            if (field == value) return
            field = value
            val p = _player ?: return
            val ch = currentChannel
            val url = currentUrl
            val title = _state.value.catchupTitle
            val wasPlaying = p.playWhenReady
            release()
            if (ch != null && wasPlaying) play(ch, overrideUrl = url.takeIf { it != ch.url }, catchupTitle = title)
        }

    var audioPassthrough: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            val p = _player ?: return
            val ch = currentChannel
            val url = currentUrl
            val title = _state.value.catchupTitle
            val wasPlaying = p.playWhenReady
            release()
            if (ch != null && wasPlaying) play(ch, overrideUrl = url.takeIf { it != ch.url }, catchupTitle = title)
        }

    @Volatile private var watchingFullscreen = false

    fun attach(): ExoPlayer {
        releaseJob?.cancel()
        attachCount++
        if (!watchingFullscreen) { watchingFullscreen = true; watchFullscreen() }
        return _player ?: buildPlayer().also { _player = it }
    }

    /** Releases the player shortly after the last screen detaches (lets route changes hand it over). */
    fun detach() {
        attachCount = (attachCount - 1).coerceAtLeast(0)
        if (attachCount == 0) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(600)
                if (attachCount == 0) release()
            }
        }
    }

    /** Other links to try, in turn, if the current one can't be played (catch-up variants). */
    private val fallbackUrls = ArrayDeque<String>()
    /** The catch-up now playing has actually started (pictures moving), at least once. */
    @Volatile private var archiveStarted = false

    fun play(
        channel: LiveChannel,
        overrideUrl: String? = null,
        catchupTitle: String? = null,
        fallback: String? = null,
        fallbacks: List<String> = emptyList(),
        /** Catch-up: the real time the replay should start at (the show's start). */
        catchupStartMs: Long? = null
    ) {
        portalLinkJob?.cancel()
        portalLinkJob = null
        if (overrideUrl == null && channel.url.startsWith("reseq-stalker://")) {
            reconnectJob?.cancel()
            stallJob?.cancel()
            stallJob = null
            stopReplayCache()
            clearStarting()
            resumeAfterBackground = null
            if (_shiftStartedAt.value != null) stopShift()
            _player?.stop()
            currentChannel = channel
            currentUrl = null
            _state.value = LivePlaybackState(channelKey = channel.key, isBuffering = true)
            portalLinkJob = scope.launch {
                try {
                    val source = preferences.playlists.first().singleOrNull { it.id == channel.sourceId && it.isStalker && it.liveEnabled }
                        ?: error("This portal playlist is unavailable.")
                    val link = stalker.resolve(source, channel.url)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    portalLinkJob = null
                    play(channel.copy(headers = channel.headers + link.headers), overrideUrl = link.url)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { _state.value = LivePlaybackState(channelKey = channel.key, error = "Could not open the portal channel. Check the account or try again.") }
            }
            return
        }
        // Something chosen to play (back from the background through a launcher row, say):
        // that wins over picking up what was on before.
        resumeAfterBackground = null
        shiftStartPending = false
        if (_shiftStartedAt.value != null) stopShift()
        if (_recordingPlayback.value != null) { _recordingPlayback.value = null; shiftJob?.cancel() }
        archiveStartWallMs = if (catchupTitle != null) catchupStartMs else null
        archiveWallMapped = false
        if (currentChannel?.key != channel.key) {
            neverStartedCount = 0
            audioHandled = false
            audioPrefPending = true
            captionsHandled = false
            resetFrameRate()
            LiveCaptions.reset()
            captionWatch?.cancel()
        }
        // A channel whose HLS playlist couldn't be read earlier: its TS link instead.
        val url = overrideUrl ?: channel.url.let { u -> if (channel.key in tsInstead) tsLinkFor(u) ?: u else u }
        if (currentChannel?.key != channel.key || currentUrl != url) {
            forcedMime = null
            formatGuess = 0
            // A new stream (catch-up, another link): ask for the saved audio language again.
            audioPrefPending = true
        }
        // A channel whose "HLS" turned out to be plain TS: read it as TS from the start.
        if (catchupTitle == null && channel.key in plainTs && overrideUrl == null) forcedMime = MimeTypes.VIDEO_MP2T
        fallbackUrls.clear()
        hlsCatchupFailedOn = null
        archiveStarted = false
        fallback?.let { fallbackUrls += it }
        fallbacks.forEach { if (it !in fallbackUrls) fallbackUrls += it }
        if (catchupTitle != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up \"$catchupTitle\" on ${channel.name}: ${redact(url)}")
        startStallWatch()
        val p = _player ?: attach().also { attachCount-- }
        // Captions follow the setting until you change them yourself on this channel. Without
        // this the player switched on captions a stream marks as "default" (ARD, ZDF…) a couple
        // of seconds in, even with captions off in Settings.
        // Audio picked before on this channel: ask for that language from the start (catch-up
        // and reconnects included); without one, the stream's own default as before.
        if (audioPrefPending) {
            audioPrefPending = false
            val lang = channelAudio[channel.key]?.split('|')?.getOrNull(0)?.ifBlank { null }
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(lang)
                .build()
        }
        if (!captionsHandled) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsByDefault)
                .build()
        }
        if (currentChannel?.key == channel.key && currentUrl == url && _state.value.error == null &&
            p.playbackState != Player.STATE_IDLE
        ) {
            if (!p.isPlaying) p.play()
            return
        }
        reconnectJob?.cancel()
        stopReplayCache()
        currentChannel = channel
        LiveWatching.sourceId = channel.sourceId
        currentUrl = url
        _state.value = LivePlaybackState(channelKey = channel.key, isBuffering = true, catchupTitle = catchupTitle)
        if (catchupTitle == null) markStarting(channel.key)
        if (bufferFailedKey != null && bufferFailedKey != channel.key) bufferFailedKey = null
        // Rewind anytime (full screen): play through the rolling buffer instead.
        if (shouldBuffer(channel, catchupTitle)) {
            p.stop()
            if (startAlwaysBuffer(channel, url)) { p.playWhenReady = true; return }
        }
        // A TS catch-up replay: saved while it plays, for instant skipping.
        if (catchupTitle != null && catchupCache && channel.drm == null &&
            !url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) && startReplayCache(channel, url)
        ) return
        archiveStartPending = catchupTitle != null
        p.setMediaSource(buildMediaSource(url, channel.headers, isLive = catchupTitle == null))
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Catch-up just started: begin at the start of what we asked for. Some providers send a
     * show that's still airing as a growing "live" stream, and the player starts those at the
     * live edge (so "Watch from start" landed near now, and you had to rewind by hand).
     */
    @Volatile private var archiveStartPending = false

    /** The real time the replay should start at, when known. */
    @Volatile private var archiveStartWallMs: Long? = null

    /**
     * The provider sent its live stream with a long rewind window instead of the show itself:
     * positions are then worked out from the stream's own clock (see [archiveOffsetMs]).
     */
    @Volatile var archiveWallMapped = false
        private set

    /** The stream's clock time at the current position, when the stream carries one. */
    private fun streamWallMs(p: ExoPlayer): Long? {
        val tl = p.currentTimeline
        if (tl.isEmpty) return null
        val w = tl.getWindow(p.currentMediaItemIndex.coerceIn(0, tl.windowCount - 1), androidx.media3.common.Timeline.Window())
        if (w.isPlaceholder || w.windowStartTimeMs == C.TIME_UNSET) return null
        return w.windowStartTimeMs + p.currentPosition
    }

    /**
     * Time into the show that started at [programStartMs], for a replay that came as the live
     * stream's rewind window. Null for ordinary replays (there the position is the time).
     */
    fun archiveOffsetMs(programStartMs: Long): Long? {
        if (!archiveWallMapped) return null
        val p = _player ?: return null
        return streamWallMs(p)?.let { it - programStartMs }
    }

    /** Seeks such a replay to [offsetMs] into the show; false if it can't be done in the stream. */
    fun seekArchiveTo(programStartMs: Long, offsetMs: Long): Boolean {
        if (!archiveWallMapped) return false
        val p = _player ?: return false
        val wall = streamWallMs(p) ?: return false
        val target = p.currentPosition + (programStartMs + offsetMs - wall)
        if (target < 0) return false
        p.seekTo(target)
        return true
    }

    /** Plain words for playback errors, with the provider's HTTP status when there is one. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> "Provider refused the stream (HTTP ${cause.responseCode}): account limit, expired, or blocked"
                    404 -> "Stream not found (HTTP 404). Try another stream format in the playlist's settings"
                    429, 456, 458, 509 -> "Too many connections on this account (HTTP ${cause.responseCode})"
                    in 500..599 -> "Provider's server error (HTTP ${cause.responseCode})"
                    else -> "Provider answered HTTP ${cause.responseCode}"
                }
            }
            cause = cause.cause
        }
        // Plain words on screen, saying whose end it is ("Parsing manifest malformed" made people
        // think the app was broken). The exact error still goes in the Start-Up Report.
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "The provider isn't sending this channel properly right now"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Can't reach the provider right now"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The provider doesn't have this channel's stream right now"
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                "This channel is protected and couldn't be unlocked"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "This TV couldn't play this channel's picture or sound"
            else -> "This channel isn't playing right now"
        }
    }

    // ---------------------------------------------------------------- sleep timer

    private val _sleepAtMs = MutableStateFlow<Long?>(null)
    /** When the sleep timer stops Live TV (null = off). Runs wherever you are in the app. */
    val sleepAtMs: StateFlow<Long?> = _sleepAtMs.asStateFlow()
    private val _sleepFired = MutableStateFlow(0)
    /** Goes up each time the sleep timer stops playback (full screen closes itself). */
    val sleepFired: StateFlow<Int> = _sleepFired.asStateFlow()
    private var sleepJob: Job? = null

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { _sleepAtMs.value = null; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAtMs.value = at
        sleepJob = scope.launch {
            delay(at - System.currentTimeMillis())
            _sleepAtMs.value = null
            stop()
            _sleepFired.value++
        }
    }

    fun stop() {
        portalLinkJob?.cancel()
        portalLinkJob = null
        clearStarting()
        reconnectJob?.cancel()
        stopReplayCache()
        _player?.stop()
        _player?.clearMediaItems()
        currentChannel = null
        LiveWatching.sourceId = null
        currentUrl = null
        _state.value = LivePlaybackState()
    }

    // ---------------------------------------------------------------- stall watchdog

    /**
     * Some streams stop sending pictures without ever reporting an error (the provider keeps
     * the connection open, or a live HLS list stops updating), leaving "Loading…" on screen
     * forever. If playback makes no progress for [STALL_MS], reload the stream, the same as
     * switching away and back.
     */
    private var stallJob: Job? = null
    private var lastPosition = -1L
    private var lastProgressAt = 0L
    private var lastDiagnosticAt = 0L

    private fun startStallWatch() {
        lastProgressAt = android.os.SystemClock.elapsedRealtime()
        lastPosition = -1L
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            while (true) {
                delay(2_000)
                checkStall()
            }
        }
    }

    private fun checkStall() {
        val p = _player ?: return
        if (_shiftStartedAt.value != null || _recordingPlayback.value != null) { lastProgressAt = android.os.SystemClock.elapsedRealtime(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        val ch = currentChannel
        if (com.nuvio.tv.BuildConfig.DEBUG && now - lastDiagnosticAt >= 15_000) {
            lastDiagnosticAt = now
            Log.d(TAG, "Playback state=${p.playbackState} playing=${p.isPlaying} ready=${p.playWhenReady} " +
                "position=${p.currentPosition} buffered=${p.bufferedPosition} uiBuffering=${_state.value.isBuffering} " +
                "background=$pausedForBackground shift=${_shiftStartedAt.value != null} " +
                "recording=${_recordingPlayback.value != null} awaitingFrame=$awaitingFrame reconnect=$autoReconnect")
        }
        // Paused, in the background, nothing playing, or already showing an error: not a stall.
        if (ch == null || !p.playWhenReady || pausedForBackground || _state.value.error != null ||
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
        ) {
            lastProgressAt = now
            lastPosition = p.currentPosition
            return
        }
        val pos = p.currentPosition
        val moving = p.playbackState == Player.STATE_READY && p.isPlaying && pos != lastPosition
        lastPosition = pos
        if (moving) {
            lastProgressAt = now
            if (_state.value.catchupTitle != null) archiveStarted = true
            return
        }
        if (now - lastProgressAt < STALL_MS || !autoReconnect) return
        lastProgressAt = now
        val archive = _state.value.catchupTitle != null
        // A catch-up that never started (stuck on "Loading…" with no error): some panels answer
        // a form of catch-up link they don't really support with an empty stream instead of an
        // error, so the other link forms were never tried. Move on to the next one; when none
        // are left, say so instead of loading forever.
        if (archive && !archiveStarted) {
            val fb = fallbackUrls.removeFirstOrNull()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up didn't start on ${ch.name} (nothing came in)" + (fb?.let { "; trying ${redact(it)}" } ?: "")
            )
            p.stop()
            if (fb != null) {
                currentUrl = fb
                archiveStartPending = true
                p.setMediaSource(buildMediaSource(fb, ch.headers, isLive = false))
                p.prepare()
                p.playWhenReady = true
            } else {
                scheduleReconnect("Catch-up didn't start: the provider sent nothing for this show")
            }
            return
        }
        Log.w(TAG, "Stream stalled on ${ch.name}; reloading")
        if (!archive && awaitingFrame && ++neverStartedCount >= 2 && onLiveFailure?.invoke(ch.key) == true) return
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "Channel ${ch.name} " + (if (awaitingFrame) "never started (nothing came in for ${STALL_MS / 1000}s)" else "stalled") +
                "; reloading · ${redact(currentUrl ?: ch.url)}"
        )
        val url = currentUrl ?: ch.url
        val resumeAt = if (archive) pos else C_TIME_UNSET
        p.stop()
        archiveStartPending = false
        p.setMediaSource(buildMediaSource(url, ch.headers, isLive = !archive))
        p.prepare()
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.playWhenReady = true
    }

    /** Catch-up moved on to the next show: update the title shown. */
    fun setCatchupTitle(title: String) {
        if (_state.value.catchupTitle != null) _state.value = _state.value.copy(catchupTitle = title)
    }

    fun retry() {
        val ch = currentChannel ?: return
        val url = currentUrl
        currentUrl = null
        play(ch, url, _state.value.catchupTitle)
    }

    private var pausedForBackground = false

    private var backgroundJob: Job? = null
    /** Set when the channel's connection was let go in the background: how to pick it up again. */
    private var resumeAfterBackground: (() -> Unit)? = null

    /**
     * The app left the screen (Home button, another app): stop the sound straight away, and
     * a few seconds later let go of the provider connection (and any pause buffer), so the line
     * is free for another device. A quick overlay (a voice search, a system message) doesn't
     * drop the channel; going to the launcher does. Scheduled recordings carry on.
     */
    fun onAppBackground() {
        val p = _player ?: return
        if (p.playWhenReady) {
            pausedForBackground = true
            p.pause()
        }
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(5_000)
            disconnectForBackground()
        }
    }

    private fun disconnectForBackground() {
        val p = _player ?: return
        val ch = currentChannel ?: return
        // A recording on this device: nothing to let go of.
        if (_recordingPlayback.value != null) return
        if (resumeAfterBackground != null) return
        val wasPlaying = pausedForBackground
        val url = currentUrl
        val title = _state.value.catchupTitle
        val start = archiveStartWallMs
        val pos = p.currentPosition
        resumeAfterBackground = {
            if (title != null) {
                // Catch-up: the same replay, back where you were.
                play(ch, url, title, catchupStartMs = start)
                scope.launch {
                    var waited = 0
                    while (waited < 15_000 && _player?.playbackState != Player.STATE_READY) { delay(200); waited += 200 }
                    _player?.let { pl -> if (pos > 0) pl.seekTo(pos); if (!wasPlaying) pl.pause() }
                }
            } else {
                currentUrl = null
                play(ch, overrideUrl = url.takeIf { it != ch.url })
            }
        }
        stopShift()
        stopReplayCache()
        reconnectJob?.cancel()
        stallJob?.cancel()
        stallJob = null
        p.stop()
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("App in the background: ${ch.name} stopped, so the line is free for another device")
    }

    /** Back in the app: carry on, jumping to the live edge for live channels. */
    fun onAppForeground() {
        backgroundJob?.cancel()
        backgroundJob = null
        resumeAfterBackground?.let { r ->
            resumeAfterBackground = null
            pausedForBackground = false
            r()
            return
        }
        if (!pausedForBackground) return
        pausedForBackground = false
        val p = _player ?: return
        if (_state.value.catchupTitle == null && p.isCurrentMediaItemLive) p.seekToDefaultPosition()
        p.play()
    }

    fun togglePause() {
        val p = _player ?: return
        when {
            _shiftStartedAt.value != null -> if (p.isPlaying) p.pause() else resumeShift()
            _recordingPlayback.value != null -> if (p.isPlaying) p.pause() else p.play()
            p.isPlaying && _state.value.catchupTitle == null -> pauseLive()
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun seekBy(ms: Long) {
        val p = _player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
    }

    /** Captions are showing (a text track is selected and text isn't switched off). */
    fun captionsOn(): Boolean {
        val p = _player ?: return false
        if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) return false
        return tracksOf(C.TRACK_TYPE_TEXT).any { it.selected }
    }

    /** Settings: captions on automatically when a channel has them. */
    var captionsByDefault: Boolean = false
    /** Captions were already switched on (or you chose) for the channel now playing. */
    private var captionsHandled = false

    /** One press: captions off if on; otherwise on, with the first available track. */
    fun toggleCaptions(): Boolean {
        captionsHandled = true // your choice for this channel stands
        captionWatch?.cancel()
        return if (captionsOn()) { selectTrack(C.TRACK_TYPE_TEXT, null); false }
        else {
            val pick = subtitleTracks().firstOrNull() ?: return false
            selectTrack(C.TRACK_TYPE_TEXT, pick)
            if (pick.kind != null) watchCaptions()
            true
        }
    }

    /** Picked in the Subtitles menu: that track stays, no switching to the other copy. */
    fun pickSubtitle(option: LiveTrackOption?) {
        captionsHandled = true
        captionWatch?.cancel()
        selectTrack(C.TRACK_TYPE_TEXT, option)
    }

    private var captionWatch: Job? = null

    /**
     * Captions switched on: if the copy picked shows nothing for 10 s while the other copy of the
     * same captions (the old kind or the digital kind) is carrying text, switch to that one.
     */
    private fun watchCaptions() {
        captionWatch?.cancel()
        captionWatch = scope.launch {
            repeat(12) { step ->
                delay(5_000)
                if (!captionsOn()) return@launch
                val all = tracksOf(C.TRACK_TYPE_TEXT)
                val kind = all.firstOrNull { it.selected }?.kind ?: return@launch
                val found = LiveCaptions.found.value
                if (kind in found) return@launch // working
                if (step < 1) return@repeat
                val other = all.firstOrNull {
                    it.kind != null && it.kind != kind &&
                        it.kind in setOf(LiveCaptions.CC1, LiveCaptions.DIGITAL) && it.kind in found
                }
                if (other != null) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Captions: nothing on ${kind.uppercase()}, switched to ${other.kind?.uppercase()}")
                    selectTrack(C.TRACK_TYPE_TEXT, other)
                    return@launch
                }
            }
        }
    }

    /**
     * The closed-captions track to offer first: CC1, or the digital copy when only that one has
     * text. Null when the channel has no captions inside the video.
     */
    private fun primaryCaption(all: List<LiveTrackOption>): LiveTrackOption? {
        val cea = all.filter { it.kind != null }
        if (cea.isEmpty()) return null
        val found = LiveCaptions.found.value
        return cea.firstOrNull { it.kind == LiveCaptions.CC1 && (found.isEmpty() || LiveCaptions.CC1 in found) }
            ?: cea.firstOrNull { it.kind == LiveCaptions.DIGITAL && LiveCaptions.DIGITAL in found }
            ?: cea.firstOrNull { it.kind in found }
            ?: cea.firstOrNull { it.kind == LiveCaptions.CC1 }
            ?: cea.first()
    }

    /** Other caption tracks this channel really carries (the CC menu's "More caption tracks"). */
    fun moreCaptionTracks(): List<LiveTrackOption> {
        val all = tracksOf(C.TRACK_TYPE_TEXT)
        val primary = primaryCaption(all)
        val found = LiveCaptions.found.value
        return all.filter { it.kind != null && it != primary && (it.kind in found || it.selected) }
    }

    /** For an empty Subtitles menu: why. */
    fun captionsNote(): String? = when {
        subtitleTracks().isNotEmpty() || moreCaptionTracks().isNotEmpty() -> null
        else -> "No captions on this channel"
    }

    /** Stream Info: which captions the channel carries. */
    fun captionsInfo(): String {
        val found = LiveCaptions.found.value
        if (found.isNotEmpty()) return LiveCaptions.summary(found)
        val subs = tracksOf(C.TRACK_TYPE_TEXT).filter { it.kind == null }
        if (subs.isNotEmpty()) return subs.joinToString(", ") { it.label }
        return when {
            LiveCaptions.noneFound() -> "None found in this stream"
            LiveCaptions.watching() -> "Checking…"
            tracksOf(C.TRACK_TYPE_TEXT).isEmpty() -> "None"
            else -> "Not checked for this stream type"
        }
    }

    /**
     * The sound of what's playing for the info bar badge: the kind when it's surround from Dolby
     * or DTS, and the channels ("DD+ 5.1", "DOLBY 5.1", "ATMOS", "DTS 5.1", "5.1", "STEREO").
     */
    fun audioBadge(): String? {
        val f = _player?.audioFormat ?: return null
        val chans = when (val c = f.channelCount) {
            1 -> "MONO"
            2 -> "STEREO"
            6 -> "5.1"
            8 -> "7.1"
            in 3..16 -> "${c}CH"
            else -> null
        }
        val mime = f.sampleMimeType.orEmpty()
        val codecs = f.codecs.orEmpty().lowercase()
        val kind = when {
            mime == MimeTypes.AUDIO_E_AC3_JOC || codecs.contains("joc") -> return "ATMOS"
            mime == MimeTypes.AUDIO_E_AC3 -> "DD+"
            mime == MimeTypes.AUDIO_AC3 -> "DOLBY"
            mime == MimeTypes.AUDIO_AC4 -> "AC-4"
            mime.startsWith("audio/vnd.dts") -> "DTS"
            else -> null
        }
        return when {
            kind != null && chans != null && chans != "STEREO" && chans != "MONO" -> "$kind $chans"
            kind != null && chans == null -> kind
            else -> chans
        }
    }

    /** The audio track playing, short ("English 5.1"), for the panel. */
    fun audioLabel(): String = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label?.take(18) ?: "Audio"

    fun audioTracks(): List<LiveTrackOption> = tracksOf(C.TRACK_TYPE_AUDIO)

    /** Saved audio choices: channel key -> "language|label|channels" (kept up to date by Live TV). */
    var channelAudio: Map<String, String> = emptyMap()
    /** Called when you pick an audio track, to remember it for that channel. */
    var onAudioChosen: ((channelKey: String, audio: String) -> Unit)? = null
    private var audioHandled = false
    private var audioPrefPending = true

    /** Picked in the Audio menu: plays it, and it's used every time this channel plays. */
    fun pickAudio(option: LiveTrackOption) {
        audioHandled = true
        selectTrack(C.TRACK_TYPE_AUDIO, option)
        val key = currentChannel?.key ?: return
        // Its place in the list too: streams often have several tracks with the same name and no
        // language ("AAC 2ch" twice), and the name alone picked the first of them every time.
        val position = tracksOf(C.TRACK_TYPE_AUDIO).indexOfFirst { it.groupIndex == option.groupIndex && it.trackIndex == option.trackIndex }
        val saved = listOf(option.language.orEmpty(), option.label, option.channels.toString(), position.toString()).joinToString("|")
        channelAudio = channelAudio + (key to saved)
        _player?.let { p ->
            // Later streams on this channel (catch-up, a reconnect) start in this language too.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(option.language)
                .build()
        }
        onAudioChosen?.invoke(key, saved)
    }

    /**
     * The saved audio choice, once this channel's tracks are known. The language is already
     * asked for; this also picks the same track among several in that language (stereo or 5.1,
     * main or audio description), by its label and number of channels.
     */
    private fun applySavedAudio() {
        val key = currentChannel?.key ?: return
        val options = tracksOf(C.TRACK_TYPE_AUDIO)
        if (options.isEmpty()) return
        // Checked every time the tracks change, not just once per channel: a reconnect or a
        // catch-up brings new tracks, and the player went back to the stream's default audio.
        audioHandled = true
        val parts = channelAudio[key]?.split('|') ?: return
        val lang = parts.getOrNull(0).orEmpty()
        val label = parts.getOrNull(1).orEmpty()
        val chans = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val position = parts.getOrNull(3)?.toIntOrNull() ?: -1
        fun sameLang(o: LiveTrackOption) = lang.isNotBlank() && o.language != null &&
            java.util.Locale.forLanguageTag(o.language).language == java.util.Locale.forLanguageTag(lang).language
        val sameName = options.filter { it.label == label }
        val pick = when {
            sameName.size == 1 -> sameName[0]
            // Several with that name: the one in the same place as before.
            sameName.size > 1 -> options.getOrNull(position)?.takeIf { it.label == label } ?: sameName[0]
            else -> options.firstOrNull { sameLang(it) && it.channels == chans }
                ?: options.firstOrNull { sameLang(it) }
                ?: options.getOrNull(position)?.takeIf { lang.isBlank() && (chans == 0 || it.channels == chans) }
        } ?: return
        if (!pick.selected) selectTrack(C.TRACK_TYPE_AUDIO, pick)
    }
    /**
     * The Subtitles menu: ordinary subtitle tracks, plus one closed-captions entry (the others
     * are under "More caption tracks"). No captions entry once 20 s of watching found none.
     */
    fun subtitleTracks(): List<LiveTrackOption> {
        val all = tracksOf(C.TRACK_TYPE_TEXT)
        val primary = primaryCaption(all)
        val showPrimary = primary != null && (!LiveCaptions.noneFound() || primary.selected)
        return all.filter { it.kind == null || (showPrimary && it == primary) }
    }

    fun selectTrack(type: Int, option: LiveTrackOption?) {
        val p = _player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun tracksOf(type: Int): List<LiveTrackOption> {
        val p = _player ?: return emptyList()
        val out = mutableListOf<LiveTrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, group: Tracks.Group ->
            if (group.type != type) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val kind = LiveCaptions.kindOf(f)
                val captions = kind?.let { LiveCaptions.label(it) }
                val parts = listOfNotNull(
                    captions ?: f.label,
                    f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } },
                    f.sampleMimeType?.takeIf { captions == null }?.substringAfter('/')?.uppercase(),
                    if (f.channelCount > 0) "${f.channelCount}ch" else null
                )
                out += LiveTrackOption(
                    gi, ti, parts.distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }, group.isTrackSelected(ti), kind,
                    language = f.language, channels = f.channelCount.coerceAtLeast(0)
                )
            }
        }
        return out
    }

    /** How much the player may download ahead, in bytes, from how much memory the app gets. */
    private fun playerBufferBytes(): Int {
        val appMemory = Runtime.getRuntime().maxMemory().takeIf { it in 1 until Long.MAX_VALUE } ?: (512L shl 20)
        return (appMemory * 3 / 10).coerceIn(32L shl 20, 160L shl 20).toInt()
    }

    private fun buildPlayer(): ExoPlayer {
        val passthrough = audioPassthrough
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                if (passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                // Pretend the output only takes plain PCM: Dolby / DTS gets decoded here.
                @Suppress("DEPRECATION")
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        // Buffer size setting: how much is downloaded ahead. Bigger rides out a shaky connection or
        // a slow provider; smaller starts channels a little faster.
        val (minMs, maxMs, startMs, rebufferMs) = when (bufferSize) {
            "small" -> listOf(10_000, 30_000, 1_000, 2_000)
            "large" -> listOf(40_000, 90_000, 3_000, 6_000)
            "xlarge" -> listOf(60_000, 150_000, 5_000, 10_000)
            else -> listOf(25_000, 60_000, 2_000, 4_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            // The download-ahead lives in the app's memory. The player's own cap (about 130 MB
            // for video) is more than half of what a cheap stick gives an app, which made the
            // whole app stutter as memory ran short. Capped to about 30% of the app's memory:
            // the same on boxes with plenty, smaller on low-memory ones. The minimum buffer
            // time is always kept, so channels are just as steady.
            .setTargetBufferBytes(playerBufferBytes())
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            // Take part in Android's audio focus like other media apps: when something else starts
            // playing sound (another app, a launcher's video wallpaper with sound…), Live TV pauses.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
            .apply {
                playWhenReady = true
                addListener(listener)
                // Choppy video ("slide show"): how many frames the box couldn't show in time,
                // in the start-up report, so we can see when it starts and what was going on.
                setVideoFrameMetadataListener(frameListener)
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    private var droppedTotal = 0L
                    private var lastReportAt = 0L
                    override fun onDroppedVideoFrames(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        droppedTotal += droppedFrames
                        val now = System.currentTimeMillis()
                        if (droppedFrames >= 10 && now - lastReportAt > 30_000) {
                            lastReportAt = now
                            val rt = Runtime.getRuntime()
                            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                                "Video dropped $droppedFrames frames in ${elapsedMs / 1000}s ($droppedTotal so far; app memory ${usedMb} of ${rt.maxMemory() / (1024 * 1024)} MB)"
                            )
                        }
                    }
                })
            }
    }

    // ---------------------------------------------------------------- picture watchdog

    /**
     * Moving the picture between the guide preview and full screen gives the player a new
     * screen to draw on. On some TV chips the video then freezes (sound keeps going) or never
     * starts. After each move, if no picture appears within a couple of seconds, quietly
     * restart the stream on the new screen (what "Reload stream" did by hand).
     */
    @Volatile private var awaitingFrame = false
    private var frameJob: Job? = null

    fun onSurfaceAttached() {
        val p = _player ?: return
        awaitingFrame = true
        if (com.nuvio.tv.BuildConfig.DEBUG) Log.d(TAG, "Video surface attached")
        frameJob?.cancel()
        frameJob = scope.launch {
            // Sound playing but no picture: frozen video. A channel that's simply still loading
            // gets longer before we step in (it may just be slow to start).
            delay(FRAME_TIMEOUT_MS)
            if (!stuck(p, requireReady = true)) {
                delay(LOADING_AFTER_MOVE_MS - FRAME_TIMEOUT_MS)
                if (!stuck(p, requireReady = false)) return@launch
            }
            Log.w(TAG, "No picture after moving the video; restarting the stream")
            val archive = _state.value.catchupTitle != null
            val pos = p.currentPosition
            p.stop()
            archiveStartPending = false
            p.prepare()
            if (archive && pos > 0) p.seekTo(pos)
            p.playWhenReady = true
        }
    }

    private fun stuck(p: ExoPlayer, requireReady: Boolean): Boolean {
        if (!awaitingFrame || !p.playWhenReady || currentChannel == null || _state.value.error != null) return false
        return if (requireReady) p.playbackState == Player.STATE_READY && p.isPlaying
        else p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_READY
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.value = _state.value.copy(userPaused = !playWhenReady)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            applySavedAudio()
            // "Captions on by default": once the channel's caption tracks are known, switch them
            // on (once per channel, and not if you've turned them off yourself).
            if (!captionsByDefault || captionsHandled) return
            if (tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }) return
            captionsHandled = true
            if (!captionsOn()) subtitleTracks().firstOrNull()?.let {
                selectTrack(C.TRACK_TYPE_TEXT, it)
                if (it.kind != null) watchCaptions()
            }
        }

        override fun onRenderedFirstFrame() {
            if (com.nuvio.tv.BuildConfig.DEBUG) Log.d(TAG, "First video frame rendered")
            awaitingFrame = false
            // Playing fine for a while: no longer counts as "the app closed while this was starting".
            currentChannel?.key?.let { key ->
                scope.launch {
                    kotlinx.coroutines.delay(15_000)
                    if (currentChannel?.key == key) clearStarting()
                }
            }
            // HLS catch-up failed and the TS link plays: remember it for this provider.
            hlsCatchupFailedOn?.let { source ->
                hlsCatchupFailedOn = null
                onCatchupHlsUnsupported?.invoke(source)
            }
            // HLS catch-up playing: this provider does offer it.
            val url = currentUrl?.substringBefore('?')
            if (_state.value.catchupTitle != null && url != null && url.contains("/timeshift/") && url.endsWith(".m3u8", true)) {
                currentChannel?.sourceId?.let { onCatchupHlsWorks?.invoke(it) }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (shiftStartPending && !timeline.isEmpty) {
                val pl = _player
                if (pl != null) {
                    val w = timeline.getWindow(pl.currentMediaItemIndex.coerceIn(0, timeline.windowCount - 1), androidx.media3.common.Timeline.Window())
                    if (!w.isPlaceholder) {
                        shiftStartPending = false
                        pl.seekTo(0L)
                        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: timeline in (buffer holds ${if (w.durationMs != C.TIME_UNSET) w.durationMs / 1000 else -1} s); started at the pause point (was at ${pl.currentPosition / 1000} s)")
                    }
                }
            }
            if (!archiveStartPending || timeline.isEmpty) return
            val p = _player ?: return
            val window = timeline.getWindow(p.currentMediaItemIndex.coerceIn(0, timeline.windowCount - 1), androidx.media3.common.Timeline.Window())
            // The first timeline is only a stand-in until the stream answers. A seek to 0 made
            // then is ignored by the player (it reads 0 as "no seek" and starts live streams at
            // the live edge), which is why "Watch from start" still began at now on some
            // providers. Wait for the real one.
            if (window.isPlaceholder) return
            archiveStartPending = false
            if (_state.value.catchupTitle == null) return
            if (!(window.isDynamic || window.isLive())) return
            // A replay sent as a growing live stream: the player would start it at the live
            // edge. When the stream carries its own clock and reaches back before the show
            // (the provider sent its live stream with a long rewind window instead of just the
            // show), go to the show's start by the clock; otherwise to the start of the window.
            val startWall = archiveStartWallMs
            val windowStart = window.windowStartTimeMs
            if (startWall != null && windowStart != C.TIME_UNSET && startWall - windowStart > 120_000) {
                val length = window.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                val target = (startWall - windowStart).coerceAtMost(length)
                archiveWallMapped = true
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up came as the live stream with rewind; jumping back ${target / 60_000} min into it to the show's start")
                p.seekTo(target)
            } else {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up came as a live stream; starting from the beginning")
                p.seekTo(0L)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (com.nuvio.tv.BuildConfig.DEBUG) Log.d(TAG, "Playback state changed to $playbackState")
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                error = if (playbackState == Player.STATE_READY) null else _state.value.error,
                reconnectAttempt = if (playbackState == Player.STATE_READY) 0 else _state.value.reconnectAttempt
            )
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && timeshift.isRecording) {
                // Caught up with the end of what's recorded while it's still recording: carry on
                // from here (the newest pieces come in), rather than dropping back to live.
                reloadShiftAt(_player?.currentPosition ?: 0L, "reached the end of the buffer")
                return
            }
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && !alwaysBuffer) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: playback ended (recording stopped); back to live")
                goLive()
                return
            }
            if (playbackState == Player.STATE_ENDED && _shiftStartedAt.value != null && alwaysBuffer) {
                // The buffer stopped coming in (the stream dropped): start over, or play directly
                // if it fails straight away.
                if (System.currentTimeMillis() - bufferStartedAt < 20_000) bufferFailed() else goLiveFresh()
                return
            }
            if (playbackState == Player.STATE_ENDED && _recordingPlayback.value != null) {
                // The recording you were watching from finished: on with the channel live.
                recordingPlaybackEnded()
                return
            }
            if (playbackState == Player.STATE_ENDED && _state.value.catchupTitle == null) {
                // A live stream should never end; treat it as a dropped connection.
                scheduleReconnect("Stream ended")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.value = _state.value.copy(
                videoWidth = videoSize.width, videoHeight = videoSize.height,
                pixelRatio = videoSize.pixelWidthHeightRatio
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // A recording that failed: back to live rather than reconnecting to nowhere.
            if (_shiftStartedAt.value != null && timeshift.isRecording && shiftReloads < 5) {
                // A hiccup reading the buffer (it's still recording): pick up where it was.
                Log.w(TAG, "Buffer playback error; reloading", error)
                reloadShiftAt(_player?.currentPosition ?: 0L, error.errorCodeName)
                return
            }
            if (_shiftStartedAt.value != null && alwaysBuffer) {
                Log.w(TAG, "Buffer playback failed", error)
                if (System.currentTimeMillis() - bufferStartedAt < 20_000) bufferFailed() else goLiveFresh()
                return
            }
            if (_shiftStartedAt.value != null) {
                Log.w(TAG, "Recording playback failed", error)
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: playback error ${error.errorCodeName} (recording=${timeshift.isRecording}, reloads=$shiftReloads); back to live")
                goLive(); return
            }
            if (_recordingPlayback.value != null) { Log.w(TAG, "Playing from the recording failed", error); recordingPlaybackEnded(); return }
            // The saved catch-up couldn't be played: play the replay straight from the provider.
            if (replayLocal) {
                val ch0 = currentChannel
                val url0 = currentUrl
                stopReplayCache()
                if (ch0 != null && url0 != null) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: the saved copy didn't play (${error.errorCodeName}); playing it directly")
                    archiveStartPending = true
                    _player?.let { pl ->
                        pl.setMediaSource(buildMediaSource(url0, ch0.headers, isLive = false))
                        pl.prepare()
                        pl.playWhenReady = true
                    }
                    return
                }
            }
            val p = _player
            // The provider doesn't offer this as HLS: switch to the TS link once.
            val ch = currentChannel
            val fb = fallbackUrls.removeFirstOrNull()
            if (fb != null && ch != null && p != null) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up failed (${error.errorCodeName}); trying ${redact(fb)}")
                val wasHls = currentUrl?.substringBefore('?')?.let { it.contains("/timeshift/") && it.endsWith(".m3u8", true) } == true
                val toTs = fb.substringBefore('?').let { it.contains("/timeshift/") && it.endsWith(".ts", true) }
                hlsCatchupFailedOn = if (wasHls && toTs) ch.sourceId else null
                currentUrl = fb
                archiveStartPending = _state.value.catchupTitle != null
                p.setMediaSource(buildMediaSource(fb, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                p.playWhenReady = true
                return
            }
            // "Container unsupported": the link didn't say what kind of stream it is and it was
            // read as a plain video file. Try it as HLS, then as DASH, before giving up.
            if (ch != null && p != null && isUnrecognizedFormat(error) && formatGuess < FORMAT_GUESSES.size) {
                forcedMime = FORMAT_GUESSES[formatGuess++]
                Log.w(TAG, "Unrecognized stream format for ${ch.name}; trying $forcedMime")
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    (if (_state.value.catchupTitle != null) "Catch-up" else "Channel ${ch.name}") +
                        " failed (${error.errorCodeName}); trying it as ${forcedMime?.substringAfter('/')}"
                )
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
                p.prepare()
                p.playWhenReady = true
                return
            }
            // The provider's HLS playlist can't be read ("manifest malformed"): Xtream channels
            // also come as plain TS, which TiviMate-style players use. Switch once, and keep using
            // TS for this channel until the app restarts.
            if (ch != null && p != null && _state.value.catchupTitle == null && ch.key !in tsInstead && isMalformedManifest(error)) {
                val ts = tsLinkFor(currentUrl ?: ch.url)?.takeIf { it != (currentUrl ?: ch.url) }
                if (ts != null) {
                    tsInstead += ch.key
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                        "Channel ${ch.name}: its HLS playlist couldn't be read (${error.errorCodeName}); playing it as TS instead"
                    )
                    currentUrl = ts
                    forcedMime = null
                    p.setMediaSource(buildMediaSource(ts, ch.headers, isLive = true))
                    p.prepare()
                    p.playWhenReady = true
                    return
                }
            }
            // Still "malformed" (no TS link to switch to, or the TS link said the same): the
            // provider is often sending plain TS on a link that looks like HLS. Read the same
            // link as TS once before reconnecting over and over.
            if (ch != null && p != null && _state.value.catchupTitle == null && ch.key !in plainTs && isMalformedManifest(error)) {
                plainTs += ch.key
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                    "Channel ${ch.name}: still no readable HLS playlist (${error.errorCodeName}); reading the stream as plain TS"
                )
                forcedMime = MimeTypes.VIDEO_MP2T
                val url = currentUrl ?: ch.url
                p.setMediaSource(buildMediaSource(url, ch.headers, isLive = true))
                p.prepare()
                p.playWhenReady = true
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (_state.value.catchupTitle != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Catch-up failed on ${ch?.name} (${error.errorCodeName}); no other links left to try"
            )
            scheduleReconnect(describe(error), error)
        }
    }

    /** A provider's HLS catch-up just failed and its TS link is being tried (playlist id). */
    @Volatile private var hlsCatchupFailedOn: String? = null
    /** Called with the playlist id when that provider's catch-up works as TS but not HLS. */
    var onCatchupHlsUnsupported: ((String) -> Unit)? = null
    /** Called with the playlist id when that provider's catch-up plays as HLS. */
    var onCatchupHlsWorks: ((String) -> Unit)? = null

    /** Live channels whose HLS playlist couldn't be read: played as TS instead (until the app restarts). */
    private val tsInstead: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    /** Live channels whose link looks like HLS but sends plain TS: read as TS (until the app restarts). */
    private val plainTs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * The TS version of an Xtream live link: ".../123.m3u8" -> ".../123.ts", and a link with no
     * ending at all (".../user/pass/123", which the server answers as HLS) -> ".../123.ts".
     * Null if it isn't one.
     */
    private fun tsLinkFor(url: String): String? {
        val base = url.substringBefore('?')
        if (base.endsWith(".m3u8", ignoreCase = true) && XTREAM_HLS.containsMatchIn(base))
            return base.dropLast(5) + ".ts" + url.substring(base.length)
        if (XTREAM_BARE.containsMatchIn(base)) return base + ".ts" + url.substring(base.length)
        return null
    }

    private fun isMalformedManifest(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return true
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.common.ParserException && c.message?.contains("malformed", true) == true) return true
            c = c.cause
        }
        return false
    }

    // ---- start-up guard: a channel that made the app close while it was starting isn't
    // started by itself again next time (it used to close the app again, over and over).

    private val startGuard by lazy { context.getSharedPreferences("livetv_start_guard", Context.MODE_PRIVATE) }

    /** The channel that was starting when the app last closed unexpectedly (read once, at start-up). */
    val crashedWhileStarting: String? by lazy {
        val key = startGuard.getString("starting", null)
        val at = startGuard.getLong("at", 0L)
        startGuard.edit().clear().apply()
        key?.takeIf { System.currentTimeMillis() - at < 6 * 60 * 60 * 1000L }
    }

    private fun markStarting(key: String) {
        crashedWhileStarting // read the last one before it's replaced
        startGuard.edit().putString("starting", key).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun clearStarting() {
        if (startGuard.contains("starting")) startGuard.edit().remove("starting").remove("at").apply()
    }

    /** Format tried for a link that doesn't say what it is (after "container unsupported"). */
    @Volatile private var forcedMime: String? = null
    @Volatile private var formatGuess = 0

    private fun isUnrecognizedFormat(error: PlaybackException): Boolean {
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) return true
            if (c is androidx.media3.common.ParserException && c.message?.contains("ontainer", true) == true) return true
            c = c.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    }

    /** A link for the start-up report, with the username and password hidden. */
    private fun redact(url: String): String =
        url.replace(Regex("""(/(?:timeshift|live|movie|series)/)[^/]+/[^/]+/"""), "$1***/***/")
            .replace(Regex("""(username|password)=[^&]*"""), "$1=***")
            // Xtream links without "/live": http://host:port/user/pass/123.ts
            .replace(Regex("""^(https?://[^/]+)/(?!\*\*\*)[^/]+/[^/]+/(\d+(?:\.[A-Za-z0-9]+)?(?:\?.*)?)$"""), "$1/***/***/$2")

    /**
     * A live channel failed twice in a row (or never started): Live TV may switch to a backup
     * channel. True when it did, and this channel isn't tried again.
     */
    var onLiveFailure: ((channelKey: String) -> Boolean)? = null
    /** Times the channel playing sat loading with nothing coming in. */
    private var neverStartedCount = 0

    private fun scheduleReconnect(reason: String, error: PlaybackException? = null) {
        val attempt = _state.value.reconnectAttempt + 1
        // Live channels that won't play: what failed and why, for the Start-Up Report (only
        // catch-up failures were written there, so "it won't play" reports had nothing to go on).
        if (_state.value.catchupTitle == null) {
            val detail = error?.let { e ->
                generateSequence(e.cause) { it.cause }.take(4)
                    .map { c -> c.javaClass.simpleName + (c.message?.take(80)?.let { m -> ": $m" } ?: "") }
                    .joinToString(" ← ")
            }.orEmpty()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Channel ${currentChannel?.name} failed (try $attempt): $reason" +
                    (error?.let { " (${it.errorCodeName})" } ?: "") +
                    (if (detail.isNotBlank()) " [$detail]" else "") +
                    (currentUrl?.let { " · ${redact(it)}" } ?: "")
            )
        }
        _state.value = _state.value.copy(error = reason, isBuffering = false, reconnectAttempt = attempt)
        if (_state.value.catchupTitle == null && attempt == 2) {
            val key = currentChannel?.key
            if (key != null && onLiveFailure?.invoke(key) == true) return
        }
        if (!autoReconnect || attempt > MAX_RECONNECTS) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay((attempt * 2_000L).coerceAtMost(10_000L))
            val ch = currentChannel ?: return@launch
            val url = currentUrl ?: return@launch
            val p = _player ?: return@launch
            p.setMediaSource(buildMediaSource(url, ch.headers, isLive = _state.value.catchupTitle == null))
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun buildMediaSource(url: String, headers: Map<String, String>, isLive: Boolean): androidx.media3.exoplayer.source.MediaSource {
        // Protected (DRM) streams, e.g. MPEG-DASH with a ClearKey or Widevine license.
        currentChannel?.drm?.let { drm -> buildDrmMediaSource(url, headers, isLive, drm)?.let { return it } }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val item = buildMediaItem(url, isLive)
        // Closed captions: TV channels usually carry them inside the video (CEA-608/708) without
        // announcing them, so the player never offered them and Subtitles only showed "Off".
        // Look for them anyway, the way TV apps do.
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http)
                .setExtractorFactory(
                    LiveCaptions.Hls(
                        androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                            /* exposeCea608WhenMissingDeclarations = */ true
                        )
                    )
                )
                .createMediaSource(item)
        }
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
            )
            // CC1–CC4 and the digital (708) captions; the menu only offers those with text.
            .setTsSubtitleFormats(LiveCaptions.formats)
        return DefaultMediaSourceFactory(http, LiveCaptions.Extractors(extractors)).createMediaSource(item)
    }

    /**
     * DRM playback, as TiviMate and Kodi do it from "#KODIPROP" playlist lines:
     *  - Widevine / PlayReady: the license server URL (with any headers the playlist gives).
     *  - ClearKey: keys given in the playlist ("kid:key" pairs or a JSON key set), or a ClearKey
     *    license URL. Widevine needs a device that supports it (most Android TV devices do).
     */
    private fun buildDrmMediaSource(
        url: String,
        headers: Map<String, String>,
        isLive: Boolean,
        drm: com.nuvio.tv.livetv.model.DrmInfo
    ): androidx.media3.exoplayer.source.MediaSource? = runCatching {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val itemBuilder = buildMediaItem(url, isLive).buildUpon()
        if (drm.manifestType == "mpd" || url.lowercase().contains(".mpd")) itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        if (drm.manifestType == "hls") itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val uuid = when (drm.scheme) {
            "widevine" -> androidx.media3.common.C.WIDEVINE_UUID
            "playready" -> androidx.media3.common.C.PLAYREADY_UUID
            else -> androidx.media3.common.C.CLEARKEY_UUID
        }
        val factory = DefaultMediaSourceFactory(http)
        val license = drm.license.trim()
        if (drm.scheme == "clearkey" && !license.startsWith("http", ignoreCase = true)) {
            // Keys in the playlist: answer license requests locally with them.
            val keySet = clearKeyJson(license) ?: return@runCatching null
            factory.setDrmSessionManagerProvider {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(androidx.media3.exoplayer.drm.LocalMediaDrmCallback(keySet.toByteArray(Charsets.UTF_8)))
            }
            itemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.CLEARKEY_UUID).build())
        } else {
            // License requests go out with the same User-Agent / Referer as the stream (like
            // TiviMate and Kodi), plus any license headers from the playlist. Without them many
            // license servers refuse the request.
            val licenseHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) -> if (k.equals("User-Agent", true) || k.equals("Referer", true) || k.equals("Origin", true)) licenseHeaders[k] = v }
            licenseHeaders.putAll(drm.licenseHeaders)
            factory.setDrmSessionManagerProvider(
                androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }
            )
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(license)
                    .setLicenseRequestHeaders(licenseHeaders)
                    .setMultiSession(true)
                    .build()
            )
        }
        factory.createMediaSource(itemBuilder.build())
    }.onFailure { Log.w(TAG, "Couldn't set up DRM for this stream", it) }.getOrNull()

    /**
     * A ClearKey key set (JSON) from what playlists give: "kid:key" pairs (hex or base64,
     * comma-separated), or a JSON key set as is.
     */
    private fun clearKeyJson(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("{")) return t
        fun b64url(v: String): String {
            val s = v.trim()
            val bytes = if (Regex("^[0-9a-fA-F]+$").matches(s) && s.length % 2 == 0) {
                ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else {
                runCatching { android.util.Base64.decode(s.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT) }
                    .getOrElse { return "" }
            }
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }
        val keys = t.split(',').mapNotNull { pair ->
            val kid = pair.substringBefore(':').trim()
            val key = pair.substringAfter(':', "").trim()
            if (kid.isEmpty() || key.isEmpty()) null
            else org.json.JSONObject().put("kty", "oct").put("kid", b64url(kid)).put("k", b64url(key))
        }
        if (keys.isEmpty()) return null
        return org.json.JSONObject().put("keys", org.json.JSONArray(keys)).put("type", "temporary").toString()
    }

    private fun buildMediaItem(url: String, isLive: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val lower = url.lowercase().substringBefore('?')
        when {
            forcedMime != null -> builder.setMimeType(forcedMime)
            lower.endsWith(".m3u8") || lower.contains("/hls/") || lower.contains("m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (isLive) {
            // How far behind "live" to play. Playing very close to live (it was 8 seconds) left
            // almost no room for a slow segment, so some providers kept buffering; now it
            // follows the stream's own recommendation, with more room for bigger buffers.
            val offset = when (bufferSize) {
                "small" -> 10_000L
                "large" -> 30_000L
                "xlarge" -> 45_000L
                else -> null
            }
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .apply { if (offset != null) setTargetOffsetMs(offset) }
                    // No speeding up / slowing down to chase the live edge (can cause stutter).
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
        }
        return builder.build()
    }

    /**
     * Lets go of the player (and its hold on the audio output) right away. Called when Nuvio's
     * own player opens, so the two never hold the audio at the same time.
     */
    fun releaseNow() {
        portalLinkJob?.cancel()
        portalLinkJob = null
        if (_player != null || currentChannel != null) release()
    }

    private fun release() {
        portalLinkJob?.cancel()
        portalLinkJob = null
        backgroundJob?.cancel()
        backgroundJob = null
        resumeAfterBackground = null
        clearStarting()
        stopShift()
        reconnectJob?.cancel()
        stallJob?.cancel()
        stallJob = null
        _player?.removeListener(listener)
        _player?.release()
        _player = null
        currentChannel = null
        LiveWatching.sourceId = null
        currentUrl = null
        _state.value = LivePlaybackState()
    }

    companion object {
        private const val MAX_RECONNECTS = 8
        /** No progress for this long while it should be playing = stalled. */
        private const val STALL_MS = 15_000L
        private val FORMAT_GUESSES = listOf(MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD)
        /** Xtream live links: ".../live/user/pass/123.m3u8" or "host/user/pass/123.m3u8". */
        private val XTREAM_HLS = Regex("""/(?:live/)?[^/]+/[^/]+/\d+\.m3u8$""", RegexOption.IGNORE_CASE)
        /** An Xtream live link with no ending: ".../live/user/pass/123" or ".../user/pass/123". */
        private val XTREAM_BARE = Regex("""/(?:live/)?[^/]+/[^/]+/\d+$""")
        /** No picture this long after the video moved to a new screen = stuck. */
        private const val FRAME_TIMEOUT_MS = 2_500L
        /** Still loading this long after the video moved to a new screen = stuck. */
        private const val LOADING_AFTER_MOVE_MS = 7_000L
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        private const val TAG = "LiveTvPlayback"
    }
}

/**
 * The playlist Live TV's player is streaming from right now (null when nothing is playing). A
 * recording that drops checks this: many accounts allow one connection at a time, and watching
 * another channel from the same provider is the usual reason a recording gets cut off.
 */
object LiveWatching {
    /** Also tells the app a channel is on (Nuvio's idle slideshow stays away while one is). */
    val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    var sourceId: String?
        get() = active.value
        set(value) { active.value = value }
}
