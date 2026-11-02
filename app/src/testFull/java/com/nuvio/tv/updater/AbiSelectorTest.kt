package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.*
import org.junit.Test

class AbiSelectorTest {
    private fun assets(vararg names: String) = names.map { GitHubAssetDto(it, "https://example.test/$it") }
    @Test fun universalWinsForEveryDeviceAndAssetOrder() {
        val apks = assets("ReSeq-v1-arm64-v8a.apk", "ReSeq-v1-x86.apk", "ReSeq-v1-universal.apk")
        for (abis in listOf(listOf("armeabi-v7a"), listOf("arm64-v8a"), listOf("x86"), listOf("x86_64"), emptyList())) {
            assertEquals("ReSeq-v1-universal.apk", AbiSelector.chooseBestApkAsset(apks, abis)?.name)
            assertEquals("ReSeq-v1-universal.apk", AbiSelector.chooseBestApkAsset(apks.reversed(), abis)?.name)
        }
    }
    @Test fun incompatibleSingleAssetIsRejected() {
        assertNull(AbiSelector.chooseBestApkAsset(assets("ReSeq-arm64-v8a.apk"), listOf("armeabi-v7a")))
        assertNull(AbiSelector.chooseBestApkAsset(assets("ReSeq-x86_64.apk"), listOf("x86")))
    }
    @Test fun compatibleFallbackUsesDeviceOrder() {
        assertEquals("ReSeq-arm64-v8a.apk", AbiSelector.chooseBestApkAsset(
            assets("ReSeq-armeabi-v7a.apk", "ReSeq-arm64-v8a.apk"), listOf("arm64-v8a", "armeabi-v7a"))?.name)
    }
    @Test fun legacyUnlabelledApkAndNonApkAssets() {
        assertEquals("app-full-release.apk", AbiSelector.chooseBestApkAsset(
            assets("SHA256SUMS.txt", "app-full-release.apk"), emptyList())?.name)
        assertNull(AbiSelector.chooseBestApkAsset(assets("checksums.txt"), listOf("x86")))
        assertNull(AbiSelector.chooseBestApkAsset(assets("one.apk", "two.apk"), listOf("x86")))
    }
}
