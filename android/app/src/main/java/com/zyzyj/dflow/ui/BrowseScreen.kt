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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyzyj.dflow.data.Post
import com.zyzyj.dflow.data.Tag

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
    Column(modifier.fillMaxSize()) {        SearchBar(
            query = state.query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmitSearch,
            onClear = onClearSearch,
        )

        ModeRow(selected = state.mode, onSelect = onModeChange)

        if (state.query.isBlank() && state.hotTags.isNotEmpty()) {
            HotTagRow(tags = state.hotTags, onClick = { tag -> onQueryChange(tag.name); onSubmitSearch() })
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
                        text = if (state.query.isBlank()) "这一栏暂时没有内容" else "没有找到匹配「${state.query}」的作品",
                        hint = "下拉或点击重试；也可能是服务端连不上画站（可在设置里检查代理）",
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
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun HotTagRow(tags: List<Tag>, onClick: (Tag) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tags, key = { it.id }) { tag ->
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(50),
                    )
                    .clickable { onClick(tag) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
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
