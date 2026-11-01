package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.components.NuvioDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class PersonalRatingsState(val open: Boolean = false, val busy: Boolean = false,
    val results: List<PersonalRatingResult> = emptyList(), val saved: Boolean = false, val error: String? = null)

@HiltViewModel
internal class PersonalRatingsViewModel @Inject constructor(
    private val service: PersonalRatingsService,
    profiles: ProfileManager,
    preferences: com.nuvio.tv.data.local.PlayerSettingsDataStore
) : ViewModel() {
    val connected: StateFlow<Set<PersonalRatingProvider>?> = service.connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val promptEnabled: StateFlow<Boolean?> = preferences.playerSettings.map { it.promptToRateAfterWatching }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val activeProfileId = profiles.activeProfileId
    val state = MutableStateFlow(PersonalRatingsState())
    private var session: PersonalRatingSession? = null
    private var target: PersonalRatingTarget? = null
    private var job: Job? = null
    private var requestVersion = 0L
    init { viewModelScope.launch { profiles.activeProfileId.drop(1).collect { close() } } }

    fun open(value: PersonalRatingTarget) {
        close()
        target = value
        val version = requestVersion
        state.value = PersonalRatingsState(open = true, busy = true)
        job = viewModelScope.launch {
            try {
                val account = service.session(value)
                session = account
                val result = service.read(value, account)
                state.value = PersonalRatingsState(open = true, results = result)
            } catch (cancelled: CancellationException) { if (version == requestVersion) close(); throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(busy = false, error = "Could not contact your trackers. Close and try again.") }
        }
    }
    fun write(rating: Int?) {
        val account = session ?: return
        val value = target ?: return
        if (state.value.busy) return
        val version = requestVersion
        state.value = state.value.copy(busy = true, saved = false)
        job = viewModelScope.launch {
            try {
                val result = service.write(value, account, rating)
                state.value = PersonalRatingsState(open = true, results = result, saved = true)
            } catch (cancelled: CancellationException) { if (version == requestVersion) close(); throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(busy = false, error = "Could not contact your trackers. Close and try again.") }
        }
    }
    fun close() { requestVersion++; job?.cancel(); job = null; session = null; target = null; state.value = PersonalRatingsState() }
}

@Composable
internal fun PersonalRatingButton(meta: Meta, viewModel: PersonalRatingsViewModel = hiltViewModel()) {
    val target = remember(meta.id, meta.imdbId, meta.apiType) { personalRatingTarget(meta) } ?: return
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val eligible = connected.orEmpty().filter(target::supports)
    if (eligible.isEmpty()) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    Button(onClick = { viewModel.open(target) }) { Text("Rate") }
    PersonalRatingDialog(meta.name, state, eligible, viewModel::write, viewModel::close)
}

@Composable
private fun PersonalRatingDialog(title: String, state: PersonalRatingsState, eligible: List<PersonalRatingProvider>,
    onWrite: (Int?) -> Unit, onClose: () -> Unit) {
    if (state.open) {
        var selected by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(state.results, state.saved) {
            if (!state.saved) selected = state.results.filter { it.confirmed }.mapNotNull { it.rating }.distinct().singleOrNull()
        }
        NuvioDialog(onDismiss = onClose, title = "Your rating", subtitle = title, width = 620.dp) {
            Text("Half-star steps · 1–10 on Trakt, Simkl and MDBList")
            if (state.busy) Text("Contacting trackers…")
            state.error?.let { Text(it) }
            state.results.forEach { result ->
                Text(result.provider.label + ": " + when {
                    !result.confirmed -> if (state.saved) "Not saved — retry" else "Could not load existing rating"
                    result.rating == null -> if (state.saved) "Rating removed" else "Not rated"
                    else -> "${result.rating / 2.0} ★ (${result.rating}/10)" + if (state.saved) " — saved" else ""
                })
            }
            for (start in listOf(1, 6)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rating in start until start + 5) Button(enabled = !state.busy, onClick = { selected = rating }) {
                    Text((if (selected == rating) "✓ " else "") + "${rating / 2.0} ★")
                }
            }
            Text("Submit to: " + eligible.joinToString { it.label })
            if (PersonalRatingProvider.SIMKL in eligible) Text("Simkl may also add newly rated titles to your watchlist.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !state.busy && state.results.isNotEmpty() && selected != null, onClick = { onWrite(selected) }) { Text("Submit") }
                Button(enabled = !state.busy && state.results.isNotEmpty(), onClick = { onWrite(null) }) { Text("Remove ratings") }
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
internal fun MovieRatingPromptHost(request: MovieRatingRequest?, canShow: Boolean, onConsumed: () -> Unit) {
    if (request == null) return
    val viewModel: PersonalRatingsViewModel = hiltViewModel(key = "completed-movie-rating")
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val enabled by viewModel.promptEnabled.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target = remember(request) { personalRatingTarget(request.contentId, "movie") }
    val eligible = connected.orEmpty().filter { target?.supports(it) == true }
    var opened by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, canShow, connected, enabled, profile, state.open) {
        when {
            profile != request.profileId || enabled == false || target == null -> { viewModel.close(); onConsumed() }
            opened && !state.open -> onConsumed()
            connected == null || enabled == null -> Unit
            eligible.isEmpty() -> { viewModel.close(); onConsumed() }
            canShow && !opened -> { opened = true; viewModel.open(target) }
            !canShow && opened -> { viewModel.close(); onConsumed() }
        }
    }
    if (canShow) PersonalRatingDialog(request.title, state, eligible, viewModel::write) {
        viewModel.close(); onConsumed()
    }
}

@Composable
internal fun PersonalRatingPromptSetting(enabled: Boolean, onToggle: () -> Unit) {
    val viewModel: PersonalRatingsViewModel = hiltViewModel()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    if (connected.isNullOrEmpty()) return
    com.nuvio.tv.ui.screens.settings.SettingsToggleRow(
        title = "Prompt to rate after watching",
        subtitle = "Ask when returning from a completed movie. Uses all compatible signed-in trackers.",
        checked = enabled, onToggle = onToggle)
}
