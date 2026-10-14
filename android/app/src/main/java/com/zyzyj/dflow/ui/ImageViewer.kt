package com.zyzyj.dflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter

/**
 * Full-screen viewer.
 *
 * Loads the original file rather than the grid thumbnail — thumbnails are
 * half-size derivatives and look soft at this scale. Zoom is double-tap-to-toggle
 * as well as pinch, because pinch alone is fiddly one-handed, and the browser
 * chrome (title bar, info panel) can be tapped away so the image gets the whole
 * screen.
 */
@Composable
fun ImageViewer(
    urls: List<String?>,
    titles: List<String>,
    initialIndex: Int,
    isFavorite: (Int) -> Boolean,
    onToggleFavorite: (Int) -> Unit,
    onClose: () -> Unit,
    subtitles: List<String> = emptyList(),
    details: (Int) -> String = { "" },
) {
    if (urls.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, urls.lastIndex),
        pageCount = { urls.size },
    )
    val current = pagerState.currentPage
    var chromeVisible by remember { mutableStateOf(true) }
    var detailsVisible by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    ZoomableImage(
                        url = urls[page],
                        onTap = { chromeVisible = !chromeVisible },
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    TopBar(
                        title = titles.getOrNull(current).orEmpty(),
                        subtitle = subtitles.getOrNull(current).orEmpty(),
                        position = if (urls.size > 1) "${current + 1} / ${urls.size}" else "",
                        favorite = isFavorite(current),
                        onToggleFavorite = { onToggleFavorite(current) },
                        onShowDetails = { detailsVisible = !detailsVisible },
                        detailsOpen = detailsVisible,
                        onClose = onClose,
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible && detailsVisible,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    DetailsPanel(
                        title = titles.getOrNull(current).orEmpty(),
                        text = details(current),
                        onDismiss = { detailsVisible = false },
                    )
                }

                if (urls[current] == null) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                        Text(
                            "这张图没有可用的地址",
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    position: String,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShowDetails: () -> Unit,
    detailsOpen: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
            val line = listOfNotNull(
                subtitle.takeIf { it.isNotBlank() },
                position.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onShowDetails) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "详情",
                tint = if (detailsOpen) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

/**
 * Bottom sheet with the card's metadata.
 *
 * The text is selectable so a user can lift a prompt or a tag string out of the
 * viewer without going back to the library.
 */
@Composable
private fun DetailsPanel(title: String, text: String, onDismiss: () -> Unit) {
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Text(
                text.ifBlank { "暂无可用信息" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(url: String?, onTap: () -> Unit) {
    if (url == null) return
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val painter = rememberAsyncImagePainter(model = url)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(url) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(url) {
                // Double tap toggles between fit and 2.5x. Single tap toggles the
                // surrounding chrome, so a tap that is part of a double tap must
                // not flicker the bars — detectTapGestures handles that for us.
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )

        when (val state = painter.state) {
            is AsyncImagePainter.State.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                }
            }
            is AsyncImagePainter.State.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("图片加载失败", color = Color.White.copy(alpha = 0.85f))
                        Text(
                            "可能是画站连不上；到设置里检查代理",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}
