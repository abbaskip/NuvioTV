package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.extractTrackingYear
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamContentContext
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Catalogue matching only. Playback and tracker identities remain owned by the existing player. */
@Singleton
class IptvVodStreams @Inject constructor(
    private val repository: OnDemandRepository,
    private val metadata: MetaRepository,
    private val tmdb: TmdbService,
    private val preferences: LiveTvPreferences,
    private val profiles: ProfileManager
) {
    suspend fun revision(): String = MessageDigest.getInstance("SHA-256").digest(
        (preferences.playlists.first().filter { it.enabled && it.importVod && it.isXtream }
            .map { listOf(it.id, it.xtreamServer, it.xtreamUsername, it.xtreamPassword, it.userAgent) }.toString() + repository.version.value)
            .toByteArray()).joinToString("") { "%02x".format(it) }

    internal suspend fun prepare(type: String, videoId: String, season: Int?, episode: Int?,
        selection: StreamContentContext?): VodStreamTarget? {
        val kind = when (type) { "movie" -> VodKind.MOVIE; "series", "tv" -> VodKind.SERIES; else -> return null }
        val id = selection?.contentId?.takeIf { it.isNotBlank() } ?: when {
            videoId.startsWith("tt") -> videoId.substringBefore(':')
            videoId.startsWith("tmdb:") -> "tmdb:" + videoId.substringAfter(':').substringBefore(':')
            kind == VodKind.MOVIE -> videoId
            else -> return null
        }
        val ids = parseTrackingExternalIds(id)
        val needsMetadata = ids.imdb == null && ids.tmdb == null || selection == null || selection.title.isBlank() || selection.year == null ||
            kind == VodKind.SERIES && selection.episodeTitle.isNullOrBlank()
        val meta = if (needsMetadata) withTimeoutOrNull(15_000) {
            (metadata.getProviderMeta(type, id).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
        } else null
        val imdb = meta?.imdbId ?: ids.imdb
        val tmdbId = ids.tmdb?.toString() ?: if (tmdb.apiKey().isBlank()) null else withTimeoutOrNull(8_000) {
            try { tmdb.ensureTmdbId(imdb ?: id, type) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        val native = if (selection?.tvdb == null) meta?.videos?.singleOrNull {
            it.id == videoId || it.season == season && it.episode == episode
        } else null
        val context = selection?.tvdb
        val titles = linkedSetOf<String>()
        (selection?.episodeTitle ?: native?.title)?.takeIf { it.isNotBlank() }?.let(titles::add)
        context?.providerSources.orEmpty().filter { source ->
            context != null && context.episodeIds.isNotEmpty() && source.episodeIds.toSet() == context.episodeIds.toSet()
        }.forEach { titles += it.title }
        android.util.Log.d("IptvVod", "Catalogue target: kind=$kind tmdb=${tmdbId != null} imdb=${imdb != null} year=${selection?.year != null || meta != null} titles=${titles.size}")
        return VodStreamTarget(kind, tmdbId, selection?.title?.takeIf { it.isNotBlank() } ?: meta?.name.orEmpty(),
            selection?.year ?: extractTrackingYear(meta?.releaseInfo), titles,
            context?.episodeIds.orEmpty().toSet(), context?.episodeId,
            (native?.id ?: videoId).takeIf { it.matches(Regex("tt[0-9]+")) && it != id }, imdb)
    }

    internal suspend fun load(playlistKey: String, sourceName: String, target: VodStreamTarget?, profileId: Int): List<Stream> {
        if (target == null || profiles.activeProfileId.value != profileId) return emptyList()
        repository.start()
        val result = repository.integratedSources(target, playlistKey.removePrefix(IptvStreamSources.PREFIX))
        if (profiles.activeProfileId.value != profileId) return emptyList()
        return result.map { OnDemandStreams.stream(it, sourceName) }
    }
}
