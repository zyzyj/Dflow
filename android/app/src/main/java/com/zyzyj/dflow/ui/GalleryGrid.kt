package com.zyzyj.dflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyzyj.dflow.data.Post

/**
 * Staggered grid of posts.
 *
 * A staggered grid (not a uniform one) because the source is illustration art:
 * forcing every cell to the same height either crops the artwork or wastes space
 * on tall pieces, and the aspect ratio is part of what the user is judging.
 */
@Composable
fun GalleryGrid(
    posts: List<Post>,
    loadingMore: Boolean,
    onOpen: (Post) -> Unit,
    onToggleFavorite: (Post) -> Unit,
    onLoadMore: () -> Unit,
    favoriteIds: Set<String>,
    imageUrl: (Post) -> String?,
    contentPadding: PaddingValues,
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyStaggeredGridState()

    // Ask for the next page slightly before the user reaches the bottom so the
    // grid does not visibly stall.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= posts.size - 8
        }
    }
    LaunchedEffect(gridState, posts.size) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
        state = gridState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        if (header != null) {
            item(span = StaggeredGridItemSpan.FullLine) { header() }
        }

        items(posts, key = { it.id }) { post ->
            PostCard(
                post = post,
                imageUrl = imageUrl(post),
                isFavorite = favoriteIds.contains("post_${post.id}") ||
                    favoriteIds.contains(post.id.toString()),
                onClick = { onOpen(post) },
                onToggleFavorite = { onToggleFavorite(post) },
            )
        }

        if (loadingMore) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
    }
}

@Composable
fun PostCard(
    post: Post,
    imageUrl: String?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = imageUrl,
                contentDescription = post.artist.ifBlank { "作品 ${post.id}" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(post.aspectRatio.coerceIn(0.4f, 2.2f))
                    .clip(RoundedCornerShape(14.dp)),
            )

            // Rating chip: colour-coded so adult content is identifiable at a
            // glance without having to open the post.
            RatingBadge(
                rating = post.rating,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp).padding(4.dp),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                )
            }

            if (post.score > 0) {
                Text(
                    text = "♥ ${post.score}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun RatingBadge(rating: String, modifier: Modifier = Modifier) {
    // g/s/q/e is Danbooru's general/sensitive/questionable/explicit scale.
    val (label, color) = when (rating.lowercase()) {
        "g" -> "G" to Color(0xFF2E7D32)
        "s" -> "S" to Color(0xFF1565C0)
        "q" -> "Q" to Color(0xFFE65100)
        "e" -> "E" to Color(0xFFB71C1C)
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(color.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Shown when a request failed, with the server's detail line when it has one. */
@Composable
fun ErrorState(
    message: String,
    detail: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = "点击重试",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp).clickable(onClick = onRetry),
        )
    }
}
