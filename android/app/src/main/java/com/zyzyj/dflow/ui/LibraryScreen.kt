package com.zyzyj.dflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyzyj.dflow.data.Favorite
import com.zyzyj.dflow.data.McpSession

/**
 * The local library with its reverse-prompt workflow.
 *
 * Each card shows the actions that are valid *for its current folder*, because
 * the server enforces a pipeline: a card must be queued (pending) and have its
 * full-size image cached before an agent can read it, and it needs a prompt
 * before it can be archived. Offering every button everywhere would just produce
 * refusals.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    favoriteIds: Set<String>,
    aiConfigured: Boolean,
    batchRunning: Boolean,
    onFolderChange: (LibraryFolder) -> Unit,
    onOpen: (Favorite, Int) -> Unit,
    onCopyPrompt: (Favorite) -> Unit,
    onEditPrompt: (Favorite) -> Unit,
    onEnqueue: (Favorite) -> Unit,
    onDirectReverse: (Favorite) -> Unit,
    onLocalReverse: (Favorite) -> Unit,
    onRelease: (Favorite) -> Unit,
    onRestore: (Favorite) -> Unit,
    onRetryCache: (Favorite) -> Unit,
    onRefresh: () -> Unit,
    imageUrl: (Favorite) -> String?,
    modifier: Modifier = Modifier,
) {
    val visible = state.favorites
        .filter { it.folder == state.folder.key }
        .sortedWith(comparatorFor(state.folder))
    val hasAgents = state.agents.isNotEmpty()
    val counts = LibraryFolder.entries.associateWith { folder ->
        state.favorites.count { it.folder == folder.key }
    }

    Column(modifier.fillMaxSize()) {
        // Reverse-prompting can come from two places now. The built-in model runs
        // entirely in the app; the MCP route needs an agent elsewhere. Say which
        // is available instead of leaving the user to guess why a button is dead.
        when {
            aiConfigured -> Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = if (batchRunning) "正在批量反推…可在「反推」页查看进度"
                    else "内置 AI 可用，待反推卡片可直接点「识别」自动反推" +
                        if (state.agents.isNotEmpty()) "；也已连接 ${state.agents.size} 个外部 Agent" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
            state.agents.isEmpty() -> Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = "还没配置 AI。到「反推」页填一个视觉模型接口，就能在手机上自动反推；" +
                        "也可以手动编辑提示词，或在电脑上连接本机 MCP 由 Agent 处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
            else -> Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = "已连接：" + state.agents.joinToString("、") { agent ->
                        agent.name.ifBlank { "Agent" }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = LibraryFolder.entries.indexOf(state.folder),
            edgePadding = 8.dp,
        ) {
            LibraryFolder.entries.forEach { folder ->
                Tab(
                    selected = folder == state.folder,
                    onClick = { onFolderChange(folder) },
                    text = { Text("${folder.label} ${counts[folder] ?: 0}") },
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.favorites.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.error != null && state.favorites.isEmpty() -> {
                    ErrorState(state.error, state.errorDetail, onRetry = onRefresh)
                }
                visible.isEmpty() -> {
                    EmptyHint(text = "${state.folder.label}还是空的", hint = hintFor(state.folder))
                }
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.id }) { favorite ->
                        FavoriteCard(
                            favorite = favorite,
                            imageUrl = imageUrl(favorite),
                            aiConfigured = aiConfigured,
                            batchRunning = batchRunning,
                            agentsAvailable = hasAgents,
                            onOpen = { onOpen(favorite, visible.indexOf(favorite)) },
                            onCopyPrompt = { onCopyPrompt(favorite) },
                            onEditPrompt = { onEditPrompt(favorite) },
                            onEnqueue = { onEnqueue(favorite) },
                            onDirectReverse = { onDirectReverse(favorite) },
                            onLocalReverse = { onLocalReverse(favorite) },
                            onRelease = { onRelease(favorite) },
                            onRestore = { onRestore(favorite) },
                            onRetryCache = { onRetryCache(favorite) },
                        )
                    }
                }
            }
        }
    }
}

/** Pending work first, then newest; completed items read best by completion time. */
private fun comparatorFor(folder: LibraryFolder): Comparator<Favorite> = when (folder) {
    LibraryFolder.Pending -> compareBy(
        { if (it.autoEnabled) 0 else 1 },
        { it.queueOrder },
    )
    LibraryFolder.Completed -> compareByDescending { it.completedAt.ifBlank { it.updatedAt } }
    else -> compareByDescending { it.promptWrittenAt.ifBlank { it.createdAt } }
}

