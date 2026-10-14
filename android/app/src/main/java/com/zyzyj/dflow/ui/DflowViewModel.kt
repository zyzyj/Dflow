package com.zyzyj.dflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyzyj.dflow.data.Account
import com.zyzyj.dflow.data.AiBatch
import com.zyzyj.dflow.data.AppState
import com.zyzyj.dflow.data.DflowApi
import com.zyzyj.dflow.data.Favorite
import com.zyzyj.dflow.data.McpSession
import com.zyzyj.dflow.data.NetworkTest
import com.zyzyj.dflow.data.Post
import com.zyzyj.dflow.data.PresetLibrary
import com.zyzyj.dflow.data.Tag
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Gallery sources the user can switch between. */
enum class BrowseMode(val label: String, val station: Station) {
    Latest("最新", Station.DANBOORU),
    PopularDay("日热门", Station.DANBOORU),
    PopularWeek("周热门", Station.DANBOORU),
    PopularMonth("月热门", Station.DANBOORU),
    Viewed("日浏览", Station.DANBOORU),
    Trending("趋势", Station.DANBOORU),
    PixivDaily("P站日榜", Station.PIXIV),
    PixivWeekly("P站周榜", Station.PIXIV),
    PixivMonthly("P站月榜", Station.PIXIV),
}

enum class Station { DANBOORU, PIXIV }

/** Which tab of the local library is showing. */
enum class LibraryFolder(val label: String, val key: String) {
    Original("原始收藏", "original"),
    Pending("待反推", "pending"),
    Worded("有词区", "worded"),
    Completed("已完成", "completed"),
}

data class BrowseUiState(
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val errorDetail: String = "",
    val page: Int = 1,
    val endReached: Boolean = false,
    val query: String = "",
    val mode: BrowseMode = BrowseMode.Latest,
    val hotTags: List<Tag> = emptyList(),
)

data class LibraryUiState(
    val favorites: List<Favorite> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val errorDetail: String = "",
    val folder: LibraryFolder = LibraryFolder.Original,
    /** Connected agents. Reverse-prompting needs one; empty means the handoff
     *  button cannot succeed, and the UI says so instead of failing on tap. */
    val agents: List<McpSession> = emptyList(),
)

