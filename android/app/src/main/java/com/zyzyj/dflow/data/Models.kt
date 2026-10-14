package com.zyzyj.dflow.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DFlow API models.
 *
 * Field names mirror the server payloads exactly (the server proxies Danbooru's
 * own JSON, so the snake_case names are not ours to choose). Every field is
 * nullable or defaulted: the server trims empty strings, Danbooru omits fields
 * on some post types, and a strict model would fail to parse real responses.
 */

/** A Danbooru (or Pixiv-mapped) post as returned by the posts and explore endpoints. */
@Serializable
data class Post(
    val id: Long,
    val image_width: Int = 0,
    val image_height: Int = 0,
    val preview_file_url: String? = null,
    val large_file_url: String? = null,
    val file_url: String? = null,
    val rating: String = "g",
    val score: Int = 0,
    val fav_count: Int = 0,
    val created_at: String = "",
    val md5: String? = null,
    val file_ext: String = "",
    val file_size: Long = 0,
    val source: String = "",
    val tag_string: String = "",
    val tag_string_artist: String = "",
    val tag_string_character: String = "",
    val tag_string_copyright: String = "",
    val tag_string_general: String = "",
    @SerialName("pixiv_id") val pixivId: Long? = null,
) {
    val aspectRatio: Float
        get() = if (image_width > 0 && image_height > 0) {
            image_width.toFloat() / image_height.toFloat()
        } else 0.66f

    /** Best URL for a grid cell: small, cheap, always present when a post has an image. */
    val gridUrl: String?
        get() = preview_file_url ?: large_file_url ?: file_url

    /** Best URL for full-screen viewing. */
    val fullUrl: String?
        get() = file_url ?: large_file_url ?: preview_file_url

    val artist: String
        get() = tag_string_artist.split(' ').firstOrNull()?.replace('_', ' ') ?: ""

    /** Compact size line for the viewer's subtitle. */
    val dimensionLabel: String
        get() = if (image_width > 0 && image_height > 0) "${image_width}×${image_height}" else ""

    /**
     * Metadata shown in the viewer's bottom panel.
     *
     * Only non-empty lines are emitted, so the panel never shows a label with
     * nothing after it.
     */
    val detailText: String
        get() = buildList {
            if (id > 0) add("ID  $id")
            if (dimensionLabel.isNotBlank()) add("尺寸  $dimensionLabel")
            if (rating.isNotBlank()) add("分级  ${rating.uppercase()}")
            if (score > 0) add("评分  $score")
            if (fav_count > 0) add("收藏数  $fav_count")
            if (created_at.isNotBlank()) add("发布  ${created_at.take(10)}")
            if (tag_string_artist.isNotBlank()) add("画师  ${tag_string_artist.replace('_', ' ')}")
            if (tag_string_copyright.isNotBlank()) add("版权  ${tag_string_copyright.replace('_', ' ')}")
            if (tag_string_character.isNotBlank()) add("角色  ${tag_string_character.replace('_', ' ')}")
            if (tag_string_general.isNotBlank()) add("\n标签\n${tag_string_general.replace('_', ' ')}")
        }.joinToString("\n")
}

/**
 * A locally saved card. The server keeps these in its own state file; the fields
 * used by the UI are a subset of the 44 the server round-trips.
 */
@Serializable
data class Favorite(
    val id: String,
    val source: String = "local",
    val sourceId: String = "",
    val sourcePageUrl: String = "",
    val sourceImageUrl: String = "",
    val preview_file_url: String = "",
    val large_file_url: String = "",
    val file_url: String = "",
    val image_width: Int = 0,
    val image_height: Int = 0,
    val rating: String = "g",
    val title: String = "",
    val name: String = "",
    val author: String = "",
    val summary: String = "",
    val folder: String = "original",
    val preset: String = "",
    val reversePreset: String = "",
    val autoEnabled: Boolean = true,
    val queueOrder: Int = 0,
    val prompt: String = "",
    val resolvedPreset: String = "",
    val reverseStatus: String = "idle",
    val reverseError: String = "",
    val cacheStatus: String = "idle",
    val cacheFile: String = "",
    val cacheError: String = "",
    val tag_string_general: String = "",
    val tag_string_character: String = "",
    val tag_string_copyright: String = "",
    val created_at: String = "",
    val promptWrittenAt: String = "",
    val wordedAt: String = "",
    val completedAt: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val aspectRatio: Float
        get() = if (image_width > 0 && image_height > 0) {
            image_width.toFloat() / image_height.toFloat()
        } else 0.75f

    val isRemote: Boolean
        get() = source == "danbooru" || source == "pixiv"

    /** Display title: the site title, else the author, else a neutral placeholder. */
    val displayTitle: String
        get() = title.ifBlank { name.ifBlank { author.ifBlank { "本地卡片" } } }

    val hasPrompt: Boolean
        get() = prompt.isNotBlank()

    /** Folder name in Chinese, for the viewer subtitle. */
    val folderLabel: String
        get() = when (folder) {
            "original" -> "原始收藏"
            "pending" -> "待反推"
            "worded" -> "有词区"
            "completed" -> "已完成"
            else -> folder
        }

    /**
     * Metadata panel content for the viewer. Prompts are included in full — the
     * panel is the only place they can be read and selected without leaving the
     * image.
     */
    val detailText: String
        get() = buildList {
            if (image_width > 0 && image_height > 0) add("尺寸  ${image_width}×${image_height}")
            if (author.isNotBlank()) add("作者  $author")
            if (rating.isNotBlank()) add("分级  ${rating.uppercase()}")
            add("位置  $folderLabel")
            if (preset.isNotBlank()) add("扩写预设  $preset")
            if (reversePreset.isNotBlank()) add("反推预设  $reversePreset")
            if (cacheFile.isNotBlank()) add("缓存  $cacheFile")
            if (cacheError.isNotBlank()) add("缓存错误  $cacheError")
            if (reverseStatus.isNotBlank()) add("反推状态  $reverseStatus")
            if (reverseError.isNotBlank()) add("反推错误  $reverseError")
            if (tag_string_general.isNotBlank()) add("标签  ${tag_string_general.replace('_', ' ')}")
            if (prompt.isNotBlank()) add("\n提示词\n$prompt")
            if (summary.isNotBlank()) add("\n说明\n$summary")
        }.joinToString("\n")
}