private fun hintFor(folder: LibraryFolder): String = when (folder) {
    LibraryFolder.Original -> "在浏览页点心形即可收藏；点卡片上的「AI」加入待反推"
    LibraryFolder.Pending -> "图集下载完成后，点「直推」把它交给在线的 AI Agent"
    LibraryFolder.Worded -> "点提示词区可查看/编辑；点「→」归档"
    LibraryFolder.Completed -> "已归档的提示词可一键复制"
}

@Composable
private fun FavoriteCard(
    favorite: Favorite,
    imageUrl: String?,
    aiConfigured: Boolean,
    batchRunning: Boolean,
    agentsAvailable: Boolean,
    onOpen: () -> Unit,
    onCopyPrompt: () -> Unit,
    onEditPrompt: () -> Unit,
    onEnqueue: () -> Unit,
    onDirectReverse: () -> Unit,
    onLocalReverse: () -> Unit,
    onRelease: () -> Unit,
    onRestore: () -> Unit,
    onRetryCache: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(Modifier.clickable(onClick = onOpen)) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = favorite.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(favorite.aspectRatio.coerceIn(0.4f, 2.2f))
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SourceBadge(
                    label = when (favorite.source) {
                        "pixiv" -> "P"
                        "local" -> "本地"
                        else -> "D"
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )

                StatusChip(
                    favorite = favorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }

            // Actions, chosen per folder: only the moves the server will accept.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (favorite.folder) {
                    "original" -> {
                        CardAction("AI", Icons.Filled.AutoAwesome, "加入待反推", onEnqueue)
                    }
                    "pending" -> {
                        // Two ways to reverse, and they are not interchangeable:
                        // 「识别」runs the built-in model here; 「直推」hands the card
                        // to an external agent. Both are shown when both are available
                        // so the user picks deliberately.
                        CardAction(
                            label = "识别",
                            icon = Icons.Filled.AutoAwesome,
                            description = if (aiConfigured) "用内置模型反推" else "需要先配置 AI",
                            onClick = onLocalReverse,
                            enabled = aiConfigured && !batchRunning && favorite.isCached,
                        )
                        if (agentsAvailable) {
                            CardAction("直推", Icons.AutoMirrored.Filled.Send, "交给在线 Agent", onDirectReverse)
                        }
                        if (!favorite.isCached) {
                            CardAction("重下", Icons.Filled.Refresh, "重新下载原图", onRetryCache)
                        }
                        CardAction("移除", Icons.Filled.Restore, "移回原始收藏", onRestore)
                    }
                    "worded" -> {
                        CardAction("改词", Icons.Filled.Edit, "编辑提示词", onEditPrompt)
                        CardAction("复制", Icons.Filled.ContentCopy, "复制提示词", onCopyPrompt)
                        CardAction("归档", Icons.AutoMirrored.Filled.ArrowForward, "释放到已完成", onRelease)
                    }
                    else -> {
                        CardAction("复制", Icons.Filled.ContentCopy, "复制提示词", onCopyPrompt)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(
    label: String,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

private val Favorite.isCached: Boolean
    get() = cacheFile.isNotBlank() && cacheStatus == "ready"

/**
 * Shows what the card is waiting for. The pipeline has several intermediate
 * states that look identical on the image alone, and hiding them makes the
 * feature feel broken rather than busy.
 */
@Composable
private fun StatusChip(favorite: Favorite, modifier: Modifier = Modifier) {
    val (label, color) = when {
        favorite.hasPrompt -> "有词" to Color(0xFF34D399)
        favorite.reverseStatus == "processing" || favorite.reverseStatus == "direct-queued" ->
            "处理中" to Color(0xFF60A5FA)
        favorite.reverseStatus == "failed" -> "失败" to Color(0xFFEF4444)
        favorite.cacheStatus == "loading" -> "下载中" to Color(0xFFFBBF24)
        favorite.cacheStatus == "failed" || favorite.cacheError.isNotBlank() -> "缓存失败" to Color(0xFFEF4444)
        favorite.folder == "pending" -> "待反推" to Color(0xFFFBBF24)
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(color.copy(alpha = 0.88f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SourceBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Marks cards that already carry a prompt, so the archive is scannable. */
@Composable
private fun PromptReadyMark(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Check,
        contentDescription = "已有提示词",
        tint = Color(0xFF34D399),
        modifier = modifier.size(16.dp),
    )
}
