package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {
    private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
    ): GitHubAssetDto? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // Universal remains the automatic-update default, independent of asset order.
        apks.firstOrNull { hasToken(it.name, "universal") }?.let { return it }
        for (abi in supportedAbis) {
            apks.firstOrNull { asset ->
                // Match the longest known ABI first: x86_64 is never an x86 APK.
                knownAbis.firstOrNull { hasToken(asset.name, it) } == abi
            }?.let { return it }
        }
        // Retain old single, unlabelled universal releases. Never fall back to a
        // labelled incompatible build, even if it is the release's only asset.
        return apks.singleOrNull()?.takeIf { asset -> knownAbis.none { hasToken(asset.name, it) } }
    }

    private fun hasToken(name: String, token: String): Boolean =
        Regex("(?:^|[-_.])${Regex.escape(token)}(?=[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
