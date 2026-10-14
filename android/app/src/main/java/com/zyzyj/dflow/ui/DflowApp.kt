package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyzyj.dflow.data.Favorite
import com.zyzyj.dflow.data.Post
import kotlinx.coroutines.launch

private enum class Destination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Browse("浏览", Icons.Filled.Explore, Icons.Outlined.Explore),
    Library("收藏", Icons.Filled.Collections, Icons.Outlined.Collections),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * What the full-screen viewer is showing. Held as one object so the URL list and
 * the favourite state always describe the same set of items — keeping them in
 * separate states is how a viewer ends up toggling the wrong card.
 */
private data class ViewerRequest(
    val urls: List<String?>,
    val titles: List<String>,
    val index: Int,
    /** For gallery items: the post to save or remove. */
    val posts: List<Post> = emptyList(),
    /** For library items: the saved card to remove. */
    val favorites: List<Favorite> = emptyList(),
)

@Composable
fun DflowApp(viewModel: DflowViewModel) {
    var destination by rememberSaveable { mutableStateOf(Destination.Browse) }
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.loadHotTags()
        viewModel.refreshLibrary()
        viewModel.loadSettings()
    }

    val savedIds = remember(library.favorites) {
        library.favorites.flatMap { listOf(it.id, it.sourceId) }.toSet()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    val selected = item == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.Browse -> BrowseScreen(
                    state = browse,
                    favoriteIds = savedIds,
                    onModeChange = viewModel::setMode,
                    onQueryChange = viewModel::setQuery,
                    onSubmitSearch = { viewModel.submitSearch(browse.query) },
                    onClearSearch = viewModel::clearSearch,
                    onOpenPost = { post ->
                        viewer = ViewerRequest(
                            urls = browse.posts.map { viewModel.fullUrlFor(it) },
                            titles = browse.posts.map { post.artist.ifBlank { "作品 ${it.id}" } },
                            index = browse.posts.indexOf(post).coerceAtLeast(0),
                            posts = browse.posts,
                        )
                    },
                    onToggleFavorite = { post ->
                        viewModel.toggleFavorite(post, viewModel.findFavorite(library.favorites, post))
                    },
                    onLoadMore = viewModel::loadMore,
                    onRefresh = viewModel::refresh,
                    imageUrl = viewModel::gridUrlFor,
                )

                Destination.Library -> LibraryScreen(
                    state = library,
                    onFolderChange = viewModel::setFolder,
                    onOpen = { _, index ->
                        val visible = library.favorites.filter { it.folder == library.folder.key }
                        viewer = ViewerRequest(
                            urls = visible.map { viewModel.fullImageUrlFor(it) },
                            titles = visible.map { it.displayTitle },
                            index = index.coerceAtLeast(0),
                            favorites = visible,
                        )
                    },
                    onCopyPrompt = { favorite ->
                        copyToClipboard(context, favorite.prompt)
                        scope.launch { snackbar.showSnackbar("提示词已复制") }
                    },
                    onRefresh = viewModel::refreshLibrary,
                    imageUrl = { viewModel.imageUrlFor(it) },
                )

                Destination.Settings -> SettingsScreen(
                    state = settings,
                    appVersion = com.zyzyj.dflow.BuildConfig.VERSION_NAME,
                    onSave = viewModel::saveAccount,
                    onTestNetwork = viewModel::runNetworkTest,
                    onMessageShown = viewModel::consumeMessage,
                )
            }
        }
    }

    viewer?.let { request ->
        ImageViewer(
            urls = request.urls,
            titles = request.titles,
            initialIndex = request.index,
            isFavorite = { index ->
                val post = request.posts.getOrNull(index)
                val favorite = request.favorites.getOrNull(index)
                when {
                    post != null -> viewModel.isFavorite(post, library.favorites)
                    favorite != null -> library.favorites.any { it.id == favorite.id }
                    else -> false
                }
            },
            onToggleFavorite = { index ->
                val post = request.posts.getOrNull(index)
                val favorite = request.favorites.getOrNull(index)
                when {
                    post != null -> viewModel.toggleFavorite(
                        post,
                        viewModel.findFavorite(library.favorites, post),
                    )
                    favorite != null -> {
                        // Already saved: the only sensible action in the viewer
                        // for a library card is to un-save it.
                        if (library.favorites.any { it.id == favorite.id }) {
                            viewModel.removeFavorite(favorite)
                            scope.launch { snackbar.showSnackbar("已从收藏移除") }
                        }
                    }
                }
            },
            onClose = { viewer = null },
        )
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", text))
}
