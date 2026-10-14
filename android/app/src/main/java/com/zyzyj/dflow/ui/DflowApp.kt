package com.zyzyj.dflow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyzyj.dflow.LogStore
import com.zyzyj.dflow.NodeService
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
    Ai("反推", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Api("接口", Icons.Filled.Hub, Icons.Outlined.Hub),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * What the full-screen viewer is showing. Held as one object so the URL list, the
 * titles and the favourite state always describe the same set of items; keeping
 * them in separate states is how a viewer ends up toggling the wrong card.
 */
private data class ViewerRequest(
    val urls: List<String?>,
    val titles: List<String>,
    val index: Int,
    val subtitles: List<String> = emptyList(),
    val details: (Int) -> String = { "" },
    val posts: List<Post> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
)

@Composable
fun DflowApp(viewModel: DflowViewModel) {
    var destination by rememberSaveable { mutableStateOf(Destination.Browse) }
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val apiAccess by viewModel.apiAccess.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var promptTarget by remember { mutableStateOf<Favorite?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    // Storage Access Framework: the user picks where the export goes, so no
    // storage permission is needed and nothing is written anywhere they did not
    // choose. A raw path would need MANAGE_EXTERNAL_STORAGE, which is both
    // intrusive and increasingly rejected.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) viewModel.exportLogs(context, uri)
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.loadHotTags()
        viewModel.refreshLibrary()
        viewModel.loadSettings()
        viewModel.loadApiAccess()
    }

    // Surface operation results (queue moves, reverse-prompt handoffs, refusals)
    // as a snackbar; the settings screen also renders the message inline.
    LaunchedEffect(settings.message) {
        settings.message?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    val savedIds = remember(library.favorites) {
        library.favorites.flatMap { listOf(it.id, it.sourceId) }.toSet()
    }
    val pendingCount = remember(library.favorites) {
        library.favorites.count { it.folder == "pending" }
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
                            if (item == Destination.Library && pendingCount > 0) {
                                BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Errors from a previous action stay visible until dismissed, because
            // they usually explain why a button did nothing.
            AnimatedVisibility(
                visible = settings.message != null && settings.messageIsError,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                settings.message?.let { message ->
                    MessageBar(
                        message = message,
                        isError = settings.messageIsError,
                        onDismiss = viewModel::consumeMessage,
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
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
                                titles = browse.posts.map { it.artist.ifBlank { "作品 ${it.id}" } },
                                index = browse.posts.indexOf(post).coerceAtLeast(0),
                                subtitles = browse.posts.map { it.dimensionLabel },
                                details = { index -> browse.posts.getOrNull(index)?.detailText.orEmpty() },
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
                        favoriteIds = savedIds,
                        selectedIds = selection,
                        aiConfigured = ai.configured,
                        batchRunning = ai.batch.running,
                        onFolderChange = viewModel::setFolder,
                        onToggleSelectionMode = {
                            viewModel.setSelectionMode(!library.selectionMode)
                        },
                        onToggleSelected = { viewModel.toggleSelection(it.id) },
                        onSelectAll = viewModel::toggleSelectAllInFolder,
                        onInvertSelection = viewModel::invertSelectionInFolder,
                        onRemoveSelected = {
                            confirm = Pair(
                                "移除选中的 ${selection.size} 张，并删除本地缓存图？删除后需重新下载。",
                            ) { viewModel.removeSelected() }
                        },
                        onMoveSelectedToPending = viewModel::moveSelectedToPending,
                        onOpen = { _, index ->
                            val visible = library.favorites.filter { it.folder == library.folder.key }
                            viewer = ViewerRequest(
                                urls = visible.map { viewModel.fullImageUrlFor(it) },
                                titles = visible.map { it.displayTitle },
                                index = index.coerceAtLeast(0),
                                subtitles = visible.map { it.folderLabel },
                                details = { i -> visible.getOrNull(i)?.detailText.orEmpty() },
                                favorites = visible,
                            )
                        },
                        onCopyPrompt = { favorite ->
                            copyToClipboard(context, favorite.prompt)
                            scope.launch { snackbar.showSnackbar("提示词已复制") }
                        },
                        onEditPrompt = { promptTarget = it },
                        onEnqueue = viewModel::enqueueForReverse,
                        onDirectReverse = viewModel::directReverse,
                        onLocalReverse = viewModel::aiReverse,
                        onRelease = { favorite ->
                            // The server refuses a release without a prompt AND a
                            // cached image; checking here turns a confusing
                            // refusal into an explanation.
                            when {
                                !favorite.hasPrompt ->
                                    scope.launch { snackbar.showSnackbar("这张还没有提示词，先编辑提示词") }
                                favorite.cacheFile.isBlank() ->
                                    scope.launch { snackbar.showSnackbar("原图还没缓存完成，稍后再归档") }
                                else -> confirm = "归档到「已完成」？" to { viewModel.releaseToCompleted(favorite) }
                            }
                        },
                        onRestore = { favorite ->
                            confirm = "移回「原始收藏」？" to { viewModel.moveToOriginal(favorite) }
                        },
                        onRetryCache = viewModel::retryCache,
                        onRefresh = viewModel::refreshLibrary,
                        imageUrl = { viewModel.imageUrlFor(it) },
                    )

                    Destination.Ai -> AiScreen(
                        state = ai,
                        pendingCount = pendingCount,
                        onFieldChange = { baseUrl, apiKey, model, temperature, maxTokens, custom ->
                            viewModel.updateAiFields(baseUrl, apiKey, model, temperature, maxTokens, custom)
                        },
                        onFetchModels = viewModel::fetchAiModels,
                        onSave = viewModel::saveAiSettings,
                        onReverseAll = viewModel::aiReverseAll,
                        onStopBatch = viewModel::aiStopBatch,
                    )

                    Destination.Api -> ApiScreen(
                        state = apiAccess,
                        onReload = viewModel::loadApiAccess,
                        onToggleEnabled = viewModel::setApiEnabled,
                        onToggleRequireToken = viewModel::setApiRequireToken,
                        onRotateToken = viewModel::rotateApiToken,
                        onSaveBindScope = { bindScope ->
                            saveBindScope(context, bindScope)
                            // The guest reads the bind address before it starts, so the
                            // change only takes effect on a fresh launch. Restarting via
                            // one action avoids a stop/start race that could leave no
                            // server running; the view model then polls until the
                            // service reports the new scope.
                            viewModel.setBindScope(bindScope) { NodeService.restart(context) }
                            scope.launch { snackbar.showSnackbar("已切换监听范围，正在重启服务…") }
                        },
                    )

                    Destination.Settings -> SettingsScreen(
                        state = settings,
                        appVersion = com.zyzyj.dflow.BuildConfig.VERSION_NAME,
                        logExportName = viewModel.logExportName(),
                        onSave = viewModel::saveAccount,
                        onTestNetwork = viewModel::runNetworkTest,
                        onExportLogs = { name -> exportLauncher.launch(name) },
                        onViewLogs = { viewModel.loadLogs(); showLogs = true },
                        onClearLogs = viewModel::clearLogs,
                    )
                }
            }
        }
    }

    promptTarget?.let { favorite ->
        PromptDialog(
            favorite = favorite,
            readOnly = favorite.hasPrompt,
            onDismiss = { promptTarget = null },
            onSave = { text ->
                viewModel.writePrompt(favorite, text)
                promptTarget = null
            },
        )
    }

    confirm?.let { (message, action) ->
        ConfirmDialog(
            title = "确认",
            message = message,
            confirmLabel = "确定",
            onConfirm = action,
            onDismiss = { confirm = null },
        )
    }

    if (showLogs) {
        LogsDialog(
            logs = logs,
            onDismiss = { showLogs = false },
            onReload = viewModel::loadLogs,
        )
    }

    viewer?.let { request ->
        ImageViewer(
            urls = request.urls,
            titles = request.titles,
            initialIndex = request.index,
            subtitles = request.subtitles,
            details = request.details,
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
                    favorite != null && library.favorites.any { it.id == favorite.id } -> {
                        viewModel.removeFavorite(favorite)
                        scope.launch { snackbar.showSnackbar("已从收藏移除") }
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

/**
 * Persists the requested listen scope for the next service start.
 *
 * Written to a plain file, not to server state: the bind address has to be known
 * before the guest process exists, and the guest is what owns that state.
 */
private fun saveBindScope(context: android.content.Context, scope: String) {
    runCatching {
        java.io.File(context.filesDir, NodeService.BIND_FILE)
            .writeText(if (scope == "lan") "lan" else "local")
    }
}

/**
 * Log viewer.
 *
 * Shows the host-side ring buffer immediately (the in-memory copy), and the
 * server's own log when it can be fetched. Read-only and selectable so a bug
 * report can quote a specific line.
 */
@Composable
private fun LogsDialog(
    logs: LogsUiState,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    val text = buildString {
        if (logs.preview.isNotBlank()) {
            appendLine("--- 服务端日志 ---")
            appendLine(logs.preview)
        }
        appendLine()
        appendLine("--- 主机日志 ---")
        appendLine(LogStore.tail(300).ifBlank { "(空)" })
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("最近日志") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = text.ifBlank { "（暂无日志）" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onReload) { Text("刷新") }
        },
    )
}
