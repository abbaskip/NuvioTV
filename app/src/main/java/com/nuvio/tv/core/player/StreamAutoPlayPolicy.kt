package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(playerSettings: PlayerSettings): Boolean {
        if (playerSettings.streamReuseLastLinkEnabled) return true
        val addons = playerSettings.streamAutoPlayAddonsEnabled
            ?: (playerSettings.streamAutoPlaySource != com.nuvio.tv.data.local.StreamAutoPlaySource.ENABLED_PLUGINS_ONLY)
        val plugins = com.nuvio.tv.core.build.AppFeaturePolicy.pluginsEnabled && (playerSettings.streamAutoPlayPluginsEnabled
            ?: (playerSettings.streamAutoPlaySource != com.nuvio.tv.data.local.StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        if (!addons && !plugins && !playerSettings.streamAutoPlayPlaylistsEnabled) return false
        if (playerSettings.streamAutoPlayReuseBingeGroup &&
            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return true

        return when (playerSettings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
