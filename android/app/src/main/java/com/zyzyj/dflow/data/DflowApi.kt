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

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
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
                    .put(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("无法连接本地服务，请确认服务正在运行", e.message.orEmpty())
        }
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

    suspend fun saveFavorites(favorites: List<Favorite>): String =
        putRaw("/api/favorites", json.encodeToString(FavoritesPayload.serializer(), FavoritesPayload(favorites)))

    suspend fun updateAccount(account: Account): String =
        putRaw("/api/account", json.encodeToString(Account.serializer(), account))

    suspend fun presets(): PresetLibrary = get("/api/mcp/presets")

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
}

@kotlinx.serialization.Serializable
internal data class FavoritesPayload(val favorites: List<Favorite>)
