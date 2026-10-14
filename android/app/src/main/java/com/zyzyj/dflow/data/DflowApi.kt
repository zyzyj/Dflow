package com.zyzyj.dflow.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talk to the DFlow server running inside the app's own Linux sandbox.
 *
 * The server keeps doing the hard parts — proxying images past the CDN's
 * referer/UA checks, rate limiting, thumbnail generation, translation — so this
 * client only marshals JSON over loopback.
 */
class DflowApi(private val port: Int) {

    private val json = Json {
        // Danbooru omits fields (has_children, parent_id ...) that our models do
        // not declare, and the server trims empty ones. Strict parsing would
        // reject perfectly good responses.
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Generous read timeout: a cold gallery request may wait on an upstream
        // site that is slow to answer.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Wraps a failure with a message worth showing to the user. */
    class ApiException(message: String, val detail: String = "") : Exception(message)

    private fun url(path: String) = baseUrl + path

    /**
     * Sends a request and returns its body.
     *
     * [callTimeoutSeconds] overrides the shared client's timeouts for this one
     * call. OkHttp applies a per-call timeout when one is set, which is how a
     * multi-minute model call can coexist with the short default used everywhere
     * else.
     */
    private fun execute(request: Request, callTimeoutSeconds: Long = 0): String {
        val call = client.newCall(request)
        if (callTimeoutSeconds > 0) {
            call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
        }
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The server answers errors with {"error":...,"detail":...}; fall
                // back to the raw body when it is something else (proxy HTML, etc).
                val parsed = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
                throw ApiException(
                    parsed?.error?.ifBlank { null } ?: "请求失败（HTTP ${response.code}）",
                    parsed?.detail.orEmpty(),
                )
            }
            return body
        }
    }

    private suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url(url(path)).get().build())
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("无法连接本地服务，请确认服务正在运行", e.message.orEmpty())
        }
    }

    private suspend fun putRaw(path: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        try {
            execute(
                Request.Builder()
                    .url(url(path))
                    .put(jsonBody.toRequestBody(JSON_MEDIA))
                    .build()
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("无法连接本地服务，请确认服务正在运行", e.message.orEmpty())
        }
    }

    private suspend fun patchRaw(path: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        try {
            execute(
                Request.Builder()
                    .url(url(path))
                    .patch(jsonBody.toRequestBody(JSON_MEDIA))
                    .build()
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("无法连接本地服务，请确认服务正在运行", e.message.orEmpty())
        }
    }

    private suspend fun postRaw(
        path: String,
        jsonBody: String,
        callTimeoutSeconds: Long = 0,
    ): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url(path))
                .post(jsonBody.toRequestBody(JSON_MEDIA))
                .build()
            // A vision model can take minutes on a large image, far beyond the
            // shared client's read timeout. Without a per-call override the
            // request is killed mid-generation and surfaces as a generic network
            // error, which makes a slow model look like a broken one.
            execute(request, callTimeoutSeconds)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("无法连接本地服务，请确认服务正在运行", e.message.orEmpty())
        }
    }

    /** Minimal JSON string escaping for hand-built request bodies. */
    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(getRaw(path))

    // ---- gallery browsing --------------------------------------------------

    suspend fun posts(limit: Int = 40, page: Int = 1, tags: String = "", rating: String = ""): List<Post> {
        val query = buildString {
            append("?limit=$limit&page=$page")
            if (tags.isNotBlank()) append("&tags=${encode(tags)}")
            if (rating.isNotBlank()) append("&rating=${encode(rating)}")
        }
        return get("/api/posts$query")
    }

    suspend fun popular(scale: String = "day", limit: Int = 40, page: Int = 1): List<Post> =
        get("/api/explore/popular?scale=$scale&limit=$limit&page=$page")

    suspend fun viewed(limit: Int = 40, page: Int = 1): List<Post> =
        get("/api/explore/viewed?limit=$limit&page=$page")

    suspend fun trending(limit: Int = 40, page: Int = 1): List<Post> =
        get("/api/posts?limit=$limit&page=$page&tags=order%3Arank")

    suspend fun hotTags(): List<Tag> = get("/api/tags")

    suspend fun searchTags(query: String): List<Tag> =
        get("/api/tags?search[name_matches]=*${encode(query)}*&search[order]=count")

    // ---- pixiv -------------------------------------------------------------

    suspend fun pixivRanking(mode: String = "daily", page: Int = 1): List<Post> =
        get("/api/pixiv/ranking?mode=$mode&page=$page")

    suspend fun pixivSearch(tags: String, page: Int = 1): List<Post> =
        get("/api/pixiv/search?tags=${encode(tags)}&page=$page")

    // ---- local library -----------------------------------------------------

    suspend fun localGallery(): LocalGallery = get("/api/local-gallery")

    suspend fun reverseQueue(): ReverseQueue = get("/api/reverse/queue")

    suspend fun metadataImages(): List<Favorite> = get("/api/metadata/images")

    /**
     * Saves the whole favourites list.
     *
     * The server treats this as authoritative and reconciles against its own
     * copy: fields it owns (folder, prompt, reverseStatus, cache metadata) are
     * preserved from the existing record, so a stale client cannot clobber a
     * worker's result. Send only identity plus display fields for new cards.
     */
    suspend fun saveFavorites(favorites: List<Favorite>): String =
        putRaw("/api/favorites", json.encodeToString(FavoritesPayload.serializer(), FavoritesPayload(favorites)))

    /**
     * Applies a partial update to one card. This is how a card moves between
     * folders and how a prompt gets written:
     *  - folder=pending   → enqueue for reverse-prompting
     *  - prompt="..."     → store the prompt and move to the worded folder
     *  - folder=completed → release to the archive (the server refuses without a
     *                       prompt and a downloaded image)
     *
     * [id] must be the card's raw id. For site cards the server requires a plain
     * numeric id and silently drops anything else while saving, so "post_123"
     * would vanish from the list instead of being reported as an error.
     */
    suspend fun patchFavorite(
        id: String,
        folder: String? = null,
        prompt: String? = null,
        preset: String? = null,
        reversePreset: String? = null,
        autoEnabled: Boolean? = null,
        customInstruction: String? = null,
        retryCache: Boolean? = null,
        retryReverse: Boolean? = null,
    ): Favorite {
        val fields = mutableListOf<String>()
        folder?.let { fields += "\"folder\":${quote(it)}" }
        prompt?.let { fields += "\"prompt\":${quote(it)}" }
        preset?.let { fields += "\"preset\":${quote(it)}" }
        reversePreset?.let { fields += "\"reversePreset\":${quote(it)}" }
        autoEnabled?.let { fields += "\"autoEnabled\":$it" }
        customInstruction?.let { fields += "\"customInstruction\":${quote(it)}" }
        retryCache?.let { fields += "\"retryCache\":$it" }
        retryReverse?.let { fields += "\"retryReverse\":$it" }
        val body = patchRaw("/api/favorites/${encode(id)}", "{${fields.joinToString(",")}}")
        return json.decodeFromString<FavoriteDetail>(body).favorite
    }

    /** Asks the server to queue this card for an online agent to reverse-prompt. */
    suspend fun directReverse(id: String): ReverseDirectResult {
        val body = postRaw("/api/reverse/${encode(id)}/direct", "{}")
        return json.decodeFromString(body)
    }

    suspend fun presets(): PresetLibrary = get("/api/mcp/presets")

    /**
     * Connected MCP agents.
     *
     * Reverse-prompting is done by an external agent that reads the image and
     * writes the prompt back; the server only queues and stores. This tells the
     * UI whether handing a card off can work at all, so the button can be
     * disabled with a reason instead of failing on tap.
     */
    suspend fun mcpSessions(): List<McpSession> = get("/api/mcp/sessions")

    // ---- built-in vision reverse ------------------------------------------

    /** Lists models offered by the configured endpoint. */
    suspend fun aiModels(baseUrl: String, apiKey: String): List<String> =
        postRaw("/api/ai/models", "{\"aiBaseUrl\":${quote(baseUrl)},\"aiApiKey\":${quote(apiKey)}}")
            .let { json.decodeFromString<AiModelList>(it).models }

    /** Reverses one card with the built-in model. Long-running: a vision call
     *  on a large image can take a while. */
    suspend fun aiReverse(id: String): AiReverseResult =
        postRaw("/api/ai/reverse/${encode(id)}", "{}", callTimeoutSeconds = AI_CALL_TIMEOUT_SECONDS)
            .let { json.decodeFromString(it) }

    /**
     * Progress of a running built-in reverse, polled while the request is in
     * flight so the user sees the prompt being written instead of a spinner.
     */
    suspend fun aiProgress(id: String): AiProgress =
        get("/api/ai/progress/${encode(id)}")

    suspend fun aiCancel(id: String): String = postRaw("/api/ai/cancel/${encode(id)}", "{}")

    suspend fun aiReverseBatch(): AiBatchStart =
        postRaw("/api/ai/reverse-batch", "{}").let { json.decodeFromString(it) }

    suspend fun aiBatch(): AiBatch = get("/api/ai/batch")

    suspend fun aiStopBatch(): String = postRaw("/api/ai/batch/stop", "{}")

    suspend fun updateAccount(account: Account): String =
        putRaw("/api/account", json.encodeToString(Account.serializer(), account))

    // ---- settings / diagnostics -------------------------------------------

    suspend fun state(): AppState = get("/api/state")

    suspend fun networkTest(timeoutMs: Int = 12000): NetworkTest =
        get("/api/network/test?timeout=$timeoutMs")

    suspend fun version(): AppVersion = get("/api/version")

    // ---- image URLs --------------------------------------------------------

    /**
     * Routes an image through the server's proxy.
     *
     * Fetching cdn.donmai.us directly fails: it rejects requests whose User-Agent
     * or Referer do not look right, and those headers cannot be set from an app
     * the way it needs. The server sends the exact headers the CDN expects.
     */
    fun proxyImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Local cards already live on the server; their ids are resolved separately.
        return "$baseUrl/api/image?url=${encode(url)}"
    }

    /** A locally cached card image, optionally the half-size WebP derivative. */
    fun localImage(id: String, thumbnail: Boolean = true): String =
        "$baseUrl/api/reverse/image/${encode(id)}?thumbnail=${if (thumbnail) 1 else 0}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()

        /**
         * Ceiling for one built-in reverse call.
         *
         * Chosen to outlast a slow vision model on a large image while still
         * failing eventually; the user can cancel earlier from the UI.
         */
        const val AI_CALL_TIMEOUT_SECONDS = 600L
    }
}

@kotlinx.serialization.Serializable
internal data class FavoritesPayload(val favorites: List<Favorite>)
