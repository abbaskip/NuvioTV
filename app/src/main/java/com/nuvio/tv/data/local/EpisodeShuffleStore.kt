package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

data class EpisodeShuffleProfile(
    val profileId: Int,
    val available: Boolean = false,
    val shows: Map<String, EpisodeShuffleSettings> = emptyMap()
) {
    fun settings(contentId: String, contentType: String): EpisodeShuffleSettings {
        val saved = shows[contentId] ?: EpisodeShuffleSettings()
        return saved.copy(enabled = saved.enabled && available &&
            (contentType.equals("series", true) || contentType.equals("tv", true)))
    }
}

@Singleton
class EpisodeShuffleStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val layoutPreferences: LayoutPreferenceDataStore
) {
    val profiles = profileManager.activeProfileId.flatMapLatest(::observeProfile)

    fun observeProfile(profileId: Int) = combine(
        factory.get(profileId, "episode_shuffle").data,
        layoutPreferences.randomEpisodeEnabledForProfile(profileId)
    ) { preferences, available ->
        EpisodeShuffleProfile(profileId, available, readShuffleSettings(preferences))
    }

    /** Explicit saved choices include disabled shows; a fresh profile exports no deletions. */
    suspend fun exportSelections(profileId: Int): Map<String, String> =
        readShuffleSettings(factory.get(profileId, "episode_shuffle").data.first()).mapValues { (_, settings) ->
            JSONObject().put("enabled", settings.enabled).put("includeWatched", settings.includeWatched).toString()
        }

    fun selectionChanges(profileId: Int) = factory.get(profileId, "episode_shuffle").data
        .map(::readShuffleSettings).distinctUntilChanged().map { Unit }

    suspend fun importSelections(profileId: Int, values: Map<String, String>) {
        require(profileId in 1..ProfileManager.MAX_PROFILES)
        val settings = decodeShuffleSelections(values)
        factory.get(profileId, "episode_shuffle").edit { preferences ->
            preferences.asMap().keys.filter { it.name.startsWith("enabled:") || it.name.startsWith("watched:") }.forEach {
                @Suppress("UNCHECKED_CAST")
                preferences.remove(it as Preferences.Key<Any>)
            }
            settings.forEach { (id, choice) ->
                preferences[booleanPreferencesKey("enabled:$id")] = choice.enabled
                preferences[booleanPreferencesKey("watched:$id")] = choice.includeWatched
            }
        }
    }

    suspend fun save(contentId: String, settings: EpisodeShuffleSettings, profileId: Int) {
        if (contentId.isBlank()) return
        factory.get(profileId, "episode_shuffle").edit {
            it[booleanPreferencesKey("enabled:$contentId")] = settings.enabled
            it[booleanPreferencesKey("watched:$contentId")] = settings.includeWatched
        }
    }
}

internal fun readShuffleSettings(preferences: Preferences): Map<String, EpisodeShuffleSettings> =
    preferences.asMap().keys.mapNotNull { key ->
        key.name.takeIf { it.startsWith("enabled:") || it.startsWith("watched:") }
            ?.substringAfter(':')?.takeIf(String::isNotBlank)
    }.distinct().associateWith { id ->
        EpisodeShuffleSettings(
            enabled = preferences[booleanPreferencesKey("enabled:$id")] ?: false,
            includeWatched = preferences[booleanPreferencesKey("watched:$id")] ?: false
        )
    }

internal fun decodeShuffleSelections(values: Map<String, String>): Map<String, EpisodeShuffleSettings> =
    values.mapValues { (id, raw) ->
        require(id.isNotBlank()) { "Invalid Shuffle series ID" }
        val value = JSONObject(raw)
        require(value.keys().asSequence().toSet() == setOf("enabled", "includeWatched") &&
            value.opt("enabled") is Boolean && value.opt("includeWatched") is Boolean) { "Invalid Shuffle choice" }
        EpisodeShuffleSettings(value.getBoolean("enabled"), value.getBoolean("includeWatched"))
    }
