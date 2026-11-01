package com.nuvio.tv.livetv.ondemand

import android.content.Context
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnDemandEntryPoint {
    fun onDemandRepository(): OnDemandRepository
}

fun onDemandRepository(context: Context): OnDemandRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java).onDemandRepository()

/** Stream presentation shared by the integrated playlist sources and On Demand browser. */
object OnDemandStreams {
    const val GROUP_NAME = "📡 On Demand"

    internal fun stream(s: OnDemandRepository.OnDemandSource, sourceName: String): Stream {
        val t = s.tech
        val quality = t?.quality
        return Stream(name = listOfNotNull(sourceName, quality).joinToString("\n"), title = s.label,
            description = describe(s), url = s.url, ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = StreamBehaviorHints(notWebReady = if (s.headers.isEmpty()) null else true,
                bingeGroup = "iptv-playlist:${s.playlistId}", countryWhitelist = null,
                proxyHeaders = if (s.headers.isEmpty()) null else ProxyHeaders(request = s.headers, response = null),
                videoSize = t?.estimatedBytes, filename = releaseName(s)),
            addonName = sourceName, addonLogo = null, quality = quality, qualityValue = t?.height ?: -1,
            iptvPlaylistId = s.playlistId)
    }

    /**
     * The card text, laid out like popular stream addons:
     *   🎬 Obsession (2025)
     *   💾 ~5.4 GB | 🗣 English | 📡 My provider
     *   🎞 4K · HEVC · Dolby Vision · 23.98 fps  🔊 E-AC3 5.1
     */
    private fun describe(s: OnDemandRepository.OnDemandSource): String {
        val t = s.tech
        val head = "🎬 " + s.title + (s.year?.let { " ($it)" } ?: "") + (s.episodeTag?.let { " · $it" } ?: "")
        val line2 = listOfNotNull(
            t?.estimatedBytes?.let { "💾 ~" + formatSize(it) },
            t?.language?.let { "🗣 $it" },
            "📡 ${s.providerName}"
        ).joinToString(" | ")
        val video = listOfNotNull(
            t?.quality,
            t?.videoCodec?.let { codecName(it) },
            t?.hdr,
            t?.fps?.let { "%.2f fps".format(it).replace(".00", "") }
        ).joinToString(" · ")
        val audio = listOfNotNull(t?.audioCodec?.let { codecName(it) }, t?.channels?.let { channelsName(it) }).joinToString(" ")
        val line3 = listOfNotNull(
            video.takeIf { it.isNotBlank() }?.let { "🎞 $it" },
            audio.takeIf { it.isNotBlank() }?.let { "🔊 $it" },
            t?.durationSecs?.let { "⏱ ${it / 3600}h ${(it % 3600) / 60}m" }
        ).joinToString("  ")
        return listOf(head, line2, line3).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** e.g. "Obsession.2025.2160p.HEVC.DV.EAC3.5.1.mkv" */
    private fun releaseName(s: OnDemandRepository.OnDemandSource): String {
        val t = s.tech
        val res = when (t?.quality) { "4K" -> "2160p"; "1080p" -> "1080p"; "720p" -> "720p"; "SD" -> "480p"; else -> null }
        val hdr = when (t?.hdr) { "Dolby Vision" -> "DV"; "HDR10" -> "HDR10"; "HLG" -> "HLG"; else -> null }
        return listOfNotNull(
            s.title.replace(Regex("""[^\p{L}\p{N}]+"""), ".").trim('.'),
            s.year?.toString(),
            s.episodeTag,
            res,
            t?.videoCodec?.let { codecName(it) },
            hdr,
            t?.audioCodec?.let { codecName(it) },
            t?.channels?.let { channelsName(it) }
        ).joinToString(".") + "." + (s.ext ?: "mp4")
    }

    private fun codecName(c: String): String = when (c.lowercase()) {
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "mpeg2video" -> "MPEG-2"
        "eac3" -> "E-AC3"
        "ac3" -> "AC3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        else -> c.uppercase()
    }

    private fun channelsName(n: Int): String = when (n) {
        1 -> "Mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch"
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) "%.1f GB".format(gb) else "%d MB".format(bytes / 1_000_000)
    }
}
