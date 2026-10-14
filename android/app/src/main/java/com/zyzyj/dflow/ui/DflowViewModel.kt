package com.zyzyj.dflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyzyj.dflow.data.Account
import com.zyzyj.dflow.data.AppState
import com.zyzyj.dflow.data.DflowApi
import com.zyzyj.dflow.data.Favorite
import com.zyzyj.dflow.data.NetworkTest
import com.zyzyj.dflow.data.Post
import com.zyzyj.dflow.data.PresetLibrary
import com.zyzyj.dflow.data.Tag
import kotlinx.coroutines.Job
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

    private var browseJob: Job? = null

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
                _library.update { it.copy(favorites = state.favorites, loading = false) }
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
        favorites.firstOrNull { it.sourceId == post.id.toString() || it.id == "post_${post.id}" }

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

    /** Resolves the thumbnail URL for a library card (local cards live on the server). */
    fun imageUrlFor(favorite: Favorite, thumbnail: Boolean = true): String? =
        if (favorite.isRemote) api.proxyImage(favorite.preview_file_url.ifBlank {
            favorite.large_file_url.ifBlank { favorite.file_url }
        })
        else if (favorite.cacheFile.isNotBlank()) api.localImage(favorite.id, thumbnail)
        else null

    fun fullImageUrlFor(favorite: Favorite): String? =
        if (favorite.isRemote) api.proxyImage(favorite.file_url.ifBlank {
            favorite.large_file_url
        })
        else if (favorite.cacheFile.isNotBlank()) api.localImage(favorite.id, thumbnail = false)
        else null

    fun gridUrlFor(post: Post): String? = api.proxyImage(post.gridUrl)

    fun fullUrlFor(post: Post): String? = api.proxyImage(post.fullUrl)
}

/** Converts a browsed post into the shape the server stores for favourites. */
private fun Post.toFavorite(): Favorite = Favorite(
    id = "post_$id",
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
