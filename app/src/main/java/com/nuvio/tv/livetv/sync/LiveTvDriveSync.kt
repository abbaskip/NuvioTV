package com.nuvio.tv.livetv.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings screen shows. */
data class DriveSyncState(
    val available: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    val autoSync: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L,
    /** Set while signing in: show this code and address to the user. */
    val signIn: SignInPrompt? = null
)

/** [qrUrl] is what the QR code opens: a link with the code filled in if Google gives one. */
data class SignInPrompt(val userCode: String, val url: String, val qrUrl: String, val expiresAtMs: Long)

/**
 * Syncs the Live TV setup through the user's own Google Drive.
 *
 * Uses the private app folder when Google permits it, otherwise an app-created private Drive
 * file (drive.file). Backups contain playlist credentials and are never shared by the app. Sign-in uses Google's
 * "TVs and limited input devices" flow: the TV shows a code, the user approves on their phone.
 * Changes use a three-way merge; conflicting edits stop sync until explicitly resolved.
 * Restore is explicit. Automatic sync never replaces a setup based only on device clocks.
 */
@Singleton
class LiveTvDriveSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val repository: dagger.Lazy<LiveTvRepository>,
    private val orders: com.nuvio.tv.data.tvdb.TvdbOrderStore,
    private val profiles: com.nuvio.tv.data.local.ProfileDataStore,
    private val shuffle: com.nuvio.tv.data.local.EpisodeShuffleStore,
    private val addonPreferences: com.nuvio.tv.data.local.AddonPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
    private val protected = com.nuvio.tv.core.profile.ProtectedProfilePreferences(
        context, "reseq_drive_credentials", "com.nuvio.tv.drive.credentials.v1")
    private val mutex = Mutex()
    private data class RemoteBackup(val json: JSONObject, val etag: String?)

    private fun refreshToken(): String? {
        protected.read(1)?.let { return it }
        return store.getString(KEY_REFRESH, null)?.also {
            protected.write(1, it)
            check(store.edit().remove(KEY_REFRESH).commit())
        }
    }

    private suspend fun exportSetup(): JSONObject = prefs.exportForSync().also { data ->
        data.put(TVDB_VERSION, JSONObject().put("t", "i").put("v", 1))
        data.put(SHUFFLE_VERSION, JSONObject().put("t", "i").put("v", 1))
        data.put(SOURCES_VERSION, JSONObject().put("t", "i").put("v", 1))
        profiles.profilesList.first().forEach { profile ->
            data.put("$PROFILE_PREFIX${profile.id}", JSONObject().put("t", "str").put("v", profile.name))
            addonPreferences.exportStreamSourceSelections(profile.id).forEach { (key, value) ->
                data.put("$SOURCES_PREFIX${profile.id}:$key", JSONObject().put("t", "str").put("v", value))
            }
            shuffle.exportSelections(profile.id).forEach { (key, value) ->
                data.put("$SHUFFLE_PREFIX${profile.id}:$key", JSONObject().put("t", "str").put("v", value))
            }
        }
        for (id in 1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES) {
            orders.exportSelections(id).forEach { (key, value) ->
                data.put("$TVDB_PREFIX$id:$key", JSONObject().put("t", "str").put("v", value))
            }
        }
    }

    private fun hasSetup(data: JSONObject?) = prefs.syncHasSources(data) ||
        data?.keys()?.asSequence()?.any { it.startsWith(SHUFFLE_PREFIX) || it.startsWith(SOURCES_PREFIX) } == true ||
        data?.keys()?.asSequence()?.any { it.startsWith(TVDB_PREFIX) && (":order:" in it || ":reset:" in it) } == true

    private fun liveData(data: JSONObject) = JSONObject(data.toString()).also { out ->
        out.keys().asSequence().filter { it == TVDB_VERSION || it == SHUFFLE_VERSION || it == SOURCES_VERSION || it.startsWith(SOURCES_PREFIX) || it.startsWith(TVDB_PREFIX) || it.startsWith(SHUFFLE_PREFIX) || it.startsWith(PROFILE_PREFIX) }.toList().forEach(out::remove)
    }

    private val http = OkHttpClient.Builder()
        // IPv4 first, like Nuvio's own connections: many VPNs (and some routers) leave IPv6
        // half working, and each Google address then hung until it timed out.
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientId = BuildConfig.GOOGLE_DRIVE_CLIENT_ID
    private val clientSecret = BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DriveSyncState> = _state.asStateFlow()

    private var started = false
    private var signInJob: Job? = null
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    /** True while a restored setup is being written, so it isn't backed up straight back. */
    @Volatile private var applying = false
    /** When a restore or sync last wrote this TV's setup (its own changes come after that). */
    @Volatile private var appliedAtMs = 0L

    /** The copy in Google Drive: when it was saved, on which TV, and whether that was this one. */
    data class BackupInfo(val savedAtMs: Long, val device: String, val fromThisTv: Boolean)

    /** About the saved copy, for the restore prompt (null: none, or Drive couldn't be reached). */
    suspend fun backupInfo(): BackupInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val token = token() ?: return@runCatching null
            val fileId = fileId(token) ?: return@runCatching null
            val json = JSONObject(execute(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v2/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            ))
            BackupInfo(json.optLong("savedAt"), json.optString("device").ifBlank { "another TV" }, json.optString("deviceId") == deviceId)
        }.getOrNull()
    }

    /** When this TV's setup was last changed on this TV (0: not since this version). */
    fun localChangedAt(): Long = store.getLong(KEY_LOCAL_CHANGED_AT, 0L)

    private val deviceId: String
        get() = store.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            store.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private fun loadState() = DriveSyncState(
        available = clientId.isNotBlank() && clientSecret.isNotBlank(),
        connected = store.contains(KEY_REFRESH) || context.getSharedPreferences("reseq_drive_credentials", Context.MODE_PRIVATE).contains("profile.1"),
        email = store.getString(KEY_EMAIL, null),
        autoSync = store.getBoolean(KEY_AUTO, true),
        lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L)
    )

    /** Starts automatic sync: check Drive for a newer setup, then back up local changes. */
    @Synchronized
    fun start() {
        if (started || !_state.value.connected) return
        started = true
        scope.launch {
            if (_state.value.connected && _state.value.autoSync) {
                pull(force = false)
                push(force = false)
            }
        }
        // Poll only while the app process is alive; edits are still debounced below.
        scope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(15))
                if (_state.value.connected && _state.value.autoSync) pull(force = false)
            }
        }
        // When this TV's own setup last changed (not by a restore or sync): the restore prompt
        // warns when the saved copy is older than that.
        scope.launch {
            setupChanges().drop(1).collect {
                val now = System.currentTimeMillis()
                if (!applying && now - appliedAtMs > 20_000) store.edit().putLong(KEY_LOCAL_CHANGED_AT, now).apply()
            }
        }
        scope.launch {
            setupChanges().drop(1).debounce(8_000).collect {
                val s = _state.value
                if (s.connected && s.autoSync && !applying) push(force = false)
            }
        }
    }

    private fun setupChanges() = kotlinx.coroutines.flow.merge(
        prefs.changes,
        profiles.profilesList.map { Unit },
        profiles.profilesList.flatMapLatest { list ->
            kotlinx.coroutines.flow.merge(*list.map { shuffle.selectionChanges(it.id) }.toTypedArray())
        },
        profiles.profilesList.flatMapLatest { list ->
            kotlinx.coroutines.flow.merge(*list.map { addonPreferences.streamSourceSelectionChanges(it.id) }.toTypedArray())
        },
        * (1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES).map(orders::selectionChanges).toTypedArray()
    )

    // ------------------------------------------------------------------ sign-in

    fun beginSignIn() {
        if (!_state.value.available) return
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = _state.value.copy(busy = true, message = null, signIn = null)
            // Ask for the private app folder (plus the email, to show which account is connected).
            // If Google won't allow that set for this project, fall back step by step, ending with
            // a normal Drive file the app creates ("Nuvio Live TV backup").
            var codes = JSONObject()
            var mode = MODE_APPDATA
            for ((scopes, m) in SCOPE_ATTEMPTS) {
                codes = runCatching {
                    postForm("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to scopes))
                }.getOrElse {
                    _state.value = _state.value.copy(busy = false, message = "Couldn't reach Google. Try again.")
                    return@launch
                }
                mode = m
                if (codes.optString("error") != "invalid_scope") break
                Log.w(TAG, "Scopes refused ($scopes): ${codes.optString("error_description")}")
            }
            val deviceCode = codes.optString("device_code")
            val userCode = codes.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) {
                _state.value = _state.value.copy(busy = false, message = googleError(codes))
                return@launch
            }
            val url = codes.optString("verification_url").ifBlank { "https://www.google.com/device" }
            val qrUrl = codes.optString("verification_uri_complete").ifBlank { url }
            val expiresAt = System.currentTimeMillis() + codes.optLong("expires_in", 1800) * 1000
            var interval = codes.optLong("interval", 5).coerceAtLeast(5)
            _state.value = _state.value.copy(busy = false, signIn = SignInPrompt(userCode, url, qrUrl, expiresAt))

            while (System.currentTimeMillis() < expiresAt) {
                delay(interval * 1000)
                val token = runCatching {
                    postForm(
                        "https://oauth2.googleapis.com/token",
                        mapOf(
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                            "device_code" to deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                        )
                    )
                }.getOrNull() ?: continue
                when (token.optString("error")) {
                    "" -> {
                        store.edit().putString(KEY_MODE, mode).apply()
                        try { onSignedIn(token) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            _state.value = _state.value.copy(signIn = null, busy = false,
                                message = "Could not finish Google Drive sign-in. Try connecting again.")
                        }
                        return@launch
                    }
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "access_denied" -> {
                        _state.value = _state.value.copy(signIn = null, message = "Sign-in was declined.")
                        return@launch
                    }
                    else -> {
                        _state.value = _state.value.copy(signIn = null, message = googleError(token))
                        return@launch
                    }
                }
            }
            _state.value = _state.value.copy(signIn = null, message = "The code expired. Try again.")
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.value = _state.value.copy(signIn = null, busy = false)
    }

    private suspend fun onSignedIn(token: JSONObject) {
        val requiredScope = "https://www.googleapis.com/auth/drive." + if (isAppDataMode) "appdata" else "file"
        if (requiredScope !in token.optString("scope").split(' ')) {
            _state.value = _state.value.copy(signIn = null, busy = false,
                message = "Google sign-in did not grant Drive access. Connect again and allow the Drive permission.")
            return
        }
        val refresh = token.optString("refresh_token")
        if (refresh.isBlank()) {
            _state.value = _state.value.copy(signIn = null, message = "Google didn't return a sign-in. Try again.")
            return
        }
        accessToken = token.optString("access_token")
        accessTokenExpiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000
        val email = emailFromIdToken(token.optString("id_token"))
        protected.write(1, refresh)
        // An explicit sign-in may be a different Google account. Never reuse its predecessor's baseline.
        android.util.AtomicFile(baseFile()).delete()
        store.edit().remove(KEY_REFRESH).remove(KEY_FILE_ID).remove(KEY_LAST_HASH).putString(KEY_EMAIL, email).apply()
        _state.value = _state.value.copy(connected = true, email = email, signIn = null, message = null)
        // First connection merges with Drive; it must not replace an existing local setup.
        push(force = true)
        start()
    }

    fun disconnect() {
        cancelSignIn()
        scope.launch {
            mutex.withLock {
                store.edit().remove(KEY_REFRESH).remove(KEY_EMAIL).remove(KEY_FILE_ID).remove(KEY_LAST_HASH)
                    .remove(KEY_MODE).remove(KEY_LAST_SYNC).remove(KEY_APPLIED_AT).apply()
                android.util.AtomicFile(baseFile()).delete()
                protected.clear()
                accessToken = null
                accessTokenExpiresAt = 0L
                _state.value = _state.value.copy(connected = false, email = null, busy = false, lastSyncMs = 0L,
                    message = "Disconnected on this TV. Your backup stays in your Google Drive.")
                // Google's revoke endpoint invalidates this user's grant on the other TVs too.
                // Disconnect is local; full revocation remains in Google Account permissions.
            }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        store.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.value = _state.value.copy(autoSync = enabled)
    }

    fun backUpNow() { scope.launch { push(force = true) } }
    @Volatile private var emptyRefused = false
    fun restoreNow() { scope.launch { pull(force = true) } }

    // ------------------------------------------------------------------ backup / restore

    /** Uploads this TV's setup. Skips the upload when nothing changed since the last one. */
    private suspend fun push(force: Boolean) = mutex.withLock {
        val token = token() ?: return@withLock
        emptyRefused = false
        _state.value = _state.value.copy(busy = true, message = if (force) "Backing up…" else null)
        val result = runCatching {
            var data = exportSetup()
            val removedHere = prefs.removedSourceIds()
            val hash0 = sha(data.toString())
            if (!force && hash0 == store.getString(KEY_LAST_HASH, null)) return@runCatching false
            // Another TV saved since we last synced: combine its changes with ours first, so
            // neither TV overwrites the other (that made favorites and names flip back and forth).
            // Decided by what's in the copy, not by the TVs' clocks: a TV whose clock ran ahead
            // took the other TVs' newer copy for an older one, skipped it, and then saved over it
            // without the playlists they had added (which then vanished from every TV).
            var expectedEtag: String? = null
            remoteCopy(token)?.let { remote ->
                expectedEtag = remote.etag
                val remoteData = driveBackupData(remote.json)
                val base = readBase()
                if (remoteData.toString() != base?.toString()) {
                    val merged = merge(base, local = data, remote = remoteData, localWinsWithoutBase = true, removedHere = removedHere)
                    if (merged.toString() != data.toString()) applyLocally(merged)
                    data = merged
                }
            }
            // No playlists and no guides here (signed out, a fresh install, everything removed):
            // never write that over the backup. Automatic sync just skips it; Back up now says why.
            if (!hasSetup(data)) {
                if (force) emptyRefused = true
                return@runCatching false
            }
            val hash = sha(data.toString())
            val now = System.currentTimeMillis()
            val body = JSONObject()
                .put("format", 1)
                .put("savedAt", now)
                .put("deviceId", deviceId)
                .put("device", Build.MODEL ?: "TV")
                .put("data", data)
                .toString()
            val fileId = fileId(token)
            if (fileId == null) {
                val meta = JSONObject().put("name", FILE_NAME)
                if (isAppDataMode) meta.put("parents", org.json.JSONArray().put("appDataFolder"))
                val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(meta.toString().toRequestBody(JSON))
                    .addPart(body.toRequestBody(JSON))
                    .build()
                val created = execute(
                    Request.Builder()
                        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                        .header("Authorization", "Bearer $token")
                        .post(multipart)
                        .build()
                )
                store.edit().putString(KEY_FILE_ID, JSONObject(created).optString("id")).apply()
            } else {
                execute(
                    Request.Builder()
                        .url("https://www.googleapis.com/upload/drive/v2/files/$fileId?uploadType=media")
                        .header("Authorization", "Bearer $token")
                        .header("If-Match", checkNotNull(expectedEtag) { "Backup version could not be verified. Nothing was overwritten." })
                        .put(body.toRequestBody(JSON))
                        .build()
                )
            }
            store.edit().putString(KEY_LAST_HASH, hash).putLong(KEY_LAST_SYNC, now).putLong(KEY_APPLIED_AT, now).apply()
            writeBase(data)
            // Those removals are in the saved copy now.
            prefs.forgetRemovedSources(removedHere)
            true
        }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Backup failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                emptyRefused -> "Nothing backed up: add a playlist, guide or TVDB order preference first. Your saved copy in Google Drive was left as it was."
                force -> "Backed up to Google Drive."
                else -> _state.value.message
            }
        )
    }

    /**
     * Applies the Drive copy if it's newer than what this TV has (or always, when [force]).
     * Returns true if a copy was found and applied.
     */
    private suspend fun pull(force: Boolean, onlyIfExists: Boolean = false): Boolean = mutex.withLock {
        val token = token() ?: return@withLock false
        _state.value = _state.value.copy(busy = true, message = if (force && !onlyIfExists) "Restoring…" else null)
        val result = runCatching {
            val fileId = fileId(token) ?: return@runCatching null
            val text = execute(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v2/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            )
            val json = JSONObject(text)
            val savedAt = json.optLong("savedAt")
            val fromThisTv = json.optString("deviceId") == deviceId
            val remoteData = driveBackupData(json)
            // Nothing new: saved by this TV, or the same as what this TV last synced. (Not judged
            // by the time it was saved: TVs' clocks differ, and a newer copy was skipped.)
            val base = readBase()
            if (!force && (fromThisTv || remoteData.toString() == base?.toString())) return@runCatching false
            // A saved copy with no playlists or guides (made by an older version after signing
            // out): never let it empty this TV. Treated as no backup at all.
            if (!hasSetup(remoteData)) return@runCatching null
            // "Restore" replaces this TV's setup; automatic sync keeps changes made on this TV
            // since the last sync and takes everything else from the other TV.
            val data = if (force) remoteData
            else merge(base, local = exportSetup(), remote = remoteData, localWinsWithoutBase = false, removedHere = prefs.removedSourceIds())
            applyLocally(data)
            store.edit()
                .putLong(KEY_APPLIED_AT, savedAt)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_LAST_HASH, sha(remoteData.toString()))
                .apply()
            writeBase(remoteData)
            // Our own changes were kept on top: send the combined setup back up.
            if (data.toString() != remoteData.toString()) scope.launch { delay(2_000); push(force = false) }
            true
        }
        val applied = result.getOrNull() == true
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Restore failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                result.getOrNull() == null && force && !onlyIfExists -> "No backup found in Google Drive yet."
                applied && force -> "Restored from Google Drive."
                applied -> "Updated from your other TV."
                else -> _state.value.message
            }
        )
        applied
    }

    // ------------------------------------------------------------------ merging

    private suspend fun applyLocally(data: JSONObject) {
        applying = true
        appliedAtMs = System.currentTimeMillis()
        try {
            driveBackupData(JSONObject().put("format", 1).put("data", data))
            val before = exportSetup()
            val selections = if (data.has(TVDB_VERSION)) (1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES).associateWith { id ->
                data.keys().asSequence().filter { it.startsWith("$TVDB_PREFIX$id:") }.associate {
                    it.removePrefix("$TVDB_PREFIX$id:") to data.getJSONObject(it).getString("v")
                }
            } else emptyMap()
            val shuffleSelections = if (data.has(SHUFFLE_VERSION)) (1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES).associateWith { id ->
                data.keys().asSequence().filter { it.startsWith("$SHUFFLE_PREFIX$id:") }.associate {
                    it.removePrefix("$SHUFFLE_PREFIX$id:") to data.getJSONObject(it).getString("v")
                }
            } else emptyMap()
            val sourceSelections = if (data.has(SOURCES_VERSION)) (1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES).associateWith { id ->
                data.keys().asSequence().filter { it.startsWith("$SOURCES_PREFIX$id:") }.associate {
                    it.removePrefix("$SOURCES_PREFIX$id:") to data.getJSONObject(it).getString("v")
                }
            } else emptyMap()
            sourceSelections.values.forEach { com.nuvio.tv.data.local.validateStreamSourceSelections(it) }
            shuffleSelections.values.forEach { com.nuvio.tv.data.local.decodeShuffleSelections(it) }
            selections.values.forEach { com.nuvio.tv.data.tvdb.validateTvdbSelections(it) }
            val localProfiles = profiles.profilesList.first().associateBy { it.id }
            val profileIds = selections.filterValues { it.isNotEmpty() }.keys + shuffleSelections.filterValues { it.isNotEmpty() }.keys + sourceSelections.filterValues { it.isNotEmpty() }.keys + data.keys().asSequence()
                .filter { it.startsWith(PROFILE_PREFIX) }.map { it.removePrefix(PROFILE_PREFIX).toInt() }.toSet()
            profileIds.forEach { id ->
                check(data.optJSONObject("$PROFILE_PREFIX$id")?.optString("v") == localProfiles[id]?.name) {
                    "Backup profiles differ from this TV. Create or restore matching Nuvio profiles (same IDs and names) before restoring this setup."
                }
            }
            val changed = prefs.importFromSync(liveData(data))
            selections.forEach { (id, values) -> orders.importSelections(id, values) }
            sourceSelections.filterKeys { it in localProfiles }.forEach { (id, values) -> addonPreferences.importStreamSourceSelections(id, values) }
            shuffleSelections.filterKeys { it in localProfiles }.forEach { (id, values) -> shuffle.importSelections(id, values) }
            val after = exportSetup()
            // What really changed here. Nothing: no reload at all (it used to reload Live TV,
            // reading the guide again, on every sync, often twice at start-up).
            val keys = (before.keys().asSequence() + after.keys().asSequence()).toSet()
                .filter { k -> before.opt(k)?.toString() != after.opt(k)?.toString() }
            if (keys.isEmpty() && changed.isEmpty()) {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Sync: other TV's setup is the same as this one; nothing to reload")
                return
            }
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Sync: applied changes from your other TV (${keys.take(6).joinToString()}${if (keys.size > 6) "…" else ""})")
            // Download any playlists or guides this TV doesn't have yet, playlists whose address
            // or login changed on the other TV, and rebuild the guide.
            if (liveData(before).toString() != liveData(after).toString()) repository.get().reloadAfterSync(changed)
            delay(500)
        } finally {
            applying = false
            appliedAtMs = System.currentTimeMillis()
        }
    }

    /** Drive uses different ETags for media downloads and file updates. */
    private suspend fun remoteCopy(token: String): RemoteBackup? {
        val fileId = fileId(token) ?: return null
        val metadataRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v2/files/$fileId?fields=id,etag")
            .header("Authorization", "Bearer $token")
            .get().build()
        val etag = JSONObject(execute(metadataRequest)).getString("etag")
        check(etag.isNotBlank()) { "Backup version could not be verified. Nothing was overwritten." }
        val text = execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v2/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .get().build()
        )
        // Media If-Match compares a different tag, so verify the resource version on both sides
        // of the download. The upload also uses If-Match to reject any subsequent edit.
        check(JSONObject(execute(metadataRequest)).getString("etag") == etag) {
            "The backup changed on another TV. Sync again before saving."
        }
        return RemoteBackup(JSONObject(text), etag)
    }

    /**
     * Combines two setups setting by setting: whatever this TV changed since the last sync
     * ([base]) is kept, everything else comes from the other TV. Without a base, [localWinsWithoutBase]
     * decides.
     */
    private fun merge(
        base: JSONObject?,
        local: JSONObject,
        remote: JSONObject,
        localWinsWithoutBase: Boolean,
        removedHere: Set<String>
    ): JSONObject {
        if (base != null) DriveSyncConflict.requireCompatible(base, local, remote, SOURCE_LISTS)
        if (base == null) {
            // Nothing synced on this TV yet (or since an older version): one side's settings win,
            // but playlists and guides from both are kept. Taking one TV's whole list lost the
            // playlists only the other TVs had.
            val (first, second) = if (localWinsWithoutBase) local to remote else remote to local
            val out = JSONObject(first.toString())
            second.keys().forEach { key ->
                if (!out.has(key)) out.put(key, second.get(key))
                else if (key !in SOURCE_LISTS) {
                    DriveSyncConflict.requireCompatible(JSONObject(),
                        JSONObject().put(key, first.get(key)), JSONObject().put(key, second.get(key)), emptySet())
                }
            }
            for (k in SOURCE_LISTS) {
                val a = first.optJSONObject(k)
                val b = second.optJSONObject(k)
                if (b == null) continue
                if (a == null) { out.put(k, JSONObject(b.toString())); continue }
                runCatching { unionSources(a.optString("v"), b.optString("v"), removedHere) }.getOrNull()
                    ?.let { out.put(k, JSONObject(a.toString()).put("v", it)) }
            }
            return out
        }
        val out = JSONObject(remote.toString())
        val keys = HashSet<String>().apply {
            local.keys().forEach { add(it) }
            base.keys().forEach { add(it) }
        }
        keys.forEach { k ->
            mergedDriveSet(base.optJSONObject(k), local.optJSONObject(k), remote.optJSONObject(k))?.let {
                out.put(k, it)
                return@forEach
            }
            val l = local.opt(k)?.toString()
            val b = base.opt(k)?.toString()
            if (l != b) {
                // Playlists and guides are lists: combine them source by source, setting by
                // setting. Taking this TV's whole list whenever anything in it had changed (a
                // playlist switched off, the server's time zone learned) threw away the other
                // TV's edits, like a provider's new server address, and then sent the old one back.
                // A list missing on one side counts as empty (never "remove everything").
                val listMerged = if (k in SOURCE_LISTS && (local.has(k) || remote.has(k))) {
                    runCatching {
                        mergeSources(
                            base.optJSONObject(k)?.optString("v"),
                            local.optJSONObject(k)?.optString("v") ?: "[]",
                            remote.optJSONObject(k)?.optString("v") ?: "[]",
                            removedHere
                        )
                    }.getOrNull()
                } else null
                val shell = (local.optJSONObject(k) ?: remote.optJSONObject(k))
                when {
                    listMerged != null && shell != null -> out.put(k, JSONObject(shell.toString()).put("v", listMerged))
                    local.has(k) -> out.put(k, local.get(k))
                    else -> out.remove(k)
                }
            }
        }
        return out
    }

    /**
     * Three-way merge of two source lists (JSON arrays of objects with an "id"): sources added
     * or removed on either TV are added or removed, and within a source each setting changed on
     * this TV since the last sync is kept, everything else comes from the other TV.
     */
    private fun mergeSources(baseRaw: String?, localRaw: String, remoteRaw: String, removedHere: Set<String>): String {
        fun read(raw: String?) = readSources(raw)
        val base = read(baseRaw)
        val local = read(localRaw)
        val remote = read(remoteRaw)
        val result = org.json.JSONArray()
        // The other TV's order, with its additions and edits.
        for ((id, r) in remote) {
            val l = local[id]
            val b = base[id]
            when {
                // Removed on this TV (by you) since the last sync: stays removed.
                l == null && id in removedHere -> continue
                // Missing here but never removed here (this TV didn't get it yet, or lost it):
                // kept. Only a removal someone actually made takes a source off the other TVs.
                l == null -> result.put(r)
                b == null -> result.put(r) // On both TVs but new to the last sync: the other TV's.
                else -> {
                    val merged = JSONObject(r.toString())
                    l.keys().forEach { f ->
                        if (l.opt(f)?.toString() != b.opt(f)?.toString()) merged.put(f, l.get(f))
                    }
                    result.put(merged)
                }
            }
        }
        // Added on this TV since the last sync.
        for ((id, l) in local) {
            if (id !in remote && id !in base) result.put(l)
        }
        return result.toString()
    }

    private fun readSources(raw: String?): LinkedHashMap<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        if (raw.isNullOrBlank()) return out
        val arr = org.json.JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out[id] = o
        }
        return out
    }

    /**
     * Two source lists combined with nothing synced to compare against: every source in [firstRaw]
     * (its versions win), then those only in [secondRaw], except ones removed on this TV.
     */
    private fun unionSources(firstRaw: String, secondRaw: String, removedHere: Set<String>): String {
        val first = readSources(firstRaw)
        val result = org.json.JSONArray()
        first.values.forEach { result.put(it) }
        for ((id, o) in readSources(secondRaw)) {
            if (id !in first && id !in removedHere) result.put(o)
        }
        return result.toString()
    }

    private fun baseFile() = java.io.File(context.filesDir, "livetv_drive_base.json")

    private fun readBase(): JSONObject? = if (baseFile().exists()) JSONObject(baseFile().readText()) else null

    private fun writeBase(data: JSONObject) {
        val file = android.util.AtomicFile(baseFile())
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    // ------------------------------------------------------------------ Google plumbing

    /** A valid access token, refreshed when needed. Null (and disconnected) if sign-in was revoked. */
    private suspend fun token(): String? {
        val refresh = refreshToken() ?: run {
            _state.value = _state.value.copy(connected = false, message = "Connect Google Drive to keep syncing.")
            return null
        }
        accessToken?.let { if (System.currentTimeMillis() < accessTokenExpiresAt - 60_000) return it }
        val res = runCatching {
            postForm(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token"
                )
            )
        }.getOrElse { e ->
            // The reason too (timeout, blocked, no route…), so a VPN or network problem shows.
            val why = when (e) {
                is java.net.SocketTimeoutException -> "timed out"
                is java.net.UnknownHostException -> "couldn't look up Google's address"
                is javax.net.ssl.SSLException -> "secure connection refused"
                is java.net.ConnectException -> "connection refused"
                else -> e.javaClass.simpleName
            }
            _state.value = _state.value.copy(message = "Couldn't reach Google Drive ($why).")
            return null
        }
        if (res.optString("error") == "invalid_grant") {
            protected.clear()
            store.edit().remove(KEY_REFRESH).apply()
            _state.value = _state.value.copy(connected = false, message = "Google Drive sign-in expired or access was removed. Connect again to keep syncing.")
            return null
        }
        val access = res.optString("access_token").ifBlank { return null }
        accessToken = access
        accessTokenExpiresAt = System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000
        return access
    }

    /** The backup file's id in the app data folder, or null if there isn't one yet. */
    private suspend fun fileId(token: String): String? {
        store.getString(KEY_FILE_ID, null)?.let { return it }
        val text = execute(
            Request.Builder()
                .url(
                    "https://www.googleapis.com/drive/v3/files?spaces=${if (isAppDataMode) "appDataFolder" else "drive"}" +
                        "&q=" + java.net.URLEncoder.encode("name='$FILE_NAME' and trashed=false", "UTF-8").replace("+", "%20") +
                        "&fields=files(id)"
                )
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        )
        val files = JSONObject(text).optJSONArray("files")
        check((files?.length() ?: 0) <= 1) { "Multiple backups found. Nothing was overwritten." }
        val id = files?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        store.edit().putString(KEY_FILE_ID, id).apply()
        return id
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "http_${r.code}") }
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 404) store.edit().remove(KEY_FILE_ID).apply()
            if (r.code == 412) error("The backup changed on another TV. Sync again before saving.")
            if (!r.isSuccessful) error("HTTP ${r.code}")
            text
        }
    }

    private val isAppDataMode: Boolean
        get() = store.getString(KEY_MODE, MODE_APPDATA) == MODE_APPDATA

    /** Plain-English reason, plus Google's own wording so problems can be tracked down. */
    private fun googleError(json: JSONObject): String {
        val e = json.optString("error").ifBlank { json.optString("error_code") }
        val detail = json.optString("error_description").takeIf { it.isNotBlank() }?.let { " Google says: $it" }.orEmpty()
        return when (e) {
            "invalid_client" -> "Google doesn't recognize this build's sign-in client. Check that the client ID and secret are right, and that its type is \"TVs and Limited Input devices\".$detail"
            "unauthorized_client" -> "This sign-in client isn't allowed to use TV sign-in. Create one of type \"TVs and Limited Input devices\".$detail"
            "invalid_scope" -> "Google refused the Drive permission for this app.$detail"
            "org_internal" -> "This app is limited to one organization. Set the audience to External in Google Cloud.$detail"
            "rate_limit_exceeded" -> "Too many attempts. Wait a minute and try again."
            else -> "Google sign-in failed ($e).$detail"
        }
    }

    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        JSONObject(json).optString("email").ifBlank { null }
    }.onFailure { Log.w(TAG, "No email in sign-in") }.getOrNull()

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PROFILE_PREFIX = "reseq_profile:"
        const val TVDB_PREFIX = "reseq_tvdb:"
        const val TVDB_VERSION = "reseq_tvdb_version"
        const val SOURCES_PREFIX = "reseq_sources:"
        const val SOURCES_VERSION = "reseq_sources_version"
        const val SHUFFLE_PREFIX = "reseq_shuffle:"
        const val SHUFFLE_VERSION = "reseq_shuffle_version"
        const val TAG = "LiveTvDriveSync"
        /** Settings that are lists of sources, merged source by source. */
        val SOURCE_LISTS = setOf("playlists", "epg_sources")
        const val MODE_APPDATA = "appdata"
        const val MODE_FILE = "file"
        /** New connections use the supported device-flow permission; retain legacy mode for saved sessions. */
        val SCOPE_ATTEMPTS = listOf(
            "https://www.googleapis.com/auth/drive.file" to MODE_FILE
        )
        const val FILE_NAME = "Nuvio ReSeq backup.json"
        val JSON = "application/json; charset=UTF-8".toMediaType()
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_AUTO = "auto_sync"
        const val KEY_FILE_ID = "file_id"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_APPLIED_AT = "applied_at"
        const val KEY_LOCAL_CHANGED_AT = "local_changed_at"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_MODE = "mode"
    }
}
