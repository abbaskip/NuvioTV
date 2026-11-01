package com.nuvio.tv.ui.screens.settings

// Google Drive controls incorporated and adapted from Nuvio + IPTV (GPL-3.0).
// https://github.com/homelessbrian/NuvioPlusIPTV
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.sync.LiveTvDriveSync
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@HiltViewModel
internal class BackupSyncSettingsViewModel @Inject constructor(val drive: LiveTvDriveSync) : ViewModel() {
    init { drive.start() }
}

private enum class BackupDialog { BACK_UP, RESTORE, DISCONNECT }

@Composable
internal fun BackupSyncSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: BackupSyncSettingsViewModel = hiltViewModel()
) {
    val drive = viewModel.drive
    val state by drive.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    val close = { dialog = null }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsDetailHeader(title = "Backup & Sync", subtitle = "TVDB orders, Shuffle choices, IPTV and Sports Hub configuration")
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                DriveSyncCard(
                    modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier,
                    state = state, expanded = expanded, onToggleExpanded = { expanded = !expanded },
                    onConnect = drive::beginSignIn, onAutoSync = drive::setAutoSync,
                    onBackUp = { dialog = BackupDialog.BACK_UP }, onRestore = { dialog = BackupDialog.RESTORE },
                    onDisconnect = { dialog = BackupDialog.DISCONNECT }
                )
            }
        }
    }
    when (dialog) {
        BackupDialog.BACK_UP -> ConfirmDeleteDialog(
            title = "Back up now?", message = "Save this TV's configuration to your private Google Drive backup. Conflicting changes are refused.",
            onDismiss = close, onConfirm = { drive.backUpNow(); close() }, confirmLabel = "Back Up")
        BackupDialog.DISCONNECT -> ConfirmDeleteDialog(
            title = "Disconnect Google Drive?", message = "This TV stops syncing. Your backup stays in Google Drive and other TVs remain connected.",
            onDismiss = close, onConfirm = { drive.disconnect(); close() }, confirmLabel = "Disconnect")
        BackupDialog.RESTORE -> {
            var loaded by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf<LiveTvDriveSync.BackupInfo?>(null) }
            LaunchedEffect(Unit) { info = drive.backupInfo(); loaded = true }
            ConfirmDeleteDialog(title = "Restore from Google Drive?", message = restoreMessage(loaded, info, drive.localChangedAt()),
                onDismiss = close, onConfirm = { drive.restoreNow(); close() }, confirmLabel = "Restore")
        }
        null -> Unit
    }
    state.signIn?.let { DriveSignInDialog(it, drive::cancelSignIn) }
}

// ==================================================================== Google Drive sync

/** Collapsed to one line until selected; then shows sign-in, backup and restore. */
@Composable
private fun DriveSyncCard(
    modifier: Modifier = Modifier,
    state: com.nuvio.tv.livetv.sync.DriveSyncState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onConnect: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onDisconnect: () -> Unit
) {
    val status = when {
        !state.available -> "Not available in this build"
        !state.connected -> "Off"
        state.busy -> "Syncing…"
        state.lastSyncMs > 0 -> "On · last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastSyncMs))
        else -> "On"
    }
    LiveSection(
        title = "Google Drive",
        summary = status,
        icon = Icons.Default.Backup,
        open = expanded,
        onToggle = onToggleExpanded,
        modifier = modifier
    ) {
        run {
            when {
                !state.available -> Text(
                    text = "Google Drive sync needs to be set up by whoever builds this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                !state.connected -> {
                    Text(
                        text = "Your playlists, guide settings, Sports Hub preferences and per-profile TVDB order and Shuffle choices are saved privately in your Google Drive. Backups include playlist logins, so keep the backup private. Personal API keys and tracker sign-ins are not included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow("Connect Google Drive", "Keeps your ReSeq configuration on every TV, using your own Google Drive. Sign in with a code on your phone.", onClick = onConnect)
                }
                else -> {
                    SettingsActionRow("Account", "The Google account used for your ReSeq backup", state.email ?: "Connected", onClick = {})
                    SettingsToggleRow(
                        "Sync Automatically",
                        "Back up changes, and pick up changes made on your other TVs",
                        state.autoSync,
                        { onAutoSync(!state.autoSync) }
                    )
                    SettingsActionRow("Back Up Now", "Save this TV's setup to Google Drive", onClick = onBackUp)
                    SettingsActionRow("Restore from Google Drive", "Replace this TV's setup with the saved copy", onClick = onRestore)
                    SettingsActionRow("Disconnect", "Stop syncing on this TV (your backup stays in Drive)", onClick = onDisconnect)
                }
            }
            state.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DriveSignInDialog(prompt: com.nuvio.tv.livetv.sync.SignInPrompt, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    // Same layout as Nuvio's other QR sign-ins (debrid, Simkl): instructions, QR code, then the code.
    val qrBitmap = remember(prompt.qrUrl) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(prompt.qrUrl, 420, margin = 1) }.getOrNull()
    }
    val shortUrl = prompt.url.removePrefix("https://").removePrefix("http://")
    NuvioDialog(onDismiss = onCancel, title = "Connect Google Drive", width = 560.dp,
        usePlatformDefaultWidth = false, contentPadding = 16.dp, contentSpacing = 8.dp) {
        Text(
            text = "Scan the QR code with your phone, or go to $shortUrl, then enter the code below and allow access.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (qrBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(160.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
        Text(
            text = prompt.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = NuvioTheme.colors.Secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = shortUrl,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Waiting for you to allow access… this screen continues by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}

private fun restoreMessage(loaded: Boolean, info: com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?, localChangedAt: Long): String {
    val base = "This TV's Live TV setup, Sports Hub preferences and saved TVDB episode orders and Shuffle choices are replaced with the copy in Google Drive. Profiles must match; personal API keys and tracker sign-ins are kept on this TV."
    if (!loaded) return "$base\n\nChecking when it was saved…"
    if (info == null || info.savedAtMs <= 0L) return base
    val now = System.currentTimeMillis()
    val days = ((now - info.savedAtMs) / (24 * 60 * 60_000L)).toInt()
    val ago = when {
        now - info.savedAtMs < 60 * 60_000L -> "less than an hour ago"
        days < 1 -> "today"
        days == 1 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        else -> "${days / 30} months ago"
    }
    val stamp = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(info.savedAtMs))
    val where = if (info.fromThisTv) "this TV" else info.device
    val saved = "Saved $stamp on $where ($ago)."
    val warning = when {
        localChangedAt > info.savedAtMs + 60_000L ->
            "This TV has changes made after that copy was saved. Restoring puts back the older playlists and logins. If this TV has the latest, press Back Up Now here instead."
        days >= 7 ->
            "That copy is $ago. If your playlists or logins have changed since (a new server address or password), press Back Up Now on a TV that has the latest first."
        else -> null
    }
    return listOfNotNull(base, saved, warning).joinToString("\n\n")
}