@Serializable
data class WordedEntry(
    val id: String,
    val folder: String = "worded",
    val imageExt: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val positive: String = "",
    val summary: String = "",
    val preset: String = "",
    val reversePreset: String = "",
    val queueOrder: Int = 0,
    val autoEnabled: Boolean = true,
    val createdAt: String = "",
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0.75f
}

@Serializable
data class LocalGallery(
    val favorites: List<Favorite> = emptyList(),
    val entries: List<WordedEntry> = emptyList(),
)

@Serializable
data class Tag(
    val id: Long = 0,
    val name: String = "",
    val post_count: Long = 0,
    val category: Int = 0,
)

@Serializable
data class Account(
    val loginName: String = "",
    val loginKey: String = "",
    val pixivCookie: String = "",
    val pixivRefreshToken: String = "",
    val primaryTranslator: String = "deepl",
    val translateFallback: Boolean = true,
    val deeplKey: String = "",
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val volcAccessKey: String = "",
    val volcSecretKey: String = "",
    val googleTranslateKey: String = "",
    val proxyUrl: String = "",
    // Built-in vision model used for reverse-prompting without an external agent.
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiTemperature: Double = 0.7,
    val aiMaxTokens: Int = 2048,
    val aiCustomInstruction: String = "",
) {
    val aiConfigured: Boolean get() = aiBaseUrl.isNotBlank() && aiModel.isNotBlank()
}

@Serializable
data class Preferences(
    val mode: String = "latest",
    val columns: Int = 5,
    val ratings: List<String> = listOf("g", "s", "q", "e"),
    val pixivRating: String = "safe",
    val search: String = "",
    val selectedTags: List<String> = emptyList(),
)

@Serializable
data class AppState(
    val account: Account = Account(),
    val preferences: Preferences = Preferences(),
    val favorites: List<Favorite> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class PresetEntry(val name: String = "", val content: String = "")

@Serializable
data class PresetLibrary(
    val reverse: List<PresetEntry> = emptyList(),
    val expansion: List<PresetEntry> = emptyList(),
    val defaultReverse: String = "",
    val defaultExpansion: String = "",
)

@Serializable
data class ReverseQueue(
    val total: Int = 0,
    val withoutPrompt: Int = 0,
    val eligible: Int = 0,
    val items: List<Favorite> = emptyList(),
)

/** A connected MCP agent, from /api/mcp/sessions. */
@Serializable
data class McpSession(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val since: Long = 0,
    val seenAt: Long = 0,
)

// ---- built-in vision reverse ------------------------------------------------

@Serializable
data class AiModelList(val ok: Boolean = false, val models: List<String> = emptyList(), val baseUrl: String = "", val error: String = "")

@Serializable
data class AiReverseResult(val ok: Boolean = false, val favorite: Favorite? = null, val prompt: String = "", val error: String = "")

@Serializable
data class AiProgress(val running: Boolean = false, val text: String = "")

@Serializable
data class AiBatchStart(val ok: Boolean = false, val total: Int = 0, val error: String = "")

/** Live counters for a running batch reverse. */
@Serializable
data class AiBatch(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val currentId: String = "",
    val error: String = "",
) {
    val finished: Boolean get() = !running
    val processed: Int get() = done + failed
}

@Serializable
data class NetworkProbe(
    val name: String = "",
    val ok: Boolean = false,
    val status: Int = 0,
    val ms: Long = 0,
    val error: String = "",
)

@Serializable
data class NetworkTest(
    val ok: Boolean = false,
    val proxy: String = "",
    val explicit: Boolean = false,
    val results: List<NetworkProbe> = emptyList(),
    val ms: Long = 0,
)

@Serializable
data class VersionInfo(val version: String = "", val commit: String = "")

@Serializable
data class AppVersion(
    val ok: Boolean = false,
    val current: VersionInfo = VersionInfo(),
    val updateAvailable: Boolean = false,
)

/** Shape of the server's error replies: {"error": "...", "detail": "..."}. */
@Serializable
data class ApiError(val error: String = "", val detail: String = "")

/** PATCH /api/favorites/:id wraps the updated card. */
@Serializable
data class FavoriteDetail(val favorite: Favorite = Favorite(id = ""))

/**
 * Result of asking the server to queue a card for an online agent.
 *
 * The server answers 202 when it created a request, 200 when it reused an
 * existing one, and 409 with a plain `error` when a precondition fails (card not
 * in the pending folder, image not cached yet, or no MCP agent connected).
 */
@Serializable
data class ReverseDirectResult(
    val request: ReverseRequest? = null,
    val item: Favorite? = null,
)

@Serializable
data class ReverseRequest(
    val id: String = "",
    val itemId: String = "",
    val status: String = "",
    val agentName: String = "",
    val error: String = "",
    val createdAt: String = "",
)
