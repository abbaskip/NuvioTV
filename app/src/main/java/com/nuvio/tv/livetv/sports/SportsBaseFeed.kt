package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared public catalogue only. No playlist, credentials, user preferences or Gemini output. */
internal class SportsBaseFeed(
    private val dir: File,
    private val url: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    allowLocalhost: Boolean = false
) {
    val enabled = url.isNotBlank()
    private val gate = Mutex()
    private val file = File(dir, "base-feed.json")
    private val stateFile = File(dir, "base-feed-state.json")
    private val backup = File(dir, "base-feed.backup")
    private var snapshot = runCatching { parse(file.readText(), clock()) }.getOrNull()
        ?: runCatching { parse(backup.readText(), clock()) }.getOrNull()
    private var etag: String? = null
    private var lastCheck = 0L
    private var nextAttempt = 0L

    init {
        if (enabled) {
            val endpoint = url.toHttpUrl()
            require(endpoint.isHttps || (allowLocalhost && endpoint.host in setOf("127.0.0.1", "localhost")))
            require(endpoint.username.isEmpty() && endpoint.password.isEmpty())
        }
        runCatching {
            val state = Json.parseToJsonElement(stateFile.readText()).jsonObject
            // A changed endpoint must never send another endpoint's validator or reuse its polling state.
            if (state["url"]?.jsonPrimitive?.content == url) {
                etag = state["etag"]?.jsonPrimitive?.contentOrNull
                lastCheck = state["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0
            }
        }
    }

    fun cached(): Snapshot? = snapshot?.takeIf { clock() - it.generatedAt < MAX_AGE }

    suspend fun refresh(): Snapshot? = gate.withLock {
        if (!enabled) return@withLock null
        val now = clock()
        if (now < nextAttempt || (cached() != null && now >= lastCheck && now - lastCheck < REFRESH)) return@withLock cached()
        // Failed/invalid feeds back off instead of triggering a per-device provider fan-out.
        nextAttempt = now + 15 * 60_000L
        runCatching {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .apply { if (cached() != null) etag?.let { header("If-None-Match", it) } }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    require(cached() != null)
                } else {
                    require(response.isSuccessful)
                    val body = response.body ?: error("Empty sports catalogue")
                    require(body.contentLength() <= MAX_BYTES)
                    val buffer = okio.Buffer()
                    while (buffer.size <= MAX_BYTES && body.source().read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size)) != -1L) { }
                    val bytes = buffer.readByteArray()
                    require(bytes.size <= MAX_BYTES)
                    val raw = bytes.toString(Charsets.UTF_8)
                    val incoming = parse(raw, now)
                    require(incoming.generatedAt >= (snapshot?.generatedAt ?: 0))
                    val temp = File(dir, "base-feed.tmp")
                    temp.writeText(raw)
                    backup.delete()
                    if (file.exists()) require(file.renameTo(backup))
                    if (!temp.renameTo(file)) {
                        backup.renameTo(file)
                        error("Unable to save sports catalogue")
                    }
                    backup.delete()
                    snapshot = incoming
                    etag = response.header("ETag")
                }
                lastCheck = now
                stateFile.writeText(buildJsonObject {
                    put("url", url); put("checkedAt", now); etag?.let { put("etag", it) }
                }.toString())
            }
        }
        cached()
    }

    data class Entry(val provider: String, val leagueId: String, val fetchedAt: Long, val payload: JsonObject)
    data class Snapshot(val generatedAt: Long, val entries: List<Entry>, val metadata: JsonObject)

    companion object {
        const val REFRESH = 6 * 60 * 60_000L
        const val MAX_AGE = 7 * 24 * 60 * 60_000L
        const val MAX_BYTES = 12L * 1024 * 1024
        fun parse(raw: String, now: Long): Snapshot {
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1)
            val generated = root["generatedAt"]?.jsonPrimitive?.longOrNull ?: error("Missing generation time")
            require(generated in (now - MAX_AGE)..(now + 10 * 60_000L))
            val ids = HashSet<String>()
            val entries = root["entries"]!!.jsonArray.map { item ->
                val o = item.jsonObject
                val provider = o["provider"]!!.jsonPrimitive.content
                val id = o["leagueId"]!!.jsonPrimitive.content
                require(provider in setOf("espn", "tsdb", "calendar"))
                require(id.matches(Regex("[a-z0-9/_.-]{1,100}")) && ids.add("$provider:$id"))
                val at = o["fetchedAt"]!!.jsonPrimitive.long
                require(at in (now - MAX_AGE)..(generated + 10 * 60_000L))
                val payload = o["payload"]!!.jsonObject
                require(payload["events"] is JsonArray)
                Entry(provider, id, at, payload)
            }
            require(entries.isNotEmpty())
            return Snapshot(generated, entries, root["metadata"]?.jsonObject ?: buildJsonObject { put("leagues", JsonArray(emptyList())) })
        }

        /** A six-hour catalogue snapshot cannot act as a live-score feed. */
        fun scheduled(event: SportsEvent, fetchedAt: Long): SportsEvent = if (event.state == GameState.LIVE)
            event.copy(state = GameState.PRE, status = "Live score pending", away = event.away?.copy(score = null),
                home = event.home?.copy(score = null), progress = null, situation = null, standings = emptyList(),
                updatedAt = fetchedAt)
            else event.copy(updatedAt = fetchedAt)
    }
}

/** At most two relevant league/date scoreboards per round; catalogue dates never fan out on the TV. */
internal object SportsRefreshPolicy {
    fun choose(events: List<SportsEvent>, leagues: List<League>, focused: String?, playing: Set<String>,
               followed: Set<String>, viewing: Boolean, now: Long, attempts: Map<String, Long>): List<League> {
        val byId = leagues.associateBy { it.id }
        val current = events.filter { e ->
            val league = byId[e.leagueId]
            league?.espnPath != null && (e.hasTime || e.endMs != null) && e.state != GameState.POST && e.state != GameState.OFF &&
                now >= e.startMs - 10 * 60_000L && now <= (e.endMs?.let { it + if (e.hasTime) 0L else 24 * 60 * 60_000L } ?: (e.startMs + league.minutes * 60_000L)) + 30 * 60_000L
        }.groupBy { it.leagueId }
        fun priority(id: String): Int = when {
            current[id].orEmpty().any { it.id == focused || it.id in playing } -> 0
            id in followed -> 1
            else -> 2
        }
        return current.keys.filter { id -> (viewing || priority(id) == 0) && now - (attempts[id] ?: 0) >= 60_000 }
            .sortedWith(compareBy<String> { if (priority(it) == 0) 0 else 1 }
                .thenBy { attempts[it] ?: 0L }.thenBy { priority(it) })
            .take(2).mapNotNull(byId::get)
    }
}
