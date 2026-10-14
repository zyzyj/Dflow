package com.zyzyj.dflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyzyj.dflow.data.Post
import com.zyzyj.dflow.data.Tag

/**
 * Browse screen.
 *
 * The controls sit in one compact block above the grid: search, then the mode
 * chips, then hot tags as one-line suggestions. Previously each occupied its own
 * full-width row, which pushed the actual content below the fold on a phone.
 */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    favoriteIds: Set<String>,
    onModeChange: (BrowseMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onToggleFavorite: (Post) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    imageUrl: (Post) -> String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SearchBar(
            query = state.query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmitSearch,
            onClear = onClearSearch,
        )

        ModeRow(selected = state.mode, onSelect = onModeChange)

        // Hot tags only make sense while browsing with an empty query; they are
        // suggestions for what to search, not a filter for the results on screen.
        AnimatedVisibility(
            visible = state.query.isBlank() && state.hotTags.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            HotTagRow(tags = state.hotTags) { tag ->
                onQueryChange(tag.name)
                onSubmitSearch()
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.posts.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.error != null && state.posts.isEmpty() -> {
                    ErrorState(
                        message = state.error,
                        detail = state.errorDetail,
                        onRetry = onRefresh,
                    )
                }
                state.posts.isEmpty() -> {
                    EmptyHint(
                        text = if (state.query.isBlank()) "这一栏暂时没有内容"
                        else "没有找到「${state.query}」",
                        hint = "可能是画站连不上（到设置里检查代理与账号），也可能是这个标签确实没有作品",
                        onRetry = onRefresh,
                    )
                }
                else -> GalleryGrid(
                    posts = state.posts,
                    loadingMore = state.loadingMore,
                    onOpen = onOpenPost,
                    onToggleFavorite = onToggleFavorite,
                    onLoadMore = onLoadMore,
                    favoriteIds = favoriteIds,
                    imageUrl = imageUrl,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("搜索标签、画师名…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(query.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onClear(); keyboard?.hide() }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空")
                }
            }
        },
        // Submitting from the keyboard is the natural way to search; hiding the
        // keyboard makes the results immediately visible.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit(); keyboard?.hide() }),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ModeRow(selected: BrowseMode, onSelect: (BrowseMode) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(BrowseMode.entries.toList()) { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun HotTagRow(tags: List<Tag>, onClick: (Tag) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(
                "热门",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(tags, key = { it.id }) { tag ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { onClick(tag) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                ) {
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp).padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, hint: String = "", onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (onRetry != null) {
            Text(
                "点击重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp).clickable(onClick = onRetry),
            )
        }
    }
}