data class SettingsUiState(
    val state: AppState = AppState(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val networkTest: NetworkTest? = null,
    val testing: Boolean = false,
    val presets: PresetLibrary = PresetLibrary(),
)

/**
 * State of the built-in vision reverse page.
 *
 * Kept apart from [SettingsUiState] because it has its own lifecycle: the model
 * list is fetched on demand, and a running reverse produces streaming progress
 * that must not re-render the whole settings screen.
 */
data class AiUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val customInstruction: String = "",
    val models: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
    val modelsError: String? = null,
    /** Live text of the card being reversed, for progress display. */
    val progressText: String = "",
    val progressId: String = "",
    val batch: AiBatch = AiBatch(),
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * Holds screen state and talks to the embedded server.
 *
 * One view model for the whole app because every screen reads from the same
 * server state; splitting them would mean duplicating the account and library
 * caches that the settings and gallery screens both need.
 */
class DflowViewModel(private val api: DflowApi) : ViewModel() {

    private val _browse = MutableStateFlow(BrowseUiState())
    val browse: StateFlow<BrowseUiState> = _browse.asStateFlow()

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _ai = MutableStateFlow(AiUiState())
    val ai: StateFlow<AiUiState> = _ai.asStateFlow()

    private var browseJob: Job? = null
    private var batchPollJob: Job? = null
    private var progressPollJob: Job? = null

    // ---- browsing ----------------------------------------------------------

    fun setMode(mode: BrowseMode) {
        if (_browse.value.mode == mode) return
        _browse.update { it.copy(mode = mode) }
        refresh()
    }

    fun setQuery(query: String) {
        _browse.update { it.copy(query = query) }
    }

    fun submitSearch(query: String) {
        _browse.update { it.copy(query = query) }
        refresh()
    }

    fun clearSearch() {
        _browse.update { it.copy(query = "") }
        refresh()
    }

    fun refresh() {
        browseJob?.cancel()
        val current = _browse.value
        _browse.update { it.copy(loading = true, error = null, errorDetail = "", page = 1, endReached = false) }
        browseJob = viewModelScope.launch {
            try {
                val page = fetchPage(current.mode, current.query, 1)
                _browse.update {
                    it.copy(posts = page, loading = false, page = 1, endReached = page.isEmpty())
                }
            } catch (e: DflowApi.ApiException) {
                _browse.update {
                    it.copy(loading = false, error = e.message, errorDetail = e.detail, posts = emptyList())
                }
            }
        }
    }

    fun loadMore() {
        val current = _browse.value
        if (current.loading || current.loadingMore || current.endReached) return
        _browse.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val next = current.page + 1
                val page = fetchPage(current.mode, current.query, next)
                _browse.update {
                    // Guard against a refresh landing while this page was in flight.
                    if (it.page != current.page) it
                    else it.copy(
                        posts = it.posts + page,
                        loadingMore = false,
                        page = next,
                        endReached = page.isEmpty(),
                    )
                }
            } catch (e: DflowApi.ApiException) {
                _browse.update { it.copy(loadingMore = false, error = e.message, errorDetail = e.detail) }
            }
        }
    }

    private suspend fun fetchPage(mode: BrowseMode, query: String, page: Int): List<Post> {
        val limit = 40
        return if (query.isNotBlank()) {
            when (mode.station) {
                Station.DANBOORU -> api.posts(limit = limit, page = page, tags = query)
                Station.PIXIV -> api.pixivSearch(query, page)
            }
        } else when (mode) {
            BrowseMode.Latest -> api.posts(limit = limit, page = page)
            BrowseMode.PopularDay -> api.popular("day", limit, page)
            BrowseMode.PopularWeek -> api.popular("week", limit, page)
            BrowseMode.PopularMonth -> api.popular("month", limit, page)
            BrowseMode.Viewed -> api.viewed(limit, page)
            BrowseMode.Trending -> api.trending(limit, page)
            BrowseMode.PixivDaily -> api.pixivRanking("daily", page)
            BrowseMode.PixivWeekly -> api.pixivRanking("weekly", page)
            BrowseMode.PixivMonthly -> api.pixivRanking("monthly", page)
        }
    }

    fun loadHotTags() {
        if (_browse.value.hotTags.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { api.hotTags() }
                .onSuccess { tags -> _browse.update { it.copy(hotTags = tags.take(30)) } }
        }
    }

    // ---- local library -----------------------------------------------------

    fun setFolder(folder: LibraryFolder) {
        if (_library.value.folder == folder) return
        _library.update { it.copy(folder = folder) }
    }

    fun refreshLibrary() {
        _library.update { it.copy(loading = true, error = null, errorDetail = "") }
        viewModelScope.launch {
            try {
                // /api/state carries favourites and is what the server always
                // keeps current; /api/local-gallery adds the handwritten worded
                // entries. Favourites come from state so the count is never stale.
                val state = api.state()
                val agents = runCatching { api.mcpSessions() }.getOrDefault(emptyList())
                _library.update { it.copy(favorites = state.favorites, loading = false, agents = agents) }
                _settings.update { it.copy(state = state) }
            } catch (e: DflowApi.ApiException) {
                _library.update {
                    it.copy(loading = false, error = e.message, errorDetail = e.detail)
                }
            }
        }
    }

    fun toggleFavorite(post: Post, existing: Favorite?) {
        viewModelScope.launch {
            try {
                val current = api.state().favorites.toMutableList()
                if (existing != null) {
                    current.removeAll { it.id == existing.id }
                } else {
                    current.add(0, post.toFavorite())
                }
                api.saveFavorites(current)
                _library.update { it.copy(favorites = current) }
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    /** Removes a saved card. Separate from [toggleFavorite] because a library card
     *  has no originating [Post] to match against. */
    fun removeFavorite(favorite: Favorite) {
        viewModelScope.launch {
            try {
                val current = api.state().favorites.filterNot { it.id == favorite.id }
                api.saveFavorites(current)
                _library.update { it.copy(favorites = current) }
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    /** True when this post is already saved, so the heart renders filled. */
    fun isFavorite(post: Post, favorites: List<Favorite>): Boolean =
        findFavorite(favorites, post) != null

    /** The saved counterpart of a browsed post, if the user already saved it. */
    fun findFavorite(favorites: List<Favorite>, post: Post): Favorite? =
        favorites.firstOrNull {
            it.sourceId == post.id.toString() || it.id == post.id.toString()
        }

    // ---- reverse-prompt workflow -------------------------------------------

    /**
     * Moves a card into the pending queue, which is what makes it eligible for
     * reverse-prompting. The server assigns the queue position and starts
     * downloading the full-size image; the agent cannot read the image until
     * that download finishes, so the UI has to show cache progress rather than
     * let the user fire off a direct request immediately.
     */
    fun enqueueForReverse(favorite: Favorite) {
        runPatch(favorite.id, folder = "pending", successMessage = "已加入待反推队列")
    }

    /** Writes a prompt to a card. The server moves it to the worded folder. */
    fun writePrompt(favorite: Favorite, prompt: String) {
        if (prompt.isBlank()) return
        runPatch(favorite.id, prompt = prompt, successMessage = "提示词已保存")
    }

    /** Releases a finished card to the archive. The server validates readiness. */
    fun releaseToCompleted(favorite: Favorite) {
        runPatch(favorite.id, folder = "completed", successMessage = "已归档")
    }

    /** Puts a card back into the original folder. */
    fun moveToOriginal(favorite: Favorite) {
        runPatch(favorite.id, folder = "original", successMessage = "已移回原始收藏")
    }

    /** Retries a failed image download. */
    fun retryCache(favorite: Favorite) {
        runPatch(favorite.id, retryCache = true, successMessage = "已重新开始下载")
    }

    /** Retries a failed reverse-prompt job, re-queuing it. */
    fun retryReverse(favorite: Favorite) {
        runPatch(favorite.id, retryReverse = true, successMessage = "已重新加入队列")
    }

    /**
     * Hands a card to a connected agent for reverse-prompting.
     *
     * This needs an MCP agent to be online — the app itself does not look at
     * images. When nothing is connected the server answers 409 and that message
     * is surfaced verbatim, because "no agent connected" is the actionable part.
     */
    fun directReverse(favorite: Favorite) {
        // Fail fast with a reason rather than letting the server answer 409 after
        // a round trip: the missing agent is the whole story.
        if (_library.value.agents.isEmpty()) {
            _settings.update {
                it.copy(
                    message = "没有在线的 AI Agent。反推需要外部 Agent 读取图片并写回提示词，请先在电脑上连接本机 MCP。",
                    messageIsError = true,
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                val result = api.directReverse(favorite.id)
                val agent = result.request?.agentName.orEmpty()
                _settings.update {
                    it.copy(
                        message = if (agent.isNotBlank()) "已提交给 $agent" else "已提交给在线 Agent",
                        messageIsError = false,
                    )
                }
                refreshLibrary()
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    private fun runPatch(
        id: String,
        folder: String? = null,
        prompt: String? = null,
        retryCache: Boolean? = null,
        retryReverse: Boolean? = null,
        successMessage: String,
    ) {
        viewModelScope.launch {
            try {
                api.patchFavorite(
                    id = id,
                    folder = folder,
                    prompt = prompt,
                    retryCache = retryCache,
                    retryReverse = retryReverse,
                )
                _settings.update { it.copy(message = successMessage, messageIsError = false) }
                refreshLibrary()
            } catch (e: DflowApi.ApiException) {
                // The server's refusals are specific and worth showing as-is
                // ("prompt and cached image required", "image not cached yet").
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    // ---- settings ----------------------------------------------------------

    fun loadSettings() {
        _settings.update { it.copy(loading = true) }
        viewModelScope.launch {
            val state = runCatching { api.state() }.getOrNull()
            val presets = runCatching { api.presets() }.getOrNull()
            _settings.update {
                it.copy(
                    loading = false,
                    state = state ?: it.state,
                    presets = presets ?: it.presets,
                )
            }
            _library.update { s -> if (state != null) s.copy(favorites = state.favorites) else s }
            loadAiSettings()
        }
    }

    fun saveAccount(account: Account) {
        _settings.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                api.updateAccount(account)
                _settings.update {
                    it.copy(
                        saving = false,
                        state = it.state.copy(account = account),
                        message = "配置已保存",
                        messageIsError = false,
                    )
                }
            } catch (e: DflowApi.ApiException) {
                _settings.update {
                    it.copy(saving = false, message = e.message, messageIsError = true)
                }
            }
        }
    }

    fun runNetworkTest() {
        _settings.update { it.copy(testing = true, networkTest = null) }
        viewModelScope.launch {
            try {
                val result = api.networkTest()
                _settings.update { it.copy(testing = false, networkTest = result) }
            } catch (e: DflowApi.ApiException) {
                _settings.update {
                    it.copy(testing = false, message = e.message, messageIsError = true)
                }
            }
        }
    }

    fun consumeMessage() {
        _settings.update { it.copy(message = null) }
    }

    // ---- built-in AI reverse ----------------------------------------------

    /** Loads the AI fields from the server into the editable page state. */
    fun loadAiSettings() {
        val account = _settings.value.state.account
        _ai.update {
            it.copy(
                baseUrl = account.aiBaseUrl,
                apiKey = account.aiApiKey,
                model = account.aiModel,
                temperature = account.aiTemperature.toFloat(),
                maxTokens = account.aiMaxTokens,
                customInstruction = account.aiCustomInstruction,
            )
        }
    }

    fun updateAiFields(
        baseUrl: String? = null,
        apiKey: String? = null,
        model: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        customInstruction: String? = null,
    ) {
        _ai.update {
            it.copy(
                baseUrl = baseUrl ?: it.baseUrl,
                apiKey = apiKey ?: it.apiKey,
                model = model ?: it.model,
                temperature = temperature ?: it.temperature,
                maxTokens = maxTokens ?: it.maxTokens,
                customInstruction = customInstruction ?: it.customInstruction,
            )
        }
    }

    /** Persists the AI fields onto the account (other fields are left as-is). */
    private suspend fun persistAi() {
        val current = _ai.value
        val account = _settings.value.state.account.copy(
            aiBaseUrl = current.baseUrl.trim(),
            aiApiKey = current.apiKey.trim(),
            aiModel = current.model.trim(),
            aiTemperature = current.temperature.toDouble(),
            aiMaxTokens = current.maxTokens,
            aiCustomInstruction = current.customInstruction,
        )
        api.updateAccount(account)
        _settings.update { it.copy(state = it.state.copy(account = account)) }
    }

    /**
     * Fetches the model list.
     *
     * Saves first: the endpoint and key live on the server, and the request is
     * made by the server (the phone has no direct access to the user's LAN or
     * to a loopback service on another machine).
     */
    fun fetchAiModels() {
        _ai.update { it.copy(fetchingModels = true, modelsError = null) }
        viewModelScope.launch {
            try {
                persistAi()
                val models = api.aiModels(_ai.value.baseUrl, _ai.value.apiKey)
                _ai.update { it.copy(fetchingModels = false, models = models) }
                if (models.isEmpty()) {
                    _settings.update { it.copy(message = "该地址没有返回任何模型", messageIsError = true) }
                } else if (_ai.value.model.isBlank()) {
                    // Preselect the first entry so a single tap on 保存 is enough.
                    _ai.update { it.copy(model = models.first()) }
                }
            } catch (e: DflowApi.ApiException) {
                _ai.update { it.copy(fetchingModels = false, modelsError = e.message) }
            }
        }
    }

    fun saveAiSettings() {
        viewModelScope.launch {
            try {
                persistAi()
                _settings.update { it.copy(message = "AI 配置已保存", messageIsError = false) }
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    /**
     * Reverses one card with the built-in model, streaming progress into the
     * page state so the user can watch the prompt being written.
     */
    fun aiReverse(favorite: Favorite) {
        val id = favorite.id
        _ai.update { it.copy(progressId = id, progressText = "") }
        pollProgress(id)
        viewModelScope.launch {
            try {
                api.aiReverse(id)
                _settings.update { it.copy(message = "反推完成", messageIsError = false) }
                refreshLibrary()
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
                refreshLibrary()
            } finally {
                progressPollJob?.cancel()
                _ai.update { it.copy(progressId = "", progressText = "") }
            }
        }
    }

    fun aiCancel(id: String) {
        viewModelScope.launch {
            runCatching { api.aiCancel(id) }
            progressPollJob?.cancel()
            _ai.update { it.copy(progressId = "", progressText = "") }
        }
    }

    private fun pollProgress(id: String) {
        progressPollJob?.cancel()
        progressPollJob = viewModelScope.launch {
            // The request may not have reached the server yet on the first tick, so
            // "not running yet" must not be read as "already finished" — otherwise
            // the poll gives up before the job starts and no progress is ever shown.
            // Only stop once the job has actually been observed running and then
            // stopped; the caller cancels this on completion anyway.
            var sawRunning = false
            while (isActive) {
                delay(700)
                val state = runCatching { api.aiProgress(id) }.getOrNull()
                if (state == null) continue
                if (state.running) sawRunning = true
                _ai.update { it.copy(progressText = state.text) }
                if (sawRunning && !state.running) return@launch
            }
        }
    }

    /** Reverses every eligible pending card, one at a time. */
    fun aiReverseAll() {
        viewModelScope.launch {
            try {
                val start = api.aiReverseBatch()
                _ai.update { it.copy(batch = AiBatch(running = true, total = start.total)) }
                _settings.update {
                    it.copy(message = "已开始批量反推，共 ${start.total} 张", messageIsError = false)
                }
                pollBatch()
            } catch (e: DflowApi.ApiException) {
                _settings.update { it.copy(message = e.message, messageIsError = true) }
            }
        }
    }

    fun aiStopBatch() {
        viewModelScope.launch {
            runCatching { api.aiStopBatch() }
            pollBatch()
        }
    }

    private fun pollBatch() {
        batchPollJob?.cancel()
        batchPollJob = viewModelScope.launch {
            while (true) {
                val state = runCatching { api.aiBatch() }.getOrNull()
                if (state != null) {
                    _ai.update { it.copy(batch = state) }
                    if (!state.running) {
                        refreshLibrary()
                        val failed = state.failed
                        _settings.update {
                            it.copy(
                                message = if (failed > 0)
                                    "批量反推结束：成功 ${state.done} 张，失败 $failed 张"
                                else "批量反推完成，共 ${state.done} 张",
                                messageIsError = failed > 0,
                            )
                        }
                        return@launch
                    }
                }
                kotlinx.coroutines.delay(1200)
            }
        }
    }

    /**
     * Thumbnail URL for a library card.
     *
     * A card can have both a remote address and a local cache; prefer the cache
     * (it is the file the user actually saved, and works offline), then fall
     * back to the remote address through the proxy. A card with neither is a
     * prompt-only record and gets a placeholder in the UI.
     */
    fun imageUrlFor(favorite: Favorite, thumbnail: Boolean = true): String? {
        if (favorite.cacheFile.isNotBlank() && favorite.cacheStatus == "ready") {
            return api.localImage(favorite.id, thumbnail)
        }
        val remote = favorite.preview_file_url.ifBlank {
            favorite.large_file_url.ifBlank { favorite.file_url.ifBlank { favorite.sourceImageUrl } }
        }
        return api.proxyImage(remote)
    }

    /** Full-size URL for the viewer. Prefers the original over the thumbnail. */
    fun fullImageUrlFor(favorite: Favorite): String? {
        if (favorite.cacheFile.isNotBlank() && favorite.cacheStatus == "ready") {
            return api.localImage(favorite.id, thumbnail = false)
        }
        val remote = favorite.file_url.ifBlank {
            favorite.large_file_url.ifBlank { favorite.sourceImageUrl }
        }
        return api.proxyImage(remote)
    }

    fun gridUrlFor(post: Post): String? = api.proxyImage(post.gridUrl)

    fun fullUrlFor(post: Post): String? = api.proxyImage(post.fullUrl)
}

/** Converts a browsed post into the shape the server stores for favourites. */
private fun Post.toFavorite(): Favorite = Favorite(
    // The server keeps a plain numeric id for site cards: cleanFavorite() drops
    // any entry whose id is not all digits, and it silently disappears from the
    // saved list rather than reporting an error.
    id = id.toString(),
    source = if (pixivId != null) "pixiv" else "danbooru",
    sourceId = id.toString(),
    sourceImageUrl = file_url ?: large_file_url ?: preview_file_url.orEmpty(),
    preview_file_url = preview_file_url.orEmpty(),
    large_file_url = large_file_url.orEmpty(),
    file_url = file_url.orEmpty(),
    image_width = image_width,
    image_height = image_height,
    rating = rating,
    folder = "original",
    tag_string_general = tag_string_general,
    tag_string_character = tag_string_character,
    tag_string_copyright = tag_string_copyright,
    created_at = created_at,
    author = tag_string_artist.replace('_', ' '),
    createdAt = created_at,
)
