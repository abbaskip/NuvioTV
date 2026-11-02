package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgChannelEntry
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.EpgSourceChannels
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport as LIVE_REPORT
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.PlaylistAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class LiveTvStatus(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val loadedOnce: Boolean = false,
    /** Busy, but nothing worth announcing (reading the saved channels at start-up). */
    val quiet: Boolean = false
)

@Singleton
class LiveTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val guideDb: EpgDatabase,
    private val stalker: StalkerPortalClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // A provider that trickles data forever would otherwise hold up every other update
        // (deleting a playlist, turning one off) until the app is restarted.
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val baseDir = File(context.filesDir, "livetv")
    private val playlistDir = File(baseDir, "playlists")
    private val epgDir = File(baseDir, "epg")

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /** Programs keyed by [LiveChannel.key], sorted by start. Offset is not applied here. */
    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Channel key -> the guide channel picked automatically (shown in Assign EPG). */
    private val _autoMatches = MutableStateFlow<Map<String, EpgAssignment>>(emptyMap())
    val autoMatches: StateFlow<Map<String, EpgAssignment>> = _autoMatches.asStateFlow()

    /** Every loaded EPG source with its channel list (for "Change EPG"). */
    private val _epgSources = MutableStateFlow<List<EpgSourceChannels>>(emptyList())
    val epgSources: StateFlow<List<EpgSourceChannels>> = _epgSources.asStateFlow()

    private val _status = MutableStateFlow(LiveTvStatus())
    val status: StateFlow<LiveTvStatus> = _status.asStateFlow()

    private var initialized = false
    /** The start-up update check has run (later checks may start). */
    @Volatile private var firstCheckDone = false

    /** Loads cached data immediately, then refreshes anything older than the configured interval. */
    fun ensureLoaded(updateCheckDelayMs: Long = 0L) {
        if (initialized) return
        initialized = true
        scope.launch {
            // Saved channels and guide first: reading them only uses the device's storage.
            loadFromCache()
            // A playlist running out within a week: say so (once per app start).
            runCatching {
                val names = prefs.currentPlaylists().associate { it.id to it.name }
                prefs.playlistAccounts.first().forEach { (id, a) -> names[id]?.let { warnIfExpiring(id, it, a) } }
            }
            // Groups hidden or shown since the guide was stored (and from now on).
            watchHidden()
            // Space left empty in the guide database by earlier versions or updates.
            scheduleCompact()
            // Checking for playlist / guide updates uses the network and a lot of processing,
            // so when this starts with the app it waits a little, out of Nuvio's way.
            if (updateCheckDelayMs > 0) delay(updateCheckDelayMs)
            refreshStale()
            firstCheckDone = true
            // Then keep checking while the app stays open. TV boxes keep the app running for
            // days (Home only sends it to the background), and the check used to happen only
            // when the app started: the guide ran out after a day or two and never refreshed
            // until the box restarted or someone pressed Update now.
            while (true) {
                delay(STALE_CHECK_MS)
                runCatching { refreshStale() }.onFailure { Log.w(TAG, "Update check failed", it) }
            }
        }
    }

    /** Opening Live TV: update anything past its update time (the playlist or guide interval). */
    fun refreshIfStale() {
        // Not before the start-up check (the saved channels may not be read yet).
        if (!firstCheckDone) return
        if (mutex.isLocked) return // an update is already running
        scope.launch { runCatching { refreshStale() } }
    }

    fun refreshAll(force: Boolean = true) {
        scope.launch { refreshInternal(forcePlaylists = force, forceEpg = force) }
    }

    /** After a restore from Google Drive: rebuild from the new sources, downloading what's missing. */
    /** After another TV's setup was applied: [changedPlaylists] get downloaded again straight away. */
    fun reloadAfterSync(changedPlaylists: Set<String> = emptySet()) {
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = changedPlaylists)
        }
    }

    fun refreshEpgOnly() {
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = true) }
    }

    // Automatic updates run at low priority, so the screen and video stay smooth meanwhile.
    private suspend fun refreshStale() = withContext(BackgroundWork.quiet) { refreshInternal(forcePlaylists = false, forceEpg = false) }

    // ---------------------------------------------------------------- sources CRUD

    suspend fun addPlaylist(name: String, url: String, userAgent: String = ""): String {
        val id = UUID.randomUUID().toString()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = userAgent.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun addStalker(name: String, portal: String, mac: String, user: String, pass: String, serial: String) {
        val source = PlaylistSource(UUID.randomUUID().toString(), name.ifBlank { "Stalker" }, "",
            stalkerPortal = portal.trim(), stalkerMac = mac.trim().uppercase(),
            stalkerUsername = user.trim(), stalkerPassword = pass, stalkerSerial = serial.trim())
        StalkerPortalClient.endpoint(source.stalkerPortal)
        prefs.updatePlaylists { it + source }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) }
    }

    suspend fun addXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        importVod: Boolean = true,
        importLive: Boolean = true
    ): String {
        val id = UUID.randomUUID().toString()
        // Accept a pasted full link: keep just the server, and take the login from it if needed.
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass,
                importVod = importVod,
                importLive = importLive
            )
        }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updatePlaylist(input: PlaylistSource) {
        val source = if (input.isXtream) {
            val fromLink = PlaylistSource.credentialsFromLink(input.xtreamServer)
            input.copy(
                xtreamServer = PlaylistSource.cleanXtreamServer(input.xtreamServer),
                xtreamUsername = input.xtreamUsername.trim().ifBlank { fromLink?.first.orEmpty() },
                xtreamPassword = input.xtreamPassword.trim().ifBlank { fromLink?.second.orEmpty() }
            )
        } else input
        val wasLive = prefs.currentPlaylists().firstOrNull { it.id == source.id }?.liveEnabled == true
        prefs.updatePlaylists { list -> list.map { if (it.id == source.id) source else it } }
        if (!source.liveEnabled) {
            // TV channels switched off (or never on, for an On Demand-only login): take them out
            // of the guide straight away and leave Live TV alone. Nothing to download for the
            // guide, so no "Loading Live TV" bubble; On Demand imports on its own.
            dropPlaylistNow(source.id)
            if (wasLive) scope.launch { rebuild() }
            return
        }
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id))
        }
    }

    suspend fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        File(playlistDir, "$id.m3u").delete()
        embeddedEpgUrls.remove(id)
        dropPlaylistNow(id)
        scope.launch { rebuild() }
    }

    /**
     * Takes a deleted or turned-off playlist's channels out of the guide straight away, without
     * waiting for the full rebuild (which can be queued behind a long guide download).
     */
    private fun dropPlaylistNow(id: String) {
        val remaining = _channels.value.filterNot { it.sourceId == id }
        if (remaining.size == _channels.value.size) return
        val keys = remaining.mapTo(HashSet()) { it.key }
        _channels.value = remaining
        _programs.value = _programs.value.filterKeys { it in keys }
    }

    suspend fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (!enabled) dropPlaylistNow(id)
        // Rebuild from the cached files first (turning a playlist on or off downloads nothing,
        // so the refresh alone wouldn't rebuild the channel list), then fetch anything missing.
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
    }

    suspend fun movePlaylist(id: String, delta: Int) {
        prefs.updatePlaylists { list ->
            val m = list.toMutableList()
            val i = m.indexOfFirst { it.id == id }
            if (i < 0) return@updatePlaylists list
            val item = m.removeAt(i)
            m.add((i + delta).coerceIn(0, m.size), item)
            m
        }
        scope.launch { rebuild() }
    }

    suspend fun addEpg(name: String, url: String): String {
        val id = UUID.randomUUID().toString()
        prefs.updateEpgSources { it + EpgSource(id = id, name = name.ifBlank { "EPG" }, url = url.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updateEpg(source: EpgSource) {
        prefs.updateEpgSources { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) }
    }

    suspend fun removeEpg(id: String) {
        prefs.updateEpgSources { list -> list.filterNot { it.id == id } }
        File(epgDir, "$id.xml").delete()
        scope.launch { rebuild() }
    }

    suspend fun setEpgEnabled(id: String, enabled: Boolean) {
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
    }

    // ---------------------------------------------------------------- core

    private suspend fun loadFromCache() {
        if (_epgLogos.value.isEmpty()) _epgLogos.value = withContext(Dispatchers.IO) { readEpgLogos() }
        mutex.withLock { rebuildLocked() }
    }

    // ---------------------------------------------------------------- logos from the guide

    private val epgLogosFile get() = File(baseDir, "epg_logos.json")
    private val _epgLogos = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Channel key -> the logo its matched guide channel has (playlists can prefer these). */
    val epgLogos: StateFlow<Map<String, String>> = _epgLogos.asStateFlow()

    private fun readEpgLogos(): Map<String, String> = runCatching {
        val f = epgLogosFile
        if (!f.exists()) return@runCatching emptyMap<String, String>()
        val o = org.json.JSONObject(f.readText())
        val out = HashMap<String, String>(o.length())
        o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        out
    }.getOrDefault(emptyMap())

    // ---- The guides' channel lists (Assign EPG), kept between app starts.
    private val sourceListsFile get() = File(baseDir, "epg_channel_lists.bin")

    /** Which guide files the saved lists came from (each file's size and contents stamp). */
    private fun sourceListsStamp(targets: List<EpgTarget>): String = targets.joinToString(",") { targetStamp(it) }

    private fun saveSourceLists(targets: List<EpgTarget>, lists: List<EpgSourceChannels>) {
        runCatching {
            val tmp = File(baseDir, sourceListsFile.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()))).use { out ->
                out.writeInt(SOURCE_LISTS_VERSION)
                out.writeUTF(sourceListsStamp(targets))
                out.writeInt(lists.size)
                lists.forEach { l ->
                    out.writeUTF(l.sourceId); out.writeUTF(l.name); out.writeUTF(l.label)
                    out.writeInt(l.channels.size)
                    l.channels.forEach { e ->
                        out.writeUTF(e.id)
                        out.writeInt(e.names.size)
                        e.names.forEach { out.writeUTF(it.take(500)) }
                        out.writeUTF(e.icon.orEmpty().take(2000))
                    }
                }
            }
            if (!tmp.renameTo(sourceListsFile)) { tmp.copyTo(sourceListsFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the guides' channel lists", it) }
    }

    /** Loads the saved lists if they still match the guide files; marks what's in use. */
    private suspend fun loadSourceLists(targets: List<EpgTarget>) {
        val f = sourceListsFile
        if (!f.exists()) {
            LIVE_REPORT.add("Assign EPG lists: none saved yet (read when Assign EPG is first opened)")
            return
        }
        val lists = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(f.inputStream()))).use { input ->
                if (input.readInt() != SOURCE_LISTS_VERSION) return@use null
                if (input.readUTF() != sourceListsStamp(targets)) return@use null
                val n = input.readInt()
                (0 until n).map {
                    val sourceId = input.readUTF(); val name = input.readUTF(); val label = input.readUTF()
                    val count = input.readInt()
                    val entries = ArrayList<EpgChannelEntry>(count)
                    repeat(count) {
                        val id = input.readUTF()
                        val names = (0 until input.readInt()).map { input.readUTF() }
                        val icon = input.readUTF().ifBlank { null }
                        entries += EpgChannelEntry(id, names, icon)
                    }
                    EpgSourceChannels(sourceId, name, label, entries)
                }
            }
        }.onFailure { Log.w(TAG, "Couldn't read the guides' channel lists", it) }.getOrNull()
        if (lists == null) {
            LIVE_REPORT.add("Assign EPG lists: saved copy out of date (read when Assign EPG is first opened)")
            return
        }
        // Which guide channels already feed a playlist channel (matched or assigned).
        val overrides = prefs.userState.first().epgOverrides
        val used = HashMap<String, MutableSet<String>>()
        (_autoMatches.value.values + overrides.values).forEach { used.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        // Only if nothing filled them in the meantime (a guide update in progress wins).
        if (_epgSources.value.isEmpty()) {
            _epgSources.value = lists.map { it.copy(usedIds = used[it.sourceId].orEmpty()) }
            LIVE_REPORT.add("Assign EPG lists: ${lists.size} guide(s) ready from last time")
        }
    }

    /** Stops reading a guide once its channel list has been read (the programmes aren't needed). */
    private class ChannelListRead(val channels: Map<String, XmltvChannel>) : RuntimeException()

    private val _epgListsLoading = MutableStateFlow(false)
    /** Assign EPG's channel lists are being read (see [ensureSourceLists]). */
    val epgListsLoading: StateFlow<Boolean> = _epgListsLoading.asStateFlow()

    /**
     * Assign EPG opened with no channel lists (no saved copy yet, e.g. the first time after
     * updating, or the guides changed without being read since): reads just each guide's
     * channel list, the short part at the top of the file. No programmes, and nothing at
     * start-up: only when Assign EPG actually needs them.
     */
    fun ensureSourceLists() {
        if (_epgSources.value.isNotEmpty() || _epgListsLoading.value) return
        _epgListsLoading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                readSourceLists(collectEpgTargets())
            } finally {
                _epgListsLoading.value = false
            }
        }
    }

    private suspend fun readSourceLists(targets: List<EpgTarget>) {
        if (_epgSources.value.isNotEmpty()) return
        val lists = ArrayList<EpgSourceChannels>()
        for (t in targets) {
            val file = epgFile(t.fileId)
            if (!file.exists()) continue
            val channels: Map<String, XmltvChannel> = try {
                openMaybeGzip(file).use { input ->
                    XmltvParser.parse(input, 0L, 0L) { xc -> throw ChannelListRead(LinkedHashMap(xc)) }
                }
                emptyMap()
            } catch (e: ChannelListRead) {
                e.channels
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the channel list of ${t.name}", e)
                emptyMap()
            }
            val label = runCatching { java.net.URI(t.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: t.name
            lists += EpgSourceChannels(
                t.fileId, t.name, label,
                channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }.sortedBy { it.displayName.lowercase() }
            )
        }
        if (lists.isEmpty()) return
        val overrides = prefs.userState.first().epgOverrides
        val used = HashMap<String, MutableSet<String>>()
        (_autoMatches.value.values + overrides.values).forEach { used.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        if (_epgSources.value.isEmpty()) {
            _epgSources.value = lists.map { it.copy(usedIds = used[it.sourceId].orEmpty()) }
            saveSourceLists(targets, lists)
            LIVE_REPORT.add("Assign EPG lists: ${lists.size} guide(s) read for Assign EPG")
        }
    }

    private fun saveEpgLogos(map: Map<String, String>) {
        runCatching {
            val o = org.json.JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            baseDir.mkdirs()
            epgLogosFile.writeText(o.toString())
        }
    }

    /** "Prefer logos from playlist / EPG" for one playlist: only the logos change, nothing reloads. */
    suspend fun setPlaylistLogoSource(id: String, logoSource: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(logoSource = logoSource) else it } }
    }

    private suspend fun rebuild() {
        mutex.withLock { rebuildLocked() }
    }

    private suspend fun refreshInternal(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String> = emptySet()
    ): Unit {
        val automatic = !forcePlaylists && !forceEpg && forceIds.isEmpty()
        if (!automatic) {
            // An update you asked for (Update now, a new playlist…) doesn't queue up behind an
            // automatic check that's waiting for Nuvio's player to close: that check stops
            // waiting and finishes now. (It could wait for hours, and "Update now" did nothing.)
            manualQueued.value = true
            if (mutex.isLocked) LIVE_REPORT.add("Update asked for: finishing the update already running first")
        }
        mutex.withLock { refreshLocked(forcePlaylists, forceEpg, forceIds, automatic) }
    }

    /** An update you asked for is waiting: automatic checks stop waiting for Nuvio's player. */
    private val manualQueued = MutableStateFlow(false)

    private suspend fun refreshLocked(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String>,
        automatic: Boolean
    ) {
        if (!automatic) manualQueued.value = false
        val t0Update = System.currentTimeMillis()
        LIVE_REPORT.add(if (automatic) "Update check started" else "Update started")
        try { refreshBody(forcePlaylists, forceEpg, forceIds, automatic) }
        finally { LIVE_REPORT.add("Update finished in ${(System.currentTimeMillis() - t0Update) / 1000} s") }
        runCatching { sweepLeftoverFiles() }.onFailure { Log.w(TAG, "Leftover file sweep failed", it) }
    }

    /**
     * Deletes saved guide and playlist files no source uses any more. A playlist's built-in
     * guide is saved under a name made from its web address, so removing the playlist, or the
     * provider changing that address (a new domain, a new login), left the old file behind for
     * good. Guides are often 50–200 MB, and a box with little storage left slows right down.
     * Only files untouched for two weeks go, so a guide that's just turned off for a while, or
     * a source being added right now, is left alone (one turned back on is downloaded again).
     */
    private suspend fun sweepLeftoverFiles() {
        val now = System.currentTimeMillis()
        val oldEnough = now - 14L * 24 * HOUR
        val guideIds = collectEpgTargets().mapTo(HashSet()) { it.fileId }
        prefs.currentEpgSources().forEach { guideIds += it.id }
        val playlistIds = prefs.currentPlaylists().mapTo(HashSet()) { it.id }
        var freed = 0L
        var count = 0
        fun sweep(dir: File, idOf: (String) -> String, keep: Set<String>) {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.lastModified() > oldEnough) return@forEach
                if (idOf(f.name) in keep) return@forEach
                val size = f.length()
                if (f.delete()) { freed += size; count++ }
            }
        }
        sweep(epgDir, { it.substringBefore(".xml").substringBefore(".tmp") }, guideIds)
        sweep(playlistDir, { it.substringBefore(".m3u").substringBefore(".tmp") }, playlistIds)
        if (count > 0) LIVE_REPORT.add("Cleanup: deleted $count saved guide/playlist files no source uses (${freed / (1024 * 1024)} MB freed)")
    }

    private suspend fun refreshBody(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String>,
        automatic: Boolean
    ) {
        val settings = prefs.currentSettings()
        val now = System.currentTimeMillis()
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        var changed = false
        // Automatic checks wait while a movie or episode is playing in Nuvio's player (unless
        // an update you asked for is waiting behind this one).
        suspend fun outOfTheWay() {
            if (!automatic || !BackgroundWork.nuvioPlaying.value || manualQueued.value) return
            LIVE_REPORT.add("Update check waiting while Nuvio's player is open")
            kotlinx.coroutines.flow.combine(BackgroundWork.nuvioPlaying, manualQueued) { playing, manual -> !playing || manual }
                .first { it }
        }

        for (pl in playlists) {
            outOfTheWay()
            val file = playlistFile(pl.id)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.playlistRefreshHours.coerceAtLeast(1) * HOUR
            if (forcePlaylists || stale || pl.id in forceIds) {
                LIVE_REPORT.add("Checking playlist \"${pl.name}\" for updates")
                setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
                val before = if (file.exists()) file.length() to file.lastModified() else null
                val result = runCatching {
                    if (pl.isXtream || pl.isStalker) {
                        val old = if (file.exists()) File(file.parentFile, file.name + ".prev").also { file.copyTo(it, overwrite = true) } else null
                        if (pl.isStalker) stalker.writePlaylist(pl, file) else downloadXtream(pl, file)
                        // Xtream lists are rebuilt each time: unchanged if identical to before.
                        val unchanged = old != null && sameDecoded(old, file)
                        old?.delete()
                        if (!unchanged) markChanged(file)
                        LIVE_REPORT.add(if (unchanged) "Playlist \"${pl.name}\": no changes" else "Playlist \"${pl.name}\": new content")
                        !unchanged
                    } else download(pl.resolvedUrl(), file, pl.userAgent, progressLabel = "Downloading playlist \"${pl.name}\"")
                }
                // M3U links from Xtream providers (get.php with a login): the account's details.
                if (!pl.isXtream && !pl.isStalker) refreshM3uAccount(pl)
                val err = result.exceptionOrNull()?.let { e ->
                    when (e) {
                        is java.net.UnknownHostException -> "Couldn't find the provider's server (${e.message?.substringAfter("\"")?.substringBefore("\"") ?: "address"}). Check the address, your internet, or try again later."
                        is java.net.SocketTimeoutException -> "The provider took too long to answer (timeout). It may be busy; try again later."
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
                prefs.updatePlaylists { list ->
                    list.map {
                        if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it
                    }
                }
                if (err != null) {
                    Log.w(TAG, "Playlist ${pl.name} failed: $err")
                    LIVE_REPORT.add("Playlist \"${pl.name}\" failed: $err" + if (pl.isXtream) " (Xtream login: server ${if (pl.xtreamServer.isBlank()) "missing" else "set"}, user ${if (pl.xtreamUsername.isBlank()) "missing" else "set"}, password ${if (pl.xtreamPassword.isBlank()) "missing" else "set"})" else "")
                }
                if (result.getOrNull() == true || (before == null && file.exists())) changed = true
            }
        }

        // Build channels first so EPG matching knows which channels exist.
        if (changed || _channels.value.isEmpty()) {
            outOfTheWay()
            setStatus(loading = true, message = "Reading channels…")
            buildChannels()
            saveChannels() // so the next start uses it straight away
        }

        val epgTargets = collectEpgTargets()
        // Files saved by earlier versions, unzipped: zipped once (see download()).
        (epgTargets.map { epgFile(it.fileId) } + playlists.map { playlistFile(it.id) }).forEach { zipInPlace(it) }
        for (target in epgTargets) {
            outOfTheWay()
            val file = epgFile(target.fileId)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            // A playlist saved again (its login, user agent…): its own guide is downloaded again too.
            val forcedHere = target.sourceId in forceIds || target.playlistId in forceIds
            if (forceEpg || stale || forcedHere || guideHoles || (target.sourceId == null && changed && !file.exists())) {
                setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
                LIVE_REPORT.add("Downloading guide \"${target.name}\" (user agent: ${target.userAgent.ifBlank { "app default" }})")
                val result = runCatching {
                    if (target.url.startsWith("reseq-stalker-epg://")) {
                        val source = playlists.single { it.id == target.playlistId && it.isStalker }
                        stalker.writeGuide(source, file)
                        markChanged(file)
                        true
                    } else download(
                        target.url, file, target.userAgent,
                        progressLabel = "Downloading guide \"${target.name}\"",
                        // Also whole when the guide has gone stale: a provider that keeps answering
                        // "not changed" to the short check left channels without listings overnight.
                        full = forceEpg || forcedHere || stale || guideHoles,
                        reportAs = target.name
                    )
                }
                if (result.getOrNull() == true) changed = true
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                if (target.sourceId != null) {
                    prefs.updateEpgSources { list ->
                        list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                    }
                }
                if (err != null) {
                    Log.w(TAG, "EPG ${target.name} failed: $err")
                    LIVE_REPORT.add("Guide \"${target.name}\" failed: $err")
                }
            }
        }

        // A guide kept in the database fills in in the background (empty for a moment), so an
        // empty list then doesn't mean there's no guide: only re-match if something changed.
        // "Update guide now" always matches the guides again, even when the files came back
        // unchanged: a guide matched wrong or stored empty was otherwise kept until a file
        // changed, so pressing Update did nothing for it.
        if (changed || forceEpg || guideHoles || (_programs.value.isEmpty() && (!guideInDb || storedGuideEmpty))) {
            storedGuideEmpty = false
            guideHoles = false
            outOfTheWay()
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, epgTargets)
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    private suspend fun rebuildLocked() {
        // Reading the saved channels and guide takes a second or two and changes nothing, so no
        // "Loading channels…" bubble for it (people took it for a full reload every time). The
        // bubble only shows if the playlists really have to be read again.
        setStatus(loading = true, message = null, quiet = true)
        // The channel list as it was last read, when no playlist has changed since: Live TV
        // (Favorites included) appears straight away instead of re-reading every playlist file.
        var t0 = System.currentTimeMillis()
        // The stored guide is read at the same time as the channel list (it only has to be
        // checked against the channels afterwards), so the two waits overlap.
        val guidePreRead = scope.async(Dispatchers.IO) { preReadGuide() }
        if (!loadSavedChannels()) {
            setStatus(loading = true, message = "Loading channels…")
            buildChannels()
            saveChannels()
            LIVE_REPORT.add("Channels re-read from playlists: ${_channels.value.size} in ${System.currentTimeMillis() - t0} ms")
        } else {
            LIVE_REPORT.add("Saved channel list used: ${_channels.value.size} channels in ${System.currentTimeMillis() - t0} ms")
        }
        val targets = collectEpgTargets()
        val settings = prefs.currentSettings()
        // The guide as it was last matched, if nothing it depends on has changed: the guide
        // fills in straight away instead of re-reading every guide file at each start.
        t0 = System.currentTimeMillis()
        if (!loadGuideFromDb(settings, targets, guidePreRead.await())) {
            if (dropRemovedPlaylistsFromGuide(settings, targets)) {
                LIVE_REPORT.add("Guide kept: turned-off playlists' channels taken out in ${System.currentTimeMillis() - t0} ms")
            } else {
                buildPrograms(settings, targets)
                LIVE_REPORT.add("Guide re-read from guide files in ${System.currentTimeMillis() - t0} ms")
            }
        } else {
            LIVE_REPORT.add("Stored guide used: ${_programs.value.size} channels with listings in ${System.currentTimeMillis() - t0} ms")
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    /** Which parts of a saved copy's fingerprint differ (for the start-up report). */
    private fun fingerprintDiff(old: String, new: String): String {
        val a = old.split('|', ',').toSet()
        val b = new.split('|', ',').toSet()
        val changed = (b - a).take(4).joinToString("; ") { it.take(60) }
        return if (changed.isBlank()) "format changed" else "changed: $changed"
    }

    // ---------------------------------------------------------------- saved channel list

    private val savedChannelsFile get() = File(baseDir, "channels_cache3.bin")

    /** Everything the channel list is built from; a different value means it must be re-read. */
    private suspend fun channelsFingerprint(): String {
        val sb = StringBuilder().append(CHANNELS_CACHE_VERSION).append('|')
        prefs.currentPlaylists().filter { it.liveEnabled }.forEach { pl ->
            val f = playlistFile(pl.id)
            sb.append(pl.id).append(':').append(pl.name.hashCode()).append(':').append(pl.userAgent.hashCode())
                .append(':').append(f.length()).append(':').append(contentStamp(f)).append(',')
        }
        return sb.toString()
    }

    /**
     * Saved channel list, format 3: not zipped (unzipping cost more than reading a slightly
     * bigger file on TV boxes), and text that repeats across channels (playlist, group,
     * catch-up type) stored once and referred to by number. Faster to read, less memory.
     */
    private suspend fun loadSavedChannels(): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(baseDir, "channels_cache.bin").delete() } // the old zipped format
        val file = savedChannelsFile
        if (!file.exists()) return@withContext false
        val expected = channelsFingerprint()
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
                val stored = input.readUTF()
                if (stored != expected) {
                    LIVE_REPORT.add("Saved channel list out of date: ${fingerprintDiff(stored, expected)}")
                    return@withContext false
                }
                val epg = HashMap<String, List<String>>()
                repeat(input.readInt()) {
                    val id = input.readUTF()
                    epg[id] = List(input.readInt()) { input.readUTF() }
                }
                val table = Array(input.readInt()) { input.readUTF() }
                fun shared(): String = table[input.readInt()]
                fun opt(): String? = if (input.readBoolean()) input.readUTF() else null
                val count = input.readInt()
                val list = ArrayList<LiveChannel>(count)
                repeat(count) {
                    val key = input.readUTF()
                    val sourceId = shared()
                    val sourceName = shared()
                    val name = input.readUTF()
                    val tvgId = opt()
                    val tvgName = opt()
                    val logo = opt()
                    val groupId = shared()
                    val group = shared()
                    val number = input.readInt()
                    val url = input.readUTF()
                    val headerCount = input.readInt()
                    val headers: Map<String, String> = if (headerCount == 0) emptyMap()
                    else HashMap<String, String>(headerCount * 2).apply { repeat(headerCount) { put(shared(), input.readUTF()) } }
                    val catchup = if (input.readBoolean()) CatchupInfo(shared(), opt(), input.readInt(), input.readDouble()) else null
                    val drm = if (input.readBoolean()) {
                        val scheme = shared()
                        val license = input.readUTF()
                        val lh = HashMap<String, String>().apply { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } }
                        com.nuvio.tv.livetv.model.DrmInfo(scheme, license, lh, opt())
                    } else null
                    list += LiveChannel(key, sourceId, sourceName, name, tvgId, tvgName, logo, groupId, group, number, url, headers, catchup, drm)
                }
                embeddedEpgUrls.clear()
                embeddedEpgUrls.putAll(epg)
                _channels.value = list
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved channel list unreadable", it)
            false
        }
    }

    private suspend fun saveChannels() = withContext(Dispatchers.IO) {
        val fingerprint = channelsFingerprint()
        runCatching {
            val list = _channels.value
            // Text shared by many channels, stored once.
            val index = LinkedHashMap<String, Int>()
            fun id(v: String): Int = index.getOrPut(v) { index.size }
            list.forEach { c ->
                id(c.sourceId); id(c.sourceName); id(c.groupId); id(c.group)
                c.headers.keys.forEach { id(it) }
                c.catchup?.let { id(it.type) }
                c.drm?.let { id(it.scheme) }
            }
            val tmp = File(baseDir, "channels_cache3.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                fun opt(v: String?) { out.writeBoolean(v != null); if (v != null) out.writeUTF(v) }
                out.writeUTF(fingerprint)
                out.writeInt(embeddedEpgUrls.size)
                embeddedEpgUrls.forEach { (eid, urls) -> out.writeUTF(eid); out.writeInt(urls.size); urls.forEach { out.writeUTF(it) } }
                out.writeInt(index.size)
                index.keys.forEach { out.writeUTF(it) }
                out.writeInt(list.size)
                list.forEach { c ->
                    out.writeUTF(c.key)
                    out.writeInt(index.getValue(c.sourceId)); out.writeInt(index.getValue(c.sourceName))
                    out.writeUTF(c.name)
                    opt(c.tvgId); opt(c.tvgName); opt(c.logo)
                    out.writeInt(index.getValue(c.groupId)); out.writeInt(index.getValue(c.group))
                    out.writeInt(c.number)
                    out.writeUTF(c.url)
                    out.writeInt(c.headers.size)
                    c.headers.forEach { (k, v) -> out.writeInt(index.getValue(k)); out.writeUTF(v) }
                    out.writeBoolean(c.catchup != null)
                    c.catchup?.let { cu -> out.writeInt(index.getValue(cu.type)); opt(cu.source); out.writeInt(cu.days); out.writeDouble(cu.correctionHours) }
                    out.writeBoolean(c.drm != null)
                    c.drm?.let { d ->
                        out.writeInt(index.getValue(d.scheme)); out.writeUTF(d.license)
                        out.writeInt(d.licenseHeaders.size); d.licenseHeaders.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
                        opt(d.manifestType)
                    }
                }
            }
            if (!tmp.renameTo(savedChannelsFile)) { tmp.copyTo(savedChannelsFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the channel list", it) }
    }

    // ---------------------------------------------------------------- saved guide

    /** Assign EPG needs the full guide-channel lists, which the saved guide skips. */
    @Volatile private var epgDetailsPending = false

    // ---------------------------------------------------------------- hidden channels

    /** The channels the stored guide left out (hidden in every profile), as last stored. */
    private val skippedFile get() = File(baseDir, "guide_skipped.txt")
    @Volatile private var guideSkipped: Set<String>? = null

    private fun saveSkipped(keys: Set<String>) {
        guideSkipped = keys
        runCatching {
            val tmp = File(baseDir, skippedFile.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()))).use { out ->
                out.writeInt(keys.size)
                keys.forEach { out.writeUTF(it) }
            }
            if (!tmp.renameTo(skippedFile)) { tmp.copyTo(skippedFile, overwrite = true); tmp.delete() }
        }
    }

    /** Null for a guide stored before channels were left out (it has everything). */
    private fun loadSkipped(): Set<String>? = guideSkipped ?: runCatching {
        if (!skippedFile.exists()) return@runCatching null
        java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(skippedFile.inputStream()))).use { input ->
            HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
        }
    }.getOrNull()?.also { guideSkipped = it }

    private var hiddenWatch: kotlinx.coroutines.Job? = null

    /** Follows hiding and showing groups and channels (in any profile) once the guide is loaded. */
    private fun watchHidden() {
        if (hiddenWatch != null) return
        hiddenWatch = scope.launch {
            prefs.hiddenEverywhere.collectLatest {
                delay(2_000) // a few changes in a row are handled together
                // Once started, finished (a newer change waits its turn instead of cutting it short).
                withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock { syncHidden() } }
            }
        }
    }

    /**
     * Keeps the stored guide in step with what's hidden everywhere:
     *  - newly hidden channels: their listings are deleted (nothing is read);
     *  - channels shown again: the guide is read from the saved guide files, so they get listings.
     */
    private suspend fun syncHidden() = withContext(Dispatchers.IO) {
        if (!guideInDb) return@withContext
        val channels = _channels.value
        if (channels.isEmpty()) return@withContext
        val hidden = prefs.currentHiddenEverywhere()
        val nowHidden = channels.filter { hidden.hides(it.key, it.groupId) }.mapTo(HashSet()) { it.key }
        val skipped = loadSkipped() ?: emptySet() // older guides stored every channel
        val keys = channels.mapTo(HashSet()) { it.key }
        val shownAgain = skipped.filter { it in keys && it !in nowHidden }
        if (shownAgain.isNotEmpty()) {
            LIVE_REPORT.add("Guide: ${shownAgain.size} channels shown again, reading their listings")
            setStatus(loading = true, message = "Updating guide…")
            buildPrograms(prefs.currentSettings(), collectEpgTargets())
            _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            return@withContext
        }
        val newlyHidden = nowHidden - skipped
        if (newlyHidden.isEmpty()) return@withContext
        runCatching { guideDb.deleteChannels(newlyHidden) }.onFailure { Log.w(TAG, "Couldn't drop hidden listings", it) }
        _programs.value = _programs.value - newlyHidden
        saveSkipped(skipped.filterTo(HashSet()) { it in keys } + newlyHidden)
        scheduleCompact()
    }

    private var compactJob: kotlinx.coroutines.Job? = null

    /** Gives the guide database's empty space back, a little after a big change, out of the way. */
    private fun scheduleCompact() {
        compactJob?.cancel()
        compactJob = scope.launch {
            delay(30_000)
            BackgroundWork.awaitIdle()
            mutex.withLock { withContext(Dispatchers.IO) { guideDb.compact() } }
        }
    }

    /** Builds the full guide details (for Assign EPG) if the saved guide was used. */
    fun ensureEpgDetails() {
        if (!epgDetailsPending) return
        epgDetailsPending = false
        scope.launch {
            mutex.withLock { buildPrograms(prefs.currentSettings(), collectEpgTargets()) }
        }
    }

    private val savedGuideFile get() = File(baseDir, "guide_cache.bin")

    // ---------------------------------------------------------------- guide database

    /** The time span currently loaded from the guide database (all of it if the database isn't used). */
    @Volatile private var loadedFrom = Long.MAX_VALUE
    @Volatile private var loadedTo = Long.MIN_VALUE
    @Volatile private var guideInDb = false
    /** The stored guide turned out to have no listings around now: match the guides again. */
    @Volatile private var storedGuideEmpty = false

    /** The stored guide is missing listings for many channels that were matched (it ran out for them). */
    @Volatile private var guideHoles = false
    @Volatile private var holeRefreshTried = false
    private val rangeMutex = Mutex()

    private fun defaultWindow(now: Long = System.currentTimeMillis()) = (now - 3 * HOUR) to (now + 12 * HOUR + RANGE_SLACK_MS)

    /**
     * Opening Live TV: if the guide database matches the current guide and channels, read just
     * the hours around now. Instant, however big the guide is.
     */
    /** What [preReadGuide] read ahead: the stored fingerprint, and the priority channels' listings. */
    private class GuidePreRead(val fingerprint: String?, val from: Long, val to: Long, val window: Map<String, List<EpgProgram>>)

    /** Channels to fill in first: the group you were last looking at, and your Favorites. */
    private val priorityFile get() = File(baseDir, "guide_priority.txt")

    private fun priorityKeys(): List<String> =
        runCatching { priorityFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** Loads these channels' listings straight away if they aren't loaded yet (the group you open). */
    fun loadChannelsNow(keys: List<String>) {
        if (!guideInDb || keys.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val missing = keys.distinct().filter { it !in _programs.value }
            if (missing.isEmpty()) return@launch
            val got = runCatching { guideDb.range(loadedFrom, loadedTo, light = true, channels = missing) }.getOrNull() ?: return@launch
            rangeMutex.withLock {
                val cur = _programs.value
                _programs.value = cur + got.filterKeys { it !in cur }
            }
        }
    }

    /** Remembers which channels to load first next time, and loads them now if needed. */
    fun rememberPriority(keys: List<String>) {
        val list = keys.distinct().take(600)
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { priorityFile.writeText(list.joinToString("\n")) }
            if (!guideInDb) return@launch
            val missing = list.filter { it !in _programs.value }
            if (missing.isEmpty()) return@launch
            val got = runCatching { guideDb.range(loadedFrom, loadedTo, light = true, channels = missing) }.getOrNull() ?: return@launch
            rangeMutex.withLock { _programs.value = _programs.value + got }
        }
    }

    private fun preReadGuide(): GuidePreRead? = runCatching {
        val now = System.currentTimeMillis()
        val from = now - 60 * 60 * 1000L
        val to = now + 4 * HOUR
        val fp = guideDb.fingerprint() ?: return@runCatching null
        val keys = priorityKeys()
        val window = if (keys.isEmpty()) emptyMap() else guideDb.range(from, to, light = true, channels = keys)
        GuidePreRead(fp, from, to, window)
    }.getOrNull()

    /**
     * Opening Live TV: if the guide database matches the current guide and channels, show the
     * channels you were last looking at straight away (read ahead, short form), then fill in all
     * the others in the background. Instant, however big the guide is.
     */
    private suspend fun loadGuideFromDb(settings: LiveTvSettings, targets: List<EpgTarget>, pre: GuidePreRead?): Boolean = withContext(Dispatchers.IO) {
        if (_channels.value.isEmpty()) return@withContext false
        runCatching { savedGuideFile.delete() } // the old single-file copy isn't used any more
        val expected = guideFingerprint(settings, targets)
        val stored = pre?.fingerprint ?: runCatching { guideDb.fingerprint() }.getOrNull()
        if (stored != expected) {
            LIVE_REPORT.add(if (stored == null) "No stored guide yet" else "Stored guide out of date: ${fingerprintDiff(stored, expected)}")
            return@withContext false
        }
        val now = System.currentTimeMillis()
        val from = pre?.from ?: (now - 60 * 60 * 1000L)
        val to = pre?.to ?: (now + 4 * HOUR)
        _programs.value = pre?.window.orEmpty()
        fullRangeChannels.clear(); _guideGeneration.value++
        _autoMatches.value = runCatching { guideDb.autoMatches() }.getOrDefault(emptyMap())
        loadedFrom = from
        loadedTo = to
        guideInDb = true
        lightPrograms = true
        epgDetailsPending = true
        // The guides' channel lists (for Assign EPG), saved last time the guides were read, so
        // they're ready without a Full Scan.
        if (_epgSources.value.isEmpty()) scope.launch(Dispatchers.IO) { loadSourceLists(targets) }
        LIVE_REPORT.add("First channels' listings ready: ${_programs.value.size} channels")
        // Everything else (short form, the usual hours), in the background, in one read.
        rangeBusy.set(true)
        scope.launch(BackgroundWork.reads) {
          try {
            val t0 = System.currentTimeMillis()
            val (fullFrom, fullTo) = defaultWindow()
            val rest = runCatching { guideDb.range(fullFrom, fullTo, light = true) }.getOrNull()
            if (rest != null) rangeMutex.withLock {
                val merged = HashMap<String, List<EpgProgram>>(rest)
                _programs.value.forEach { (k, v) -> if ((merged[k]?.size ?: 0) < v.size) merged[k] = v }
                _programs.value = merged
                loadedFrom = minOf(loadedFrom, fullFrom)
                loadedTo = maxOf(loadedTo, fullTo)
            }
            LIVE_REPORT.add("All channels' listings ready: ${_programs.value.size} channels in ${System.currentTimeMillis() - t0} ms")
            // The stored guide has nothing for now or the next hours (it was stored empty, or
            // all of it has run out): it was still taken as up to date, so the guide stayed
            // empty until a playlist or guide changed. Match the guide files again now (and
            // download them again if they're old too).
            // Same when it only ran out for many of the channels (not all of them): the guide
            // was used as up to date and those channels stayed empty until Update was pressed.
            val matched = _autoMatches.value.keys
            val missing = matched.count { _programs.value[it].isNullOrEmpty() }
            // At most once every 3 hours (a download, even an unchanged one, resets the file's
            // date), so a guide that simply has no listings for many channels isn't fetched at every start.
            val checkedRecently = targets.none { System.currentTimeMillis() - epgFile(it.fileId).lastModified() > 3 * HOUR }
            // And once per run of the app: a provider whose guide simply lacks many channels would
            // otherwise have the whole guide downloaded and read again every few hours, which
            // makes the app crawl while it works.
            if (!holeRefreshTried && !checkedRecently && _programs.value.isNotEmpty() && matched.size >= 20 && missing * 100 > matched.size * 15 && targets.isNotEmpty()) {
                holeRefreshTried = true
                guideHoles = true
                LIVE_REPORT.add("Stored guide has no listings now for $missing of ${matched.size} matched channels: updating the guides")
                scope.launch { runCatching { refreshStale() } }
            }
            if (_programs.value.isEmpty() && _channels.value.isNotEmpty() && targets.isNotEmpty()) {
                storedGuideEmpty = true
                // Also download the guides whole (not "only if changed"): a guide file that stopped
                // refreshing kept being matched again and again, still empty. At most every 3 hours.
                guideHoles = !checkedRecently
                LIVE_REPORT.add("Stored guide has no listings for now: reading the guides again")
                scope.launch { runCatching { refreshStale() } }
            }
          } finally {
            rangeBusy.set(false)
            pendingRange?.let { (f, t) -> pendingRange = null; ensureRange(f, t) }
          }
        }
        true
    }

    /** Programs in memory may be the short form (no descriptions): see [programDetails]. */
    @Volatile var lightPrograms = false
        private set

    /** One show's full details (description, cast…), from the stored guide. */
    suspend fun programDetails(channelKey: String, startMs: Long): EpgProgram? = withContext(Dispatchers.IO) {
        if (!guideInDb) null else runCatching { guideDb.program(channelKey, startMs) }.getOrNull()
    }

    /**
     * Makes sure listings for [fromMs]..[toMs] are loaded (scrolling the guide ahead or back,
     * catch-up). Reads only what's missing; does nothing when the database isn't in use.
     */
    /** A big read is running: further requests wait for it instead of starting another. */
    private val rangeBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while the guide is being read in bulk (start-up): other big guide searches wait for it. */
    fun guideReadBusy(): Boolean = rangeBusy.get()
    @Volatile private var pendingRange: Pair<Long, Long>? = null

    fun ensureRange(fromMs: Long, toMs: Long) {
        dropOldHours(fromMs)
        if (!guideInDb || (fromMs >= loadedFrom && toMs <= loadedTo)) return
        if (!rangeBusy.compareAndSet(false, true)) {
            // One read at a time (each covers every channel): remember the widest request.
            val p = pendingRange
            pendingRange = if (p == null) fromMs to toMs else minOf(p.first, fromMs) to maxOf(p.second, toMs)
            return
        }
        scope.launch(BackgroundWork.reads) {
          try {
            // Read without holding the lock (a big read used to make everything else wait for it,
            // like a group you just opened); only the merge is locked.
            val from = minOf(fromMs, loadedFrom)
            // A few hours extra ahead: the guide asks for "12 hours from now" every 30 seconds,
            // and without slack each ask went past what was loaded by 30 seconds.
            val to = maxOf(toMs + RANGE_SLACK_MS, loadedTo)
            // Only the hours not loaded yet. It used to read everything from the earliest hour
            // loaded to the latest again, for every channel, each time the guide was scrolled
            // further ahead: the further you went, the longer each read took, so new listings
            // took up to a minute to appear at the end of what was shown.
            val haveSome = loadedFrom != Long.MAX_VALUE && loadedTo != Long.MIN_VALUE && loadedFrom < loadedTo
            val parts = if (!haveSome || to <= loadedFrom || from >= loadedTo) listOf(from to to)
            else buildList {
                if (from < loadedFrom) add(from to loadedFrom)
                if (to > loadedTo) add(loadedTo to to)
            }
            val reads = parts.map { (f, t) ->
                runCatching { guideDb.range(f, t, light = lightPrograms) }.getOrNull() ?: return@launch
            }
            rangeMutex.withLock {
                if (fromMs >= loadedFrom && toMs <= loadedTo) return@withLock
                val merged = HashMap(_programs.value)
                reads.forEach { got ->
                    got.forEach { (ch, list) ->
                        val cur = merged[ch]
                        merged[ch] = if (cur.isNullOrEmpty()) list else {
                            // Shows running across the edge come back in both reads: once each.
                            val starts = cur.mapTo(HashSet()) { it.startMs }
                            val extra = list.filter { it.startMs !in starts }
                            if (extra.isEmpty()) cur else (cur + extra).sortedBy { it.startMs }
                        }
                    }
                }
                _programs.value = merged
                loadedFrom = minOf(from, loadedFrom)
                loadedTo = maxOf(to, loadedTo)
            }
          } finally {
            rangeBusy.set(false)
            pendingRange?.let { (f, t) -> pendingRange = null; ensureRange(f, t) }
          }
        }
    }

    @Volatile private var lastDropAt = 0L

    /**
     * Lets go of listings that ended long ago. New hours were added as time passed but old ones
     * were never dropped, and TV boxes keep the app open for days: memory slowly filled with
     * days of past listings for every channel, and the app got slower the longer the box stayed
     * on. Anything older than a day before what the guide asks for goes (at most every 30
     * minutes); scrolling back for catch-up reads those hours from the guide database again.
     * Only when the guide is kept in the database, so nothing is ever lost.
     */
    private fun dropOldHours(fromMs: Long) {
        if (!guideInDb || loadedFrom == Long.MAX_VALUE) return
        val now = System.currentTimeMillis()
        if (now - lastDropAt < 30 * 60_000L) return
        val cutoff = minOf(fromMs - RANGE_SLACK_MS, now - 24 * HOUR)
        if (loadedFrom >= cutoff) return
        lastDropAt = now
        scope.launch(BackgroundWork.quiet) {
            rangeMutex.withLock {
                if (loadedFrom >= cutoff) return@withLock
                val cur = _programs.value
                var changed = false
                val next = HashMap<String, List<EpgProgram>>(cur.size)
                cur.forEach { (key, list) ->
                    // Lists are in start order: only ones that begin before the cutoff change
                    // (the others are kept as they are, so their guide rows needn't redraw).
                    if (list.isNotEmpty() && list[0].stopMs <= cutoff) {
                        changed = true
                        val kept = list.filter { it.stopMs > cutoff }
                        if (kept.isNotEmpty()) next[key] = kept
                    } else next[key] = list
                }
                if (changed) _programs.value = next
                loadedFrom = cutoff
            }
        }
    }

    // ---------------------------------------------------------------- a group's full guide

    /** Channels whose listings are loaded for the whole guide (as many days ahead as set). */
    private val fullRangeChannels = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Goes up each time the guide in memory is replaced with just the hours around now (a guide
     * update, a rebuild after changing Guide Days Ahead…). The guide screen then asks for its
     * group's days ahead again: before, they were only asked for when you changed group, so
     * after an update the guide ran out "tomorrow" whatever Guide Days Ahead was set to.
     */
    private val _guideGeneration = MutableStateFlow(0)
    val guideGeneration: StateFlow<Int> = _guideGeneration.asStateFlow()
    private var fullRangeJob: kotlinx.coroutines.Job? = null

    /**
     * The group on screen: its channels' listings for every day the guide keeps (Guide Days
     * Ahead), read in the background, so scrolling ahead in it never runs into the end of
     * what's loaded. Only the group's channels (at most [MAX_FULL_RANGE_CHANNELS], from the
     * top): all days for every channel of every playlist would be too much for smaller boxes.
     */
    fun ensureFullRange(channelKeys: List<String>) {
        if (!guideInDb || channelKeys.isEmpty()) return
        val todo = channelKeys.take(MAX_FULL_RANGE_CHANNELS).filter { it !in fullRangeChannels }
        if (todo.isEmpty()) return
        fullRangeJob?.cancel()
        fullRangeJob = scope.launch(BackgroundWork.reads) {
            val settings = prefs.currentSettings()
            val now = System.currentTimeMillis()
            val from = now - HOUR
            val to = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR
            // A batch at a time, so the first channels fill in quickly and a group change
            // stops the rest.
            for (batch in todo.chunked(60)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val got = runCatching { guideDb.range(from, to, light = lightPrograms, channels = batch) }.getOrNull() ?: return@launch
                rangeMutex.withLock {
                    val merged = HashMap(_programs.value)
                    batch.forEach { ch ->
                        val list = got[ch].orEmpty()
                        val cur = merged[ch]
                        merged[ch] = when {
                            list.isEmpty() -> cur ?: return@forEach
                            cur.isNullOrEmpty() -> list
                            else -> {
                                val starts = cur.mapTo(HashSet()) { it.startMs }
                                val extra = list.filter { it.startMs !in starts }
                                if (extra.isEmpty()) cur else (cur + extra).sortedBy { it.startMs }
                            }
                        }
                    }
                    _programs.value = merged
                }
                fullRangeChannels += batch
            }
        }
    }

    // ---------------------------------------------------------------- Xtream catch-up archive

    /** Channels whose archive listings were already fetched this session. */
    private val archiveFetched = java.util.Collections.synchronizedSet(HashSet<String>())
    private val archivePermits = kotlinx.coroutines.sync.Semaphore(3)
    @Volatile private var archivePausedUntil = 0L
    @Volatile private var apiFillReported = false

    /**
     * Past listings for Xtream catch-up channels, the way TiviMate gets them: many providers'
     * guide file only covers about a day back, while the Xtream API keeps the full catch-up
     * archive (up to 7 days or more) per channel. Fetched for the channels you're looking at,
     * once per session, and stored with the rest of the guide.
     */
    fun loadXtreamArchive(channels: List<LiveChannel>, missingOnly: Boolean = false) {
        // missingOnly: channels the guide file has nothing for. Most Xtream apps read each
        // channel's listings from the provider's API by stream number, so they show a guide
        // even when the guide file's ids don't line up with the channel list; this does the same.
        val wanted = channels.filter { ch ->
            ch.key !in archiveFetched &&
                (if (missingOnly) _programs.value[ch.key].isNullOrEmpty() else ch.catchup != null)
        }
        if (wanted.isEmpty()) return

        wanted.forEach { archiveFetched += it.key }
        if (System.currentTimeMillis() < archivePausedUntil) {
            wanted.forEach { archiveFetched -= it.key }
            return
        }
        scope.launch {
            val playlists = prefs.currentPlaylists().associateBy { it.id }
            val settings = prefs.currentSettings()
            wanted.forEach { ch ->
                val pl = playlists[ch.sourceId]?.takeIf { it.isXtream } ?: return@forEach
                val streamId = Regex("""/(\d+)(?:\.[a-z0-9]+)?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
                    .find(ch.url)?.groupValues?.get(1) ?: return@forEach
                archivePermits.withPermit {
                    // The provider said "too many requests": leave it alone for a few minutes, so
                    // these lookups never stand between you and playing something.
                    if (System.currentTimeMillis() < archivePausedUntil) { archiveFetched -= ch.key; return@withPermit }
                    runCatching {
                        val url = "${pl.xtreamBase()}/player_api.php?username=" +
                            java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8") +
                            "&password=" + java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8") +
                            "&action=get_simple_data_table&stream_id=$streamId"
                        val json = JSONObject(getText(url, pl.userAgent.ifBlank { DEFAULT_UA }))
                        val arr = json.optJSONArray("epg_listings") ?: return@runCatching
                        fun b64(v: String): String = runCatching {
                            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        }.getOrDefault(v)
                        val list = ArrayList<EpgProgram>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
                            val stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000)
                                ?: o.optString("end_timestamp").toLongOrNull()?.times(1000) ?: continue
                            if (stop <= start) continue
                            val title = b64(o.optString("title")).trim()
                            if (title.isEmpty()) continue
                            list += EpgProgram(
                                startMs = start,
                                stopMs = stop,
                                title = title,
                                description = b64(o.optString("description")).trim().takeIf { it.isNotEmpty() }
                            )
                        }
                        if (list.isEmpty()) return@runCatching
                        list.sortBy { it.startMs }
                        val now = System.currentTimeMillis()
                        // Keep what the guide file already has from about now on; the archive
                        // fills in the past.
                        val pastEnd = minOf(now, list.last().stopMs)
                        val past = list.filter { it.startMs < pastEnd }
                        val existing = _programs.value[ch.key].orEmpty()
                        // Beyond the end of the guide file's listings, the archive's later
                        // shows are used too (some guide files only cover a day ahead).
                        val existingEnd = existing.maxOfOrNull { it.stopMs } ?: now
                        val later = list.filter { it.startMs >= maxOf(existingEnd, now) }
                        if (past.isEmpty() && later.isEmpty()) return@runCatching
                        val from = past.firstOrNull()?.startMs ?: pastEnd
                        val keep = existing.filter { it.startMs >= pastEnd || it.startMs < from }
                        val merged = (past + keep + later).sortedBy { it.startMs }
                            .fold(ArrayList<EpgProgram>()) { acc, p -> if (acc.isEmpty() || p.startMs >= acc.last().stopMs - 60_000) acc += p; acc }
                        if (guideInDb) runCatching {
                            if (past.isNotEmpty()) guideDb.putChannelRange(ch.key, from, pastEnd, past)
                            if (later.isNotEmpty()) guideDb.putChannelRange(ch.key, later.first().startMs, later.last().stopMs, later)
                        }
                        _programs.value = _programs.value + (ch.key to merged)
                        loadedFrom = minOf(loadedFrom, from)
                        if (missingOnly && !apiFillReported) {
                            apiFillReported = true
                            LIVE_REPORT.add("Guide: listings for channels the guide file doesn't cover are coming from the Xtream API (first: ${ch.name})")
                        }
                    }.onFailure {
                        Log.w(TAG, "Catch-up archive for ${ch.name} unavailable", it)
                        if (it.message?.contains("429") == true) {
                            archivePausedUntil = System.currentTimeMillis() + 5 * 60_000L
                            archiveFetched -= ch.key
                            LIVE_REPORT.add("Provider said too many requests (HTTP 429): catch-up lookups paused for 5 minutes")
                        }
                    }
                }
            }
        }
    }

    /** One channel's whole schedule (overlay mode's days, catch-up following into later shows). */
    /** A channel's listings from an hour ago to [days] ahead (series recordings look through these). */
    suspend fun upcomingListings(channelKey: String, days: Int): List<EpgProgram> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!guideInDb) return@withContext _programs.value[channelKey].orEmpty()
        runCatching { guideDb.channelRange(channelKey, now - HOUR, now + days * 24 * HOUR) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: _programs.value[channelKey].orEmpty()
    }

    fun ensureChannelSchedule(channelKey: String) {
        if (!guideInDb) return
        scope.launch {
            val now = System.currentTimeMillis()
            val settings = prefs.currentSettings()
            val list = runCatching {
                guideDb.channelRange(channelKey, now - settings.epgPastHours.coerceAtLeast(1) * HOUR,
                    now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR)
            }.getOrNull() ?: return@launch
            val cur = _programs.value
            if ((cur[channelKey]?.size ?: 0) >= list.size) return@launch
            _programs.value = cur + (channelKey to list)
        }
    }

    /** Everything the matched guide depends on; a different value means the cache is stale. */
    private suspend fun guideFingerprint(settings: LiveTvSettings, targets: List<EpgTarget>): String {
        val overrides = prefs.userState.first().epgOverrides
        val sb = StringBuilder()
        sb.append(GUIDE_CACHE_VERSION).append('|')
        sb.append(settings.epgPastHours).append('|').append(settings.epgFutureDays).append('|')
        targets.forEach { t ->
            val f = epgFile(t.fileId)
            sb.append(t.fileId).append(':').append(f.length()).append(':').append(contentStamp(f)).append(',')
        }
        sb.append('|').append(overrides.hashCode())
        sb.append('|').append(_channels.value.size).append(':').append(_channels.value.sumOf { it.key.hashCode().toLong() })
        return sb.toString()
    }

    /** One guide file as the fingerprint sees it (also the format of the Assign EPG lists' stamp). */
    private fun targetStamp(t: EpgTarget): String {
        val f = epgFile(t.fileId)
        return t.fileId + ":" + f.length() + ":" + contentStamp(f)
    }

    /**
     * The fingerprint's parts, stored next to it: settings and hand-picked guides ("base"), each
     * guide file in order ("targets"), and each playlist's channels ("playlists"). With these, a
     * playlist turned off or removed can be told apart from any other change (see
     * [dropRemovedPlaylistsFromGuide]).
     */
    private suspend fun guideParts(settings: LiveTvSettings, targets: List<EpgTarget>, fingerprint: String): String {
        val overrides = prefs.userState.first().epgOverrides
        val playlists = JSONObject()
        _channels.value.groupBy { it.sourceId }.forEach { (id, list) ->
            playlists.put(id, "${list.size}:${list.sumOf { it.key.hashCode().toLong() }}")
        }
        return JSONObject()
            .put("fp", fingerprint)
            .put("base", "$GUIDE_CACHE_VERSION|${settings.epgPastHours}|${settings.epgFutureDays}|${overrides.hashCode()}")
            .put("targets", org.json.JSONArray(targets.map { targetStamp(it) }))
            .put("playlists", playlists)
            .toString()
    }

    /**
     * A playlist turned off or removed changes the guide's fingerprint, which used to mean reading
     * every other guide file again from start to finish. But each channel's guide match only
     * depends on that channel and the guides, so taking a playlist away changes nothing for the
     * others: its channels' listings are simply deleted from the stored guide.
     *
     * Only when nothing else changed: same settings and hand-picked guides, every other playlist
     * with exactly the channels it had, and the remaining guide files unchanged and in the same
     * order (a playlist's built-in guide may go with it, as long as no remaining channel was
     * matched to it). Otherwise (or for a guide stored by an older version) false: read it all.
     */
    private suspend fun dropRemovedPlaylistsFromGuide(settings: LiveTvSettings, targets: List<EpgTarget>): Boolean = withContext(Dispatchers.IO) {
        val channels = _channels.value
        if (channels.isEmpty()) return@withContext false
        val stored = runCatching { guideDb.fingerprint() }.getOrNull() ?: return@withContext false
        val old = runCatching { guideDb.parts()?.let { JSONObject(it) } }.getOrNull() ?: return@withContext false
        if (old.optString("fp") != stored) return@withContext false
        val fingerprint = guideFingerprint(settings, targets)
        val partsText = guideParts(settings, targets, fingerprint)
        val now = JSONObject(partsText)
        if (old.optString("base") != now.optString("base")) return@withContext false

        // Guide files: the remaining ones unchanged and in the same order, the rest only taken away.
        fun list(o: JSONObject): List<String> {
            val a = o.optJSONArray("targets") ?: return emptyList()
            return List(a.length()) { a.getString(it) }
        }
        val oldTargets = list(old)
        val newTargets = list(now)
        var i = 0
        for (t in oldTargets) if (i < newTargets.size && newTargets[i] == t) i++
        if (i != newTargets.size) return@withContext false
        val keptTargetIds = targets.mapTo(HashSet()) { it.fileId }
        val removedTargets = oldTargets.map { it.substringBefore(':') }.filterTo(HashSet()) { it !in keptTargetIds }

        // Playlists: every one still on has exactly the channels it had.
        val oldPl = old.optJSONObject("playlists") ?: return@withContext false
        val newPl = now.getJSONObject("playlists")
        for (id in newPl.keys()) if (oldPl.optString(id) != newPl.getString(id)) return@withContext false
        val removedPlaylists = oldPl.keys().asSequence().filterTo(HashSet()) { !newPl.has(it) }
        if (removedPlaylists.isEmpty()) return@withContext false

        val keys = channels.mapTo(HashSet()) { it.key }
        // A remaining channel matched to a guide that went away would need another match: read it all.
        val auto = runCatching { guideDb.autoMatches() }.getOrNull() ?: return@withContext false
        if (auto.any { (key, a) -> key in keys && a.sourceId in removedTargets }) return@withContext false

        // Hidden channels: group ids can change with the number of playlists ("playlist::group"
        // with several, just "group" with one), so check what's hidden against the stored guide.
        val hidden = prefs.currentHiddenEverywhere()
        val nowHidden = channels.filter { hidden.hides(it.key, it.groupId) }.mapTo(HashSet()) { it.key }
        val skipped = (loadSkipped() ?: emptySet()).filterTo(HashSet()) { it in keys }
        if (keys.any { it in skipped && it !in nowHidden }) return@withContext false // shown again: needs listings
        val newlyHidden = nowHidden - skipped
        // Remaining channels hand-assigned to a guide that went away have no listings any more.
        val overrides = prefs.userState.first().epgOverrides
        val lostPinned = overrides.filter { (key, a) -> key in keys && a.sourceId in removedTargets }.keys

        runCatching { guideDb.dropPlaylists(removedPlaylists, newlyHidden + lostPinned, fingerprint, partsText) }
            .onFailure { Log.w(TAG, "Couldn't take turned-off playlists out of the guide", it); return@withContext false }
        saveSkipped(skipped + newlyHidden)
        val logos = _epgLogos.value.filterKeys { it in keys && it !in lostPinned }
        if (logos.size != _epgLogos.value.size) { _epgLogos.value = logos; saveEpgLogos(logos) }
        trimSourceLists(removedTargets, oldTargets, targets)
        LIVE_REPORT.add("Guide: ${removedPlaylists.size} playlist(s) taken out without re-reading the guides")
        scheduleCompact()
        // The guide now matches its fingerprint: load it the usual way.
        loadGuideFromDb(settings, targets, preReadGuide())
    }

    /**
     * Guides that went with a turned-off playlist: taken out of Assign EPG's lists, in memory and
     * in the saved copy (re-stamped for the remaining guides, so it isn't thrown away).
     */
    private fun trimSourceLists(removed: Set<String>, oldStamps: List<String>, targets: List<EpgTarget>) {
        if (_epgSources.value.isNotEmpty()) {
            _epgSources.value = _epgSources.value.filter { it.sourceId !in removed }
        }
        val f = sourceListsFile
        if (!f.exists()) return
        val lists = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(f.inputStream()))).use { input ->
                if (input.readInt() != SOURCE_LISTS_VERSION) return@use null
                if (input.readUTF() != oldStamps.joinToString(",")) return@use null
                List(input.readInt()) {
                    val sourceId = input.readUTF(); val name = input.readUTF(); val label = input.readUTF()
                    val entries = List(input.readInt()) {
                        val id = input.readUTF()
                        val names = List(input.readInt()) { input.readUTF() }
                        EpgChannelEntry(id, names, input.readUTF().ifBlank { null })
                    }
                    EpgSourceChannels(sourceId, name, label, entries)
                }
            }
        }.getOrNull() ?: return
        saveSourceLists(targets, lists.filter { it.sourceId !in removed })
    }

    private suspend fun loadSavedGuide(settings: LiveTvSettings, targets: List<EpgTarget>): Boolean = withContext(Dispatchers.IO) {
        val file = savedGuideFile
        if (!file.exists() || _channels.value.isEmpty()) return@withContext false
        val expected = guideFingerprint(settings, targets)
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(file.inputStream()), 64 * 1024)).use { input ->
                if (input.readUTF() != expected) return@withContext false
                val programs = HashMap<String, List<EpgProgram>>()
                repeat(input.readInt()) {
                    val key = input.readUTF()
                    val list = ArrayList<EpgProgram>()
                    repeat(input.readInt()) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val desc = readOpt(input)
                        val category = readOpt(input)
                        val episode = XmltvParser.cleanEpisode(readOpt(input))
                        val icon = readOpt(input)
                        val year = input.readInt().takeIf { it > 0 }
                        val people = List(input.readInt()) { input.readUTF() }
                        list += EpgProgram(start, stop, title, desc, category, episode, icon, year, people)
                    }
                    programs[key] = list
                }
                val auto = HashMap<String, EpgAssignment>()
                repeat(input.readInt()) {
                    auto[input.readUTF()] = EpgAssignment(input.readUTF(), input.readUTF())
                }
                // Drop shows that have ended since the cache was saved (outside the window).
                val windowStart = System.currentTimeMillis() - settings.epgPastHours.coerceAtLeast(1) * HOUR
                _programs.value = programs.mapValues { (_, l) -> l.filter { it.stopMs > windowStart } }
                _autoMatches.value = auto
                epgDetailsPending = true
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved guide unreadable", it)
            false
        }
    }

    private fun saveGuide(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        // Huge guides (hundreds of thousands of listings) would make a very large file that's
        // slow to write and read; those are simply re-matched at start as before.
        if (programs.values.sumOf { it.size } > MAX_SAVED_LISTINGS) {
            savedGuideFile.delete()
            return
        }
        runCatching {
            val tmp = File(baseDir, "guide_cache.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()), 64 * 1024)).use { out ->
                out.writeUTF(fingerprint)
                out.writeInt(programs.size)
                programs.forEach { (key, list) ->
                    out.writeUTF(key)
                    out.writeInt(list.size)
                    list.forEach { p ->
                        out.writeLong(p.startMs)
                        out.writeLong(p.stopMs)
                        out.writeUTF(p.title.take(500))
                        writeOpt(out, p.description?.take(4000))
                        writeOpt(out, p.category?.take(200))
                        writeOpt(out, p.episode?.take(200))
                        writeOpt(out, p.icon?.take(1000))
                        out.writeInt(p.year ?: 0)
                        out.writeInt(p.people.size.coerceAtMost(20))
                        p.people.take(20).forEach { out.writeUTF(it.take(200)) }
                    }
                }
                out.writeInt(auto.size)
                auto.forEach { (key, a) -> out.writeUTF(key); out.writeUTF(a.sourceId); out.writeUTF(a.xmltvId) }
            }
            if (!tmp.renameTo(savedGuideFile)) { tmp.copyTo(savedGuideFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the guide", it) }
    }

    private fun readOpt(input: java.io.DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private fun writeOpt(out: java.io.DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun setStatus(loading: Boolean, message: String?, quiet: Boolean = false) {
        _status.value = _status.value.copy(loading = loading, message = message, quiet = quiet)
    }

    private data class EpgTarget(
        val fileId: String,
        val sourceId: String?,
        val name: String,
        val url: String,
        val userAgent: String,
        /** A playlist's own (built-in) guide: that playlist. */
        val playlistId: String? = null
    )

    /** Named EPG sources first (they win on conflicts), then EPG URLs embedded in playlists. */
    private suspend fun collectEpgTargets(): List<EpgTarget> {
        val targets = mutableListOf<EpgTarget>()
        // A guide added on its own from the same server as a playlist (often that playlist's own
        // guide link) is asked for with that playlist's user agent, like the playlist itself:
        // some servers answer a different user agent with a different (or empty) guide.
        val allPlaylists = prefs.currentPlaylists()
        fun hostOf(u: String) = runCatching { java.net.URI(u.trim()).host?.lowercase() }.getOrNull()
        prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.forEach { src ->
            val host = hostOf(src.url)
            val ua = allPlaylists.firstOrNull { pl ->
                pl.userAgent.isNotBlank() && host != null &&
                    hostOf(if (pl.isXtream) pl.xtreamBase() else pl.url) == host
            }?.userAgent.orEmpty()
            targets += EpgTarget(src.id, src.id, src.name, src.url, ua)
        }
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled && it.useEmbeddedEpg }
        for (pl in playlists) {
            val urls = mutableListOf<String>()
            if (pl.isStalker) urls += "reseq-stalker-epg://${pl.id}"
            pl.xtreamEpgUrl()?.let { urls += it }
            urls += embeddedEpgUrls[pl.id].orEmpty()
            urls.distinct().forEach { url ->
                if (targets.none { it.url == url }) {
                    targets += EpgTarget("auto_" + sha1(url), null, "${pl.name} (built-in)", url, pl.userAgent, playlistId = pl.id)
                }
            }
        }
        return targets
    }

    private val embeddedEpgUrls = HashMap<String, List<String>>()

    private suspend fun buildChannels() = withContext(BackgroundWork.quiet) {
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        val multi = playlists.size > 1
        val out = ArrayList<LiveChannel>()
        val usedKeys = HashSet<String>()
        var counter = 1
        for ((plIndex, pl) in playlists.withIndex()) {
            val file = playlistFile(pl.id)
            if (!file.exists()) continue
            setStatus(loading = true, message = "Reading channels from \"${pl.name}\"" + (if (playlists.size > 1) " (${plIndex + 1} of ${playlists.size})" else "") + "…")
            val parsed = runCatching { openMaybeGzip(file).bufferedReader().use { M3uParser.parse(it) } }
                .onFailure { Log.w(TAG, "Parse ${pl.name} failed", it) }
                .getOrNull() ?: continue
            embeddedEpgUrls[pl.id] = parsed.epgUrls
            for (e in parsed.entries) {
                // Many providers' M3U links ("m3u_plus") also list every movie and episode,
                // often 100,000+ entries. Those aren't TV channels, and loading them all into the
                // guide used enough memory to crash Live TV on some devices.
                if (isVodEntry(e.url)) continue
                val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
                val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
                var key = baseKey
                var n = 2
                while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
                val number = e.chno ?: counter
                counter = maxOf(counter, number) + 1
                val headers = if (pl.userAgent.isNotBlank() && "User-Agent" !in e.headers) {
                    e.headers + ("User-Agent" to pl.userAgent)
                } else e.headers
                out += LiveChannel(
                    key = key,
                    sourceId = pl.id,
                    sourceName = pl.name,
                    name = e.name,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    logo = e.logo,
                    groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                    // Names stay exactly as the playlist has them (the playlist is shown, if you want
                    // it, with "Group by playlist").
                    group = groupTitle,
                    number = number,
                    url = e.url,
                    headers = headers,
                    catchup = e.catchup,
                    drm = e.drm
                )
            }
            prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = parsed.entries.size) else it } }
        }
        _channels.value = out
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) {
        val t0 = System.currentTimeMillis()
        BackgroundWork.guideBuilding.value = true
        try { BackgroundWork.heavy { buildProgramsInner(settings, targets) } } finally { BackgroundWork.guideBuilding.value = false }
        LIVE_REPORT.add("Guide matched and stored: ${_programs.value.size} channels in ${System.currentTimeMillis() - t0} ms")
    }

    private suspend fun buildProgramsInner(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(BackgroundWork.quiet) {
        val all = _channels.value
        if (all.isEmpty()) {
            _programs.value = emptyMap()
            return@withContext
        }
        // Channels no profile shows (hidden, or in a hidden group, in every profile) get no
        // listings: nobody sees them, and big providers' hidden groups made the guide database
        // many times bigger than what's watched. Showing them again reads their listings back.
        val hidden = prefs.currentHiddenEverywhere()
        val skipped = HashSet<String>()
        val channels = all.filter { c -> (!hidden.hides(c.key, c.groupId)).also { shown -> if (!shown) skipped += c.key } }
        LIVE_REPORT.add(
            "Guide matching: ${channels.size} of ${all.size} channels shown somewhere" +
                " (${channels.count { !it.tvgId.isNullOrBlank() }} with a tvg-id)"
        )
        // Which playlist the channels come from (so "171,518 channels" can be told apart: one huge
        // playlist, or several), and how full the app's memory is to begin with.
        run {
            val counts = all.groupingBy { it.sourceId }.eachCount()
            LIVE_REPORT.add("Playlists: " + counts.entries.sortedByDescending { it.value }.joinToString { "${it.key.take(12)} ${it.value}" })
            val rt = Runtime.getRuntime()
            LIVE_REPORT.add("Memory before matching: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} of ${rt.maxMemory() / 1048576} MB")
        }
        val now = System.currentTimeMillis()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR

        // Hand-picked guides (long-press > Change EPG) win over automatic matching.
        val overrides = prefs.userState.first().epgOverrides
            .filterKeys { key -> channels.any { it.key == key } }

        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byName = HashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            if (c.key in overrides) return@forEach
            c.tvgId?.lowercase()?.let { byTvgId.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.tvgName ?: "").takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            // The tvg-id read as a name ("GloboSP.br" ~ a guide channel called "Globo SP").
            c.tvgId?.let { idName(it) }?.takeIf { it.length >= 3 }?.let { k ->
                val l = byName.getOrPut(k) { ArrayList() }
                if (c !in l) l.add(c)
            }
        }

        // Every guide is read; each channel keeps its best candidate:
        //   1. real listings beat placeholder listings ("Programming", "No information", …)
        //   2. a tvg-id match beats a name match
        //   3. then the earlier guide in the list
        // so a guide full of placeholders can't hide real listings from another guide.
        data class Candidate(val score: Long, val sourceId: String, val xmltvId: String)
        val best = HashMap<String, Candidate>()
        val pinned = HashMap<String, List<EpgProgram>>()
        val sourceLists = ArrayList<EpgSourceChannels>()
        val sourceMeta = ArrayList<Triple<EpgTarget, String, List<EpgChannelEntry>>>()
        // The hours shown first, kept as they're read (short form): the rest of each guide goes
        // straight to the database instead of into memory.
        val (winFrom, winTo) = defaultWindow()
        val windowCopies = HashMap<String, List<EpgProgram>>()
        fun keepWindow(src: String, xid: String, list: List<EpgProgram>) {
            val w = list.filter { it.stopMs > winFrom && it.startMs < winTo }
                .map { EpgProgram(startMs = it.startMs, stopMs = it.stopMs, title = it.title, episode = it.episode, icon = it.icon) }
            if (w.isEmpty()) return
            val k = "$src\u0000$xid"
            windowCopies[k] = windowCopies[k].orEmpty() + w
        }
        // What a guide's stored copy depends on besides the file: the channels it was matched for,
        // hand-picked guides and the hours kept. Unchanged, a guide file isn't read again.
        val matchStamp = run {
            var h = channels.size.toLong()
            for (c in channels) h = h * 31 + c.key.hashCode() * 7L + (c.tvgId?.hashCode() ?: 0) * 3L + (c.tvgName?.hashCode() ?: 0) + c.name.hashCode()
            "$h|${overrides.hashCode()}|${settings.epgPastHours}|${settings.epgFutureDays}"
        }

        for ((order, target) in targets.withIndex()) {
            val file = epgFile(target.fileId)
            if (!file.exists()) { LIVE_REPORT.add("Guide \"${target.name}\": no file downloaded yet"); continue }
            val src = target.fileId
            val stageStamp = targetStamp(target) + "|" + matchStamp
            // Reusing a guide read earlier (when its file hasn't changed) is switched off for now:
            // it meant keeping every guide's copy, about twice the storage.
            val reuse = false
            val step = if (targets.size > 1) " (${order + 1} of ${targets.size})" else ""
            val pinnedHere = overrides.filterValues { it.sourceId == target.fileId }
            val idHits = HashMap<String, MutableSet<LiveChannel>>()
            val nameHits = HashMap<String, MutableSet<LiveChannel>>()
            fun matchList(xmlChannels: Collection<Pair<String, List<String>>>) {
                xmlChannels.forEach { (id, names) ->
                    byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    (names.map { normalize(it) } + normalize(id) + idName(id)).distinct().forEach { k ->
                        byName[k]?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    }
                }
            }
            val entries: List<EpgChannelEntry>
            val meta: Map<String, EpgDatabase.StagedMeta>
            if (reuse) {
                val staged = runCatching { guideDb.stagedChannels(src) to guideDb.stagedMeta(src) }.getOrNull()
                if (staged == null) { LIVE_REPORT.add("Guide \"${target.name}\": stored copy couldn't be read"); continue }
                entries = staged.first.map { (id, names, icon) -> EpgChannelEntry(id, names, icon) }
                meta = staged.second
                if (entries.any { it.names.isNotEmpty() }) matchList(entries.map { it.id to it.names })
                LIVE_REPORT.add("Guide \"${target.name}\": file unchanged, used the copy read before (${meta.size} channels)")
            } else {
                // Memory nearly full (a huge playlist): reading another guide would freeze the
                // app, so it's left for later and the report says so.
                if (order > 0) {
                    val rt = Runtime.getRuntime()
                    fun used() = rt.totalMemory() - rt.freeMemory()
                    if (used() > rt.maxMemory() * 0.85) System.gc()
                    if (used() > rt.maxMemory() * 0.85) {
                        LIVE_REPORT.add("Guide \"${target.name}\" skipped: memory nearly full (${used() / 1048576} of ${rt.maxMemory() / 1048576} MB)")
                        setStatus(loading = true, message = "Not enough memory for guide \"${target.name}\" — skipped")
                        continue
                    }
                }
                // How far through each guide file it is, so a big guide doesn't look frozen.
                val fileSize = file.length().coerceAtLeast(1L)
                var shownAt = 0L
                val showProgress: (Long) -> Unit = { done ->
                    val now = System.currentTimeMillis()
                    if (now - shownAt > 750) {
                        shownAt = now
                        setStatus(loading = true, message = "Reading guide \"${target.name}\"$step… ${(done * 100 / fileSize).coerceIn(0, 99)}%")
                    }
                }
                val readStart = System.currentTimeMillis()
                val writer = runCatching { guideDb.startStage(src) }
                    .onFailure { Log.w(TAG, "Couldn't start storing ${target.name}", it) }.getOrNull()
                if (writer == null) { LIVE_REPORT.add("Guide \"${target.name}\": couldn't be stored"); continue }
                val parsed = runCatching {
                    openMaybeGzip(file, showProgress).use { input ->
                        XmltvParser.parse(
                            input, windowStart, windowEnd,
                            // No channel list in this guide: keep only shows for channels the matching
                            // below can use (the same id / name test) or picked by hand.
                            keepUnlisted = { id ->
                                byTvgId.containsKey(id.lowercase()) || byName.containsKey(normalize(id)) || byName.containsKey(idName(id)) ||
                                    pinnedHere.values.any { it.xmltvId == id }
                            },
                            // Each channel's shows go to the database as soon as they're read.
                            onPrograms = { xid, list ->
                                val cleaned = cleanListings(list)
                                writer.add(xid, cleaned) { isPlaceholder(it) }
                                keepWindow(src, xid, cleaned)
                            },
                            selectChannels = { xmlChannels ->
                                matchList(xmlChannels.values.map { it.id to it.displayNames })
                                if (xmlChannels.isEmpty()) null
                                else idHits.keys + nameHits.keys + pinnedHere.values.map { it.xmltvId }
                            }
                        )
                    }
                }.onFailure {
                    writer.abort()
                    Log.w(TAG, "EPG parse ${target.name} failed", it)
                    val start = runCatching {
                        openMaybeGzip(file).use { inp -> String(inp.readNBytesCompat(120), Charsets.UTF_8) }
                    }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
                    LIVE_REPORT.add("Guide \"${target.name}\" couldn't be read (${it.message?.take(140) ?: it.javaClass.simpleName}); ${file.length() / 1024} KB starting \"$start\"")
                }.getOrNull() ?: continue
                parsed.error?.let { err ->
                    LIVE_REPORT.add("Guide \"${target.name}\" is broken at $err. Kept what was read before that.")
                }
                entries = if (parsed.channels.isNotEmpty()) {
                    parsed.channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
                } else {
                    // No channel list: every channel the shows mention (kept or not), for Change EPG.
                    parsed.programChannelIds.map { EpgChannelEntry(it, emptyList(), null) }
                }
                meta = runCatching { writer.finish(stageStamp, entries.map { Triple(it.id, it.names, it.icon) }) }
                    .onFailure { writer.abort(); Log.w(TAG, "Couldn't store ${target.name}", it) }
                    .getOrNull() ?: run { LIVE_REPORT.add("Guide \"${target.name}\": couldn't be stored"); continue }
                LIVE_REPORT.add("Guide \"${target.name}\": read and set aside in ${(System.currentTimeMillis() - readStart) / 1000} s (${meta.values.sumOf { it.count }} listings)")
            }
            // A reused guide's window copies come from its stored copy.
            if (reuse) runCatching {
                guideDb.stagedWindow(src, winFrom, winTo).forEach { (xid, list) -> keepWindow(src, xid, list) }
            }

            setStatus(loading = true, message = "Matching guide \"${target.name}\"$step…")
            val label = runCatching { java.net.URI(target.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: target.name
            sourceMeta += Triple(target, label, entries.sortedBy { it.displayName.lowercase() })
            // How far ahead this guide's listings actually go (the file's own last show), so a
            // guide that stops early can be told apart from the app cutting it short.
            run {
                val lastStop = meta.values.maxOfOrNull { it.lastStop } ?: 0L
                // A guide whose listings have all run out: the next download asks for the whole
                // file (not "only if changed"), in case a cache keeps answering "not changed".
                if (lastStop in 1 until System.currentTimeMillis()) {
                    runCatching { File(file.parentFile, file.name + ".meta").delete() }
                }
                if (lastStop > 0) {
                    val fmt = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault())
                    val hours = ((lastStop - System.currentTimeMillis()) / HOUR).coerceAtLeast(0)
                    LIVE_REPORT.add(
                        "Guide \"${target.name}\": listings until ${fmt.format(java.util.Date(lastStop))} " +
                            "(about $hours hours ahead; set to ${settings.epgFutureDays} days)"
                    )
                }
            }

            if (pinnedHere.isNotEmpty()) {
                val lists = runCatching { guideDb.stagedProgramsFor(src, pinnedHere.values.map { it.xmltvId }) }.getOrDefault(emptyMap())
                pinnedHere.forEach { (key, a) -> pinned[key] = lists[a.xmltvId].orEmpty() }
            }

            if (idHits.isEmpty() && nameHits.isEmpty()) {
                // Guide had no <channel> list before programs: match program channel ids directly.
                meta.keys.forEach { id ->
                    byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    (byName[normalize(id)] ?: byName[idName(id)])?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                }
            }
            var count = 0
            fun offer(xmlId: String, chans: Set<LiveChannel>, idMatch: Boolean) {
                val m = meta[xmlId] ?: return
                if (m.count <= 0) return
                count += m.count
                val real = m.placeholders * 10 < m.count * 8
                // Higher is better; then more days ahead, then the guide's position.
                val score = guideRank(real, idMatch, m.lastStop, order)
                chans.forEach { c ->
                    val cur = best[c.key]
                    if (cur == null || score > cur.score) best[c.key] = Candidate(score, target.fileId, xmlId)
                }
            }
            idHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = true) }
            nameHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = false) }
            // What this guide matched, and when it's nothing, a few ids from each side, so a
            // "0 programs matched" can be told apart: ids that don't line up, a guide with no
            // listings for these days, or no channels left to match.
            run {
                val rt = Runtime.getRuntime()
                LIVE_REPORT.add("  memory after reading \"${target.name}\": ${(rt.totalMemory() - rt.freeMemory()) / 1048576} of ${rt.maxMemory() / 1048576} MB")
            }
            val matchedChannels = (idHits.values.flatten() + nameHits.values.flatten()).mapTo(HashSet()) { it.key }.size
            LIVE_REPORT.add(
                "Guide \"${target.name}\": ${entries.size} guide channels, ${meta.size} with listings kept; " +
                    "matched ${idHits.size} by id, ${nameHits.size} by name → $matchedChannels channels, $count listings"
            )
            // Most of the guide left unmatched: a few names from each side, so it's clear why.
            if (count > 0 && entries.size >= 20 && idHits.size + nameHits.size < entries.size / 5) {
                val hit = idHits.keys + nameHits.keys
                val guideSide = entries.filter { it.id !in hit }.take(4).joinToString { e -> e.id + (e.names.firstOrNull()?.let { " ($it)" } ?: "") }
                val ourSide = channels.filter { it.tvgId?.isNotBlank() == true }.take(4).joinToString { "${it.tvgId} (${it.name})" }
                LIVE_REPORT.add("Guide \"${target.name}\" mostly unmatched: guide has [$guideSide], playlist has [$ourSide]")
            }
            if (count == 0) {
                val guideIds = entries.take(3).joinToString { it.id }
                val ourIds = channels.mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }.take(3).joinToString()
                val totalListings = meta.values.sumOf { it.count }
                LIVE_REPORT.add(
                    "Guide \"${target.name}\" matched nothing: guide ids like [$guideIds], playlist tvg-ids like [$ourIds], " +
                        "$totalListings listings in the guide between ${settings.epgPastHours} h ago and ${settings.epgFutureDays} days ahead"
                )
            }
            if (target.sourceId != null) {
                prefs.updateEpgSources { l -> l.map { if (it.id == target.sourceId) it.copy(programCount = count) else it } }
            }
        }

        val auto = HashMap<String, EpgAssignment>()
        best.forEach { (key, c) -> auto[key] = EpgAssignment(c.sourceId, c.xmltvId) }
        // Unassigned in Assign EPG: no guide for these channels, automatic match included.
        val unassigned = overrides.filterValues { it.isNone }.keys
        unassigned.forEach { key -> auto.remove(key); pinned.remove(key) }
        // Which guide channels ended up feeding a playlist channel (for "Unassigned" in Assign EPG).
        val usedBySource = HashMap<String, MutableSet<String>>()
        auto.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        overrides.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        sourceMeta.forEach { (target, label, entries) ->
            sourceLists += EpgSourceChannels(target.fileId, target.name, label, entries, usedBySource[target.fileId].orEmpty())
        }
        _autoMatches.value = auto
        _epgSources.value = sourceLists
        saveSourceLists(targets, sourceLists)
        // Each channel's logo in the guide it's matched to (or assigned), for playlists that
        // prefer the guide's logos, and for channels whose playlist has none.
        val iconsBySource = sourceMeta.associate { (target, _, entries) ->
            target.fileId to entries.mapNotNull { e -> e.icon?.takeIf { it.isNotBlank() }?.let { e.id to it } }.toMap()
        }
        val logos = HashMap<String, String>()
        (auto + overrides).forEach { (key, a) -> iconsBySource[a.sourceId]?.get(a.xmltvId)?.let { logos[key] = it } }
        _epgLogos.value = logos
        saveEpgLogos(logos)
        epgDetailsPending = false
        // The guide shown: from the staged guides, the chosen one for each channel (hand-picked
        // ones as given), all in one step.
        val choice = auto.filterKeys { it !in pinned }
        val storeStart = System.currentTimeMillis()
        val stored = runCatching {
            val fp = guideFingerprint(settings, targets)
            guideDb.replaceAllFromStage(fp, choice, pinned, auto, guideParts(settings, targets, fp), windowStart, windowEnd)
        }.onFailure { Log.w(TAG, "Couldn't store the guide", it) }.isSuccess
        LIVE_REPORT.add("Guide: best guide per channel stored in ${(System.currentTimeMillis() - storeStart) / 1000} s")
        // The guides' working copies aren't kept once the guide is built (they'd double the storage).
        runCatching { guideDb.dropStagesExcept(emptyList()) }
        // The hours around now, in memory (short form: details come from the database).
        val window = HashMap<String, List<EpgProgram>>()
        choice.forEach { (key, a) ->
            windowCopies["${a.sourceId}\u0000${a.xmltvId}"]?.sortedBy { it.startMs }?.let { window[key] = it }
        }
        pinned.forEach { (key, list) ->
            val w = list.filter { it.stopMs > winFrom && it.startMs < winTo }
            if (w.isNotEmpty()) window[key] = w
        }
        windowCopies.clear()
        _programs.value = window
        if (stored) {
            lightPrograms = true
            fullRangeChannels.clear(); _guideGeneration.value++
            loadedFrom = winFrom
            loadedTo = winTo
            guideInDb = true
            saveSkipped(skipped)
            if (skipped.isNotEmpty()) LIVE_REPORT.add("Guide: ${skipped.size} hidden channels left out")
            scheduleCompact()
        } else {
            // Not stored: only the hours around now are available until the next update.
            LIVE_REPORT.add("Guide couldn't be stored: only the hours around now are shown")
            guideInDb = false
            loadedFrom = Long.MIN_VALUE
            loadedTo = Long.MAX_VALUE
        }
    }

    /**
     * How good a guide's listings for a channel are, higher is better: real listings over
     * placeholders, then a tvg-id match over a name match, then the one that goes further ahead
     * (in half-days, so a few hours either way don't count), and only then the guide higher in
     * the list. Before, the guide higher in the list won even with a day of listings against
     * another guide's week, so the same provider showed "until Saturday" on one TV and "until
     * Monday" on another depending on the order of their guides.
     */
    private fun guideRank(real: Boolean, idMatch: Boolean, listings: List<EpgProgram>, order: Int): Long =
        guideRank(real, idMatch, listings.maxOfOrNull { it.stopMs } ?: 0L, order)

    private fun guideRank(real: Boolean, idMatch: Boolean, lastStop: Long, order: Int): Long {
        val halfDaysAhead = ((lastStop - System.currentTimeMillis()).coerceAtLeast(0L) / (12 * HOUR)).coerceAtMost(999L)
        return (if (real) 4_000_000L else 0L) + (if (idMatch) 2_000_000L else 0L) + halfDaysAhead * 1_000L - order
    }

    /**
     * Some guides add a long placeholder ("Programming", "No information") that overlaps the real
     * listings for the same time. Keep the real ones wherever they overlap.
     */
    private fun cleanListings(list: List<EpgProgram>): List<EpgProgram> {
        if (list.size < 2) return list
        val real = list.filter { !isPlaceholder(it.title) }
        val kept = if (real.isEmpty() || real.size == list.size) list else {
            // The real shows' time spans, merged into separate stretches, so each placeholder is
            // checked against them with a quick search (every placeholder against every real show
            // took minutes on big guides).
            val spans = ArrayList<LongArray>()
            for (r in real.sortedBy { it.startMs }) {
                val last = spans.lastOrNull()
                if (last != null && r.startMs <= last[1]) { if (r.stopMs > last[1]) last[1] = r.stopMs }
                else spans += longArrayOf(r.startMs, r.stopMs)
            }
            fun overlapsReal(p: EpgProgram): Boolean {
                var lo = 0; var hi = spans.size - 1; var idx = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (spans[mid][0] < p.stopMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
                }
                return idx >= 0 && spans[idx][1] > p.startMs
            }
            list.filter { p -> !isPlaceholder(p.title) || !overlapsReal(p) }
        }
        return resolveOverlaps(kept)
    }

    /**
     * Makes sure only one program covers any moment. When a long entry (often a "Programming"
     * filler) runs across later shows, it's cut off where the next show starts. Otherwise the
     * filler, having started earlier, is what counts as "on now" even while a real show is airing.
     */
    private fun resolveOverlaps(list: List<EpgProgram>): List<EpgProgram> {
        val sorted = list.sortedWith(compareBy<EpgProgram>({ it.startMs }, { isPlaceholder(it.title) }, { it.stopMs - it.startMs }))
        val out = ArrayList<EpgProgram>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.stopMs > p.startMs) {
                if (last.startMs == p.startMs) continue // same start: keep the one sorted first
                out[out.lastIndex] = last.copy(stopMs = p.startMs)
            }
            if (p.stopMs > p.startMs) out += p
        }
        return out
    }

    /** True when (almost) everything in a channel's listings is a placeholder. */
    private fun isPlaceholderListing(list: List<EpgProgram>): Boolean {
        val placeholders = list.count { isPlaceholder(it.title) }
        return placeholders * 10 >= list.size * 8
    }

    // Guides repeat the same few titles a million times: each is checked once.
    private val placeholderCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isPlaceholder(title: String): Boolean {
        if (placeholderCache.size > 50_000) placeholderCache.clear()
        return placeholderCache.getOrPut(title) { PLACEHOLDER_TITLES.matches(title.trim().lowercase()) }
    }

    /**
     * Assign EPG changes applied straight to the stored guide instead of reading every guide
     * file again (which took as long as a full guide update, just to change one channel):
     *  - Unassign: the channel's listings are simply dropped. Nothing is read.
     *  - A guide channel picked by hand: only that one guide file is read, for just that channel.
     *  - Back to automatic: the guides' channel lists (already in memory, the ones Assign EPG
     *    shows) say which guide channels could match; only the guides that have one are read,
     *    for just those channels, and the best is picked the same way a full update picks.
     * Only if those lists aren't loaded yet does it fall back to reading every guide.
     * [changes]: channel key -> its new assignment (null = back to automatic).
     */
    fun applyEpgChanges(changes: Map<String, EpgAssignment?>) {
        if (changes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val quick = runCatching { applyEpgChangesQuick(changes) }
                    .onFailure { Log.w(TAG, "Quick EPG change failed; full re-match", it) }
                    .getOrDefault(false)
                if (!quick) {
                    LIVE_REPORT.add("Assign EPG: guide channel lists not ready, so every guide was read")
                    setStatus(loading = true, message = "Updating guide…")
                    buildPrograms(prefs.currentSettings(), collectEpgTargets())
                }
                _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            }
        }
    }

    private suspend fun applyEpgChangesQuick(changes: Map<String, EpgAssignment?>): Boolean = withContext(Dispatchers.IO) {
        val settings = prefs.currentSettings()
        val targets = collectEpgTargets()
        val now = System.currentTimeMillis()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR
        val assigned = changes.filterValues { it != null && !it.isNone }.mapValues { it.value!! }

        // Back to automatic: possible matches, from the guides' channel lists.
        data class Cand(val order: Int, val sourceId: String, val xmltvId: String, val idMatch: Boolean)
        val autoBack = changes.filterValues { it == null }.keys
        val cands = HashMap<String, MutableList<Cand>>()
        if (autoBack.isNotEmpty()) {
            val lists = _epgSources.value
            if (lists.isEmpty()) return@withContext false
            val chans = _channels.value.filter { it.key in autoBack }
            targets.forEachIndexed { order, t ->
                val list = lists.firstOrNull { it.sourceId == t.fileId } ?: return@forEachIndexed
                chans.forEach { c ->
                    val tvg = c.tvgId?.lowercase()?.takeIf { it.isNotBlank() }
                    val names = listOfNotNull(c.tvgName, c.name).map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
                    list.channels.forEach { e ->
                        val idMatch = tvg != null && e.id.lowercase() == tvg
                        val nameMatch = !idMatch && names.isNotEmpty() && (e.names + e.id).any { normalize(it) in names }
                        if (idMatch || nameMatch) cands.getOrPut(c.key) { ArrayList() } += Cand(order, t.fileId, e.id, idMatch)
                    }
                }
            }
        }

        // Each guide file read once, for just the channels needed from it.
        val wantedBySource = HashMap<String, MutableSet<String>>()
        assigned.values.forEach { wantedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        cands.values.flatten().forEach { wantedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        val programsBySource = HashMap<String, Map<String, List<EpgProgram>>>()
        val iconsBySource = HashMap<String, Map<String, String>>()
        for ((sourceId, wanted) in wantedBySource) {
            val target = targets.firstOrNull { it.fileId == sourceId } ?: continue
            val file = epgFile(target.fileId)
            if (!file.exists()) continue
            setStatus(loading = true, message = "Reading guide \"${target.name}\"…")
            val parsed = openMaybeGzip(file).use { input ->
                XmltvParser.parse(input, windowStart, windowEnd) { wanted }
            }
            programsBySource[sourceId] = parsed.programs
            iconsBySource[sourceId] = parsed.channels.mapNotNull { (id, ch) -> ch.icon?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap()
        }

        val newPrograms = HashMap<String, List<EpgProgram>>()
        val newIcons = HashMap<String, String>()
        assigned.forEach { (key, a) ->
            newPrograms[key] = cleanListings(programsBySource[a.sourceId]?.get(a.xmltvId).orEmpty())
            iconsBySource[a.sourceId]?.get(a.xmltvId)?.let { newIcons[key] = it }
        }
        // Automatic: the same choice a full update makes (real listings first, then a tvg-id
        // match over a name match, then the guide higher in the list).
        val newAuto = HashMap<String, EpgAssignment>()
        cands.forEach { (key, list) ->
            var bestScore = Long.MIN_VALUE
            var best: Pair<Cand, List<EpgProgram>>? = null
            list.forEach { c ->
                val listings = cleanListings(programsBySource[c.sourceId]?.get(c.xmltvId).orEmpty())
                if (listings.isEmpty()) return@forEach
                val score = guideRank(!isPlaceholderListing(listings), c.idMatch, listings, c.order)
                if (score > bestScore) { bestScore = score; best = c to listings }
            }
            best?.let { (c, listings) ->
                newPrograms[key] = listings
                newAuto[key] = EpgAssignment(c.sourceId, c.xmltvId)
                iconsBySource[c.sourceId]?.get(c.xmltvId)?.let { newIcons[key] = it }
            }
        }

        val changed = changes.keys
        // Stored guide first (when the guide is kept in the database), then what's on screen.
        if (guideInDb) {
            val fp = guideFingerprint(settings, targets)
            guideDb.replaceChannels(fp, changed, newPrograms, newAuto, guideParts(settings, targets, fp))
        }
        _programs.value = HashMap(_programs.value).apply {
            changed.forEach { remove(it) }
            newPrograms.forEach { (k, l) ->
                val shown = if (guideInDb) l.filter { it.stopMs > loadedFrom && it.startMs < loadedTo } else l
                if (shown.isNotEmpty()) put(k, shown)
            }
        }
        _autoMatches.value = (_autoMatches.value - changed) + newAuto
        fullRangeChannels.removeAll(changed)
        val logos = HashMap(_epgLogos.value).apply { changed.forEach { remove(it) }; putAll(newIcons) }
        _epgLogos.value = logos
        saveEpgLogos(logos)
        // Keep the "already used" marks in Assign EPG up to date.
        val used = (assigned.values + newAuto.values).groupBy({ it.sourceId }, { it.xmltvId })
        if (used.isNotEmpty()) {
            _epgSources.value = _epgSources.value.map { s ->
                used[s.sourceId]?.let { ids -> s.copy(usedIds = s.usedIds + ids) } ?: s
            }
        }
        LIVE_REPORT.add(
            "Assign EPG: ${changed.size} channel(s) updated, " +
                (if (programsBySource.isEmpty()) "no guide read" else "read ${programsBySource.size} of ${targets.size} guide(s)")
        )
        true
    }

    fun rematchEpg() {
        scope.launch {
            mutex.withLock {
                setStatus(loading = true, message = "Updating guide…")
                buildPrograms(prefs.currentSettings(), collectEpgTargets())
                _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            }
        }
    }

    // ---------------------------------------------------------------- IO helpers

    private fun playlistFile(id: String) = File(playlistDir, "$id.m3u")
    private fun epgFile(id: String) = File(epgDir, "$id.xml")

    /**
     * Downloads [url] to [target]. Returns false when nothing changed: the server said "not
     * modified" (we send the ETag / Last-Modified from last time), or the new file is identical.
     * Unchanged files skip the re-reading and re-matching that follows a download.
     */
    private suspend fun download(
        url: String,
        target: File,
        userAgent: String,
        progressLabel: String? = null,
        /** Ask for the whole file, not "only if changed" (Update now: a cache that wrongly
         *  answers "not changed" otherwise keeps an old guide forever). */
        full: Boolean = false,
        /** Name for the Start-Up Report line saying what came back (guides only). */
        reportAs: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "No URL set" }
        target.parentFile?.mkdirs()
        val meta = File(target.parentFile, target.name + ".meta")
        val (etag, lastModified) = if (!full && target.exists() && meta.exists()) {
            meta.readLines().let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        } else "" to ""
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .apply {
                if (etag.isNotBlank()) header("If-None-Match", etag)
                if (lastModified.isNotBlank()) header("If-Modified-Since", lastModified)
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304 && target.exists()) {
                target.setLastModified(System.currentTimeMillis()) // checked again after the usual interval
                reportAs?.let { LIVE_REPORT.add("Guide \"$it\": server said not changed since last time; kept the copy we have") }
                return@withContext false
            }
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            val tmp = File(target.parentFile, target.name + ".tmp")
            // With a label: how much has come in so far, so a big download doesn't look frozen.
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { raw ->
                // Saved zipped: guide files (XMLTV) and big playlists shrink to about a tenth,
                // and plain guides of several hundred MB made the app take up close to 1 GB.
                // Ones that already arrive zipped (.xml.gz) are kept as they are.
                val input = BufferedInputStream(raw, 64 * 1024)
                input.mark(2)
                val alreadyZipped = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                val fileOut = tmp.outputStream()
                (if (alreadyZipped) fileOut else fastGzip(fileOut)).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var shownAt = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (progressLabel != null) {
                            val now = System.currentTimeMillis()
                            if (now - shownAt > 750) {
                                shownAt = now
                                setStatus(loading = true, message = "$progressLabel… " + sizeLabel(read) + (total?.let { " of " + sizeLabel(it) } ?: ""))
                            }
                        }
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                error("Empty response")
            }
            runCatching {
                meta.writeText(response.header("ETag").orEmpty() + "\n" + response.header("Last-Modified").orEmpty())
            }
            val same = target.exists() && (
                if (isGzip(target) == isGzip(tmp)) target.length() == tmp.length() && sameContent(target, tmp)
                else sameDecoded(target, tmp)
            )
            val serverDate = response.header("Last-Modified")?.let { " (server's date: $it)" }.orEmpty()
            if (same) {
                tmp.delete()
                target.setLastModified(System.currentTimeMillis())
                reportAs?.let { LIVE_REPORT.add("Guide \"$it\": downloaded ${sizeLabel(target.length())} zipped, same as the copy we had$serverDate") }
                return@withContext false
            }
            reportAs?.let { LIVE_REPORT.add("Guide \"$it\": downloaded, new copy ${sizeLabel(tmp.length())} zipped$serverDate") }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            markChanged(target)
            true
        }
    }

    /**
     * When a downloaded file last really changed. The saved channel list and guide are checked
     * against this, not the file's date: the date is also bumped every time an update finds
     * nothing new, which used to make every restart re-read all playlists and guides.
     */
    private fun stampFile(f: File) = File(f.parentFile, f.name + ".stamp")

    private fun contentStamp(f: File): String =
        runCatching { stampFile(f).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "${f.length()}"

    private fun markChanged(f: File) {
        runCatching { stampFile(f).writeText(System.currentTimeMillis().toString()) }
    }

    private fun isGzip(f: File): Boolean = runCatching {
        f.inputStream().use { val a = it.read(); val b = it.read(); a == 0x1f && b == 0x8b }
    }.getOrDefault(false)

    /**
     * Same content once unzipped. A saved copy is zipped to save space after each update, so a
     * fresh plain download never matched it byte for byte: a playlist that hadn't changed at all
     * was reported as "new content" at every start, redoing every guide and the Sports search.
     */
    private fun sameDecoded(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("MD5")
            openMaybeGzip(f).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

    private fun sameContent(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("MD5")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

    // ---------------------------------------------------------------- Xtream Codes API

    /**
     * Loads an Xtream login through the Xtream API (player_api.php), the way TiviMate does:
     * account check, categories, then live streams, written out as an M3U file so the rest of
     * Live TV treats it like any playlist. Much lighter for the provider than the one-shot
     * get.php playlist, which many panels time out on (HTTP 502) or switch off. Falls back to
     * get.php if the API isn't available.
     */
    private suspend fun downloadXtream(pl: PlaylistSource, target: File) = withContext(Dispatchers.IO) {
        require(pl.xtreamBase().isNotBlank()) { "No server set" }
        require(pl.xtreamUsername.isNotBlank() && pl.xtreamPassword.isNotBlank()) { "Username and password are required" }
        val api = pl.xtreamApiUrl()
        val apiResult = runCatching {
            // 1. Account
            val info = JSONObject(getText(api, pl.userAgent))
            // Which stream formats this account may use (some panels turn off .ts and only
            // allow .m3u8; asking for .ts then fails on every channel with "bad http status").
            allowedFormats = info.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).lowercase() }.toSet()
            } ?: emptySet()
            // Catch-up links are in the server's local time. When the server's time zone differs
            // from the TV's (say the server runs on UTC and the TV is in London in summer),
            // every replay started an hour off. Remember the server's zone for catch-up.
            info.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }?.let { tz ->
                if (tz != pl.serverTimezone) {
                    prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
                }
            }
            info.optJSONObject("user_info")?.let { ui ->
                accountOf(ui)?.let { saveAccount(pl, it) }
                if (ui.optString("auth") == "0") throw XtreamLoginError("Login failed: check the username and password")
                val status = ui.optString("status")
                if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
                    throw XtreamLoginError("Account $status")
                }
                ui.optString("exp_date").toLongOrNull()?.let { exp ->
                    if (exp > 0 && exp * 1000 < System.currentTimeMillis()) {
                        val date = java.text.DateFormat.getDateInstance().format(java.util.Date(exp * 1000))
                        throw XtreamLoginError("Account expired on $date")
                    }
                }
            } ?: if (info.has("user_info")) Unit else error("Not an Xtream API response")

            // 2. Categories
            val categories = HashMap<String, String>()
            runCatching {
                val arr = org.json.JSONArray(getText("$api&action=get_live_categories", pl.userAgent))
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    categories[c.optString("category_id")] = c.optString("category_name")
                }
            }

            // 3. Live streams, streamed straight into an M3U file
            writeXtreamPlaylist(pl, "$api&action=get_live_streams", categories, target)
        }
        val error = apiResult.exceptionOrNull() ?: return@withContext
        if (error is XtreamLoginError) throw error
        Log.w(TAG, "Xtream API failed for ${pl.name}, trying get.php: ${error.message}")
        // Fallback: the classic one-shot playlist. If that fails too, report the API error.
        runCatching { download(pl.resolvedUrl(), target, pl.userAgent) }
            .onFailure { throw IllegalStateException(error.message ?: it.message) }
    }

    private class XtreamLoginError(message: String) : Exception(message)

    /** Stream formats the provider allows for the account being loaded (from user_info). */
    @Volatile private var allowedFormats: Set<String> = emptySet()

    private fun xtreamExtension(pl: PlaylistSource): String =
        xtreamLiveExtension(pl.streamFormat, allowedFormats)

    private fun writeXtreamPlaylist(pl: PlaylistSource, url: String, categories: Map<String, String>, target: File) {
        val ext = xtreamExtension(pl)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val base = pl.xtreamBase()
        val user = java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8")
        val pass = java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8")
        var count = 0
        val request = Request.Builder().url(url).header("User-Agent", pl.userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            // Written zipped straight away (like downloads), instead of saved plain and zipped
            // again afterwards, which read and wrote the whole list a second time.
            fastGzip(tmp.outputStream()).bufferedWriter().use { out ->
                out.write("#EXTM3U\n")
                android.util.JsonReader(body.charStream()).use { reader ->
                    // Most panels send a list; some send the channels keyed by number instead
                    // ({"0": {...}, "1": {...}}), which failed with "Expected BEGIN_ARRAY but was
                    // BEGIN_OBJECT". Both are read.
                    val keyed = reader.peek() == android.util.JsonToken.BEGIN_OBJECT
                    if (keyed) reader.beginObject() else reader.beginArray()
                    while (reader.hasNext()) {
                        if (keyed) {
                            reader.nextName()
                            if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                        }
                        val f = HashMap<String, String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            when (reader.peek()) {
                                android.util.JsonToken.STRING -> f[name] = reader.nextString()
                                android.util.JsonToken.NUMBER -> f[name] = reader.nextString()
                                android.util.JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val streamId = f["stream_id"]
                        if (streamId.isNullOrBlank()) continue
                        val title = (f["name"] ?: "Channel $streamId").replace("\n", " ").trim()
                        val attrs = buildString {
                            fun attr(k: String, v: String?) {
                                if (!v.isNullOrBlank()) append(" $k=\"${v.replace("\"", "'")}\"")
                            }
                            attr("tvg-id", f["epg_channel_id"])
                            attr("tvg-name", title)
                            attr("tvg-logo", f["stream_icon"])
                            attr("group-title", categories[f["category_id"]] ?: "Uncategorized")
                            attr("tvg-chno", f["num"])
                            if (f["tv_archive"] == "1") {
                                attr("catchup", "xc")
                                attr("catchup-days", f["tv_archive_duration"]?.takeIf { it != "0" } ?: "1")
                            }
                        }
                        out.write("#EXTINF:-1$attrs,$title\n")
                        out.write("$base/live/$user/$pass/$streamId.$ext\n")
                        count++
                        if (count % 500 == 0) setStatus(loading = true, message = "Loading channels from ${pl.name}… ${"%,d".format(count)}")
                    }
                    if (keyed) reader.endObject() else reader.endArray()
                }
            }
        }
        if (count == 0) {
            tmp.delete()
            error("The provider returned no live channels")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Playlists whose server time zone was already looked up this session. */
    private val zoneTried = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * M3U playlists from Xtream servers: the server's time zone, learned from the server the
     * channel links point to (its player_api.php, with the username and password in the link),
     * once. Catch-up links are in server time; without it, replays could start hours off or be
     * refused. Xtream logins already learn it when they load.
     */
    suspend fun learnServerZone(channel: LiveChannel): String? = withContext(Dispatchers.IO) {
        val pl = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId } ?: return@withContext null
        pl.serverTimezone.takeIf { it.isNotBlank() }?.let { return@withContext it }
        if (!zoneTried.add(pl.id)) return@withContext null
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)
            .find(channel.url) ?: return@withContext null
        val (host, user, pass) = m.destructured
        val tz = runCatching {
            val url = "$host/player_api.php?username=" + java.net.URLEncoder.encode(user, "UTF-8") +
                "&password=" + java.net.URLEncoder.encode(pass, "UTF-8")
            JSONObject(getText(url, pl.userAgent.ifBlank { DEFAULT_UA }))
                .optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        LIVE_REPORT.add(if (tz != null) "Server time zone for \"${pl.name}\": $tz" else "Server time zone for \"${pl.name}\": unknown")
        if (tz != null) prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
        tz
    }

    /** Xtream user_info → the account details worth showing. */
    private fun accountOf(ui: JSONObject): PlaylistAccount? {
        if (ui.optString("auth") == "0") return null
        val exp = ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
        return PlaylistAccount(
            expiresAtMs = exp,
            status = ui.optString("status"),
            maxConnections = ui.optString("max_connections").toIntOrNull() ?: 0,
            activeConnections = ui.optString("active_cons").toIntOrNull() ?: 0,
            checkedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun saveAccount(pl: PlaylistSource, a: PlaylistAccount) {
        prefs.setPlaylistAccount(pl.id, a)
        a.expiryLabel()?.let { LIVE_REPORT.add("Account for \"${pl.name}\": expires $it") }
        warnIfExpiring(pl.id, pl.name, a)
    }

    private val expiryWarned = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun warnIfExpiring(id: String, name: String, a: PlaylistAccount) {
        if (!a.expiringSoon() || !expiryWarned.add(id)) return
        val days = a.daysLeft() ?: return
        com.nuvio.tv.livetv.record.LiveTvNotice.show(
            when {
                a.expiresAtMs <= System.currentTimeMillis() -> "\"$name\" has expired"
                days == 0L -> "\"$name\" expires today"
                days == 1L -> "\"$name\" expires tomorrow"
                else -> "\"$name\" expires in $days days"
            }
        )
    }

    /**
     * An M3U link from an Xtream provider (…/get.php?username=…&password=…): the same server's
     * player_api.php has the account's expiry date and connections.
     */
    private suspend fun refreshM3uAccount(pl: PlaylistSource) = withContext(Dispatchers.IO) {
        val api = m3uAccountUrl(pl.url) ?: return@withContext
        runCatching {
            val ui = JSONObject(getText(api, pl.userAgent.ifBlank { DEFAULT_UA })).optJSONObject("user_info")
                ?: return@runCatching
            accountOf(ui)?.let { saveAccount(pl, it) }
        }
    }

    private fun m3uAccountUrl(url: String): String? = runCatching {
        val u = java.net.URI(url.trim())
        if (u.path?.endsWith("get.php", ignoreCase = true) != true) return@runCatching null
        val q = (u.rawQuery ?: return@runCatching null).split('&')
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val user = q["username"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val pass = q["password"] ?: return@runCatching null
        "${u.scheme}://${u.rawAuthority}/player_api.php?username=$user&password=$pass"
    }.getOrNull()

    private fun getText(url: String, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            return response.body?.string() ?: error("Empty response")
        }
    }

    /** Plain-English reasons for the HTTP errors people actually hit. */
    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "HTTP $code: the provider refused the request (wrong login, or it blocks this app; try setting a user agent)"
        404 -> "HTTP 404: nothing at that address (check the server, port and link)"
        429 -> "HTTP 429: too many requests, try again in a few minutes"
        // Not standard codes: Xtream panels use these when an account is over its connection
        // limit, expired, or blocked for the moment.
        456, 458, 461 -> "HTTP $code: the provider refused this login for now (too many connections, expired, or blocked). Close other apps using it and try again later."
        502, 503, 504, 520, 521, 522, 523, 524 -> "HTTP $code: the provider's server didn't respond, try again later"
        884 -> "HTTP 884: the provider blocked this app (try setting a user agent)"
        else -> "HTTP $code"
    }

    /** A zip stream at the fastest setting: a TV box's processor matters more than the last few MB. */
    private fun fastGzip(out: java.io.OutputStream): java.io.OutputStream =
        object : java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(out, 64 * 1024), 64 * 1024) {
            init { def.setLevel(java.util.zip.Deflater.BEST_SPEED) }
        }

    /**
     * Zips a saved guide or playlist that isn't zipped yet. Its "last changed" stamp is kept, so
     * this doesn't count as new content (the saved guide is read again once, as its size changed).
     */
    private fun zipInPlace(file: File) {
        runCatching {
            if (!file.exists() || file.length() < 1024 * 1024) return
            val zipped = file.inputStream().use { val a = it.read(); val b = it.read(); a == 0x1f && b == 0x8b }
            if (zipped) return
            val tmp = File(file.parentFile, file.name + ".zip.tmp")
            file.inputStream().use { input -> fastGzip(tmp.outputStream()).use { out -> input.copyTo(out, 64 * 1024) } }
            val modified = file.lastModified()
            if (!stampFile(file).exists()) markChanged(file)
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            file.setLastModified(modified)
            LIVE_REPORT.add("Saved \"${file.name}\" zipped to save space")
        }.onFailure { Log.w(TAG, "Couldn't zip ${file.name}", it) }
    }

    private fun openMaybeGzip(file: File, onProgress: ((Long) -> Unit)? = null): InputStream {
        val raw: InputStream = if (onProgress == null) file.inputStream() else ProgressInputStream(file.inputStream(), onProgress)
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input, 64 * 1024)) else input
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    /** Counts what's been read from a file, for "Reading guide… 45%". */
    private class ProgressInputStream(inner: InputStream, private val onProgress: (Long) -> Unit) : java.io.FilterInputStream(inner) {
        private var done = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { done++; onProgress(done) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { done += it; onProgress(done) } }
        override fun skip(n: Long): Long = super.skip(n).also { done += it }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        private const val TAG = "LiveTvRepository"
        private const val HOUR = 60L * 60L * 1000L
        /** How often to look for playlists and guides past their update time while the app is open. */
        private const val STALE_CHECK_MS = 30L * 60L * 1000L
        /** Extra guide hours read ahead, so the window moves along a few times a day, not constantly. */
        /** The most channels of one group whose listings are loaded for every day ahead. */
        private const val MAX_FULL_RANGE_CHANNELS = 400
        // Hours read ahead of what's asked for (each read only adds the hours not loaded yet).
        private const val RANGE_SLACK_MS = 6 * HOUR
        // 2: guides compared by how far ahead they go (matched again once after updating).
        private const val GUIDE_CACHE_VERSION = 2
        private const val CHANNELS_CACHE_VERSION = 6
        private const val MAX_SAVED_LISTINGS = 300_000
        /** Titles guides use when they have no real listing. */
        private val PLACEHOLDER_TITLES = Regex(
            """(programming|program|programme|no information|no info|no program information|no programme information|""" +
                """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
                """regular programming|scheduled programming|paid programming|coming soon|no program guide available)\.?"""
        )
        private val VOD_PATH = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
        private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)

        /** A movie or episode in an M3U (by its link), not a live channel. */
        fun isVodEntry(url: String): Boolean = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

        /** "Programming", "No information", "To Be Announced"… (no real show to show a poster for). */
        fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLES.matches(title.trim().lowercase())

        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val qualityTokens = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
        private val prefixToken = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val bracketed = Regex("""\[.*?]|\(.*?\)""")

        private const val SOURCE_LISTS_VERSION = 1

        private val countryEnd = Regex("""(\.(com|co|net|tv))?\.[a-z]{2}$""", RegexOption.IGNORE_CASE)

        /** A guide or tvg-id as a name: "GloboSP.br" -> "globosp". */
        internal fun idName(id: String): String = normalize(id.trim().replace(countryEnd, ""))

        internal fun normalize(name: String): String {
            var n = name.lowercase().trim()
            n = n.replace(prefixToken, "")
            n = n.replace(bracketed, " ")
            n = n.replace(qualityTokens, " ")
            return n.filter { it.isLetterOrDigit() }
        }
    }
}

/** Up to [n] bytes from the start of a stream (InputStream.readNBytes needs Android 13). */
private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var total = 0
    while (total < n) {
        val r = read(buf, total, n - total)
        if (r < 0) break
        total += r
    }
    return buf.copyOf(total)
}
