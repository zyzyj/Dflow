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
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyzyj.dflow.data.Favorite

/**
 * The local library: saved cards grouped by workflow stage.
 *
 * The four folders are the server's own model (original → pending → worded →
 * completed), so the tab order mirrors the pipeline rather than being arbitrary.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onFolderChange: (LibraryFolder) -> Unit,
    onOpen: (Favorite, Int) -> Unit,
    onCopyPrompt: (Favorite) -> Unit,
    onRefresh: () -> Unit,
    imageUrl: (Favorite) -> String?,
    modifier: Modifier = Modifier,
) {
    val visible = state.favorites.filter { it.folder == state.folder.key }
    // Tab counts come from the full list so an empty folder still shows its size.
    val counts = LibraryFolder.entries.associateWith { folder ->
        state.favorites.count { it.folder == folder.key }
    }

    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = LibraryFolder.entries.indexOf(state.folder)) {
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
                    EmptyHint(
                        text = "${state.folder.label}还是空的",
                        hint = hintFor(state.folder),
                        onRetry = onRefresh,
                    )
                }
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.id }) { favorite ->
                        FavoriteCard(
                            favorite = favorite,
                            imageUrl = imageUrl(favorite),
                            onOpen = { onOpen(favorite, visible.indexOf(favorite)) },
                            onCopyPrompt = { onCopyPrompt(favorite) },
                        )
                    }
                }
            }
        }
    }
}

private fun hintFor(folder: LibraryFolder): String = when (folder) {
    LibraryFolder.Original -> "在浏览页点卡片上的心形即可收藏"
    LibraryFolder.Pending -> "在收藏卡片上点「AI」会把原图加入待反推队列"
    LibraryFolder.Worded -> "反推完成的提示词会出现在这里，可一键复制"
    LibraryFolder.Completed -> "提示词就绪后点「→」释放归档到这里"
}

@Composable
private fun FavoriteCard(
    favorite: Favorite,
    imageUrl: String?,
    onOpen: () -> Unit,
    onCopyPrompt: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Box {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = favorite.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(favorite.aspectRatio.coerceIn(0.4f, 2.2f))
                        .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                // A card with no cached image is still a valid record (a prompt
                // written by hand); show a placeholder rather than collapsing it.
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

            if (favorite.hasPrompt) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已有提示词",
                    tint = Color(0xFF34D399),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(2.dp),
                )
            }

            if (favorite.hasPrompt) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onCopyPrompt)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制提示词",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Text("复制", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
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
