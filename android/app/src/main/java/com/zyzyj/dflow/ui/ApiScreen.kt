package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Remote access.
 *
 * Rebuilt around the order a person actually works in, and around one thing the
 * old layout never showed: the finished client configuration.
 *
 * Previously the page opened with two paragraphs of explanation, then offered
 * switches, then a token, then a bind scope, and only at the very bottom listed
 * the addresses — so the one thing the page exists to produce (something you can
 * paste into another app) was the hardest to reach, and the server's ready-made
 * `clientConfig` was never displayed at all. Users had to assemble the JSON by
 * hand from a URL and a header name.
 *
 * The bind scope was also gated behind enabling remote access first, which is
 * backwards: on a fresh install the scope buttons were disabled with no
 * explanation, so the two settings appeared broken rather than sequential.
 */
@Composable
fun ApiScreen(
    state: ApiUiState,
    onReload: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleRequireToken: (Boolean) -> Unit,
    onRotateToken: () -> Unit,
    onSaveBindScope: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf("") }
    val lanUrl = state.endpoints.firstOrNull { it.scope == "lan" }?.url
    val localUrl = state.endpoints.firstOrNull { it.scope != "lan" }?.url

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader(
                title = "对外接口",
                subtitle = "让其他设备调用 DFlow 的反推能力",
                trailing = {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        androidx.compose.material3.IconButton(onClick = onReload) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新状态")
                        }
                    }
                },
            )

            state.error?.let { error ->
                SettingsNote("读取失败：$error", NoteTone.Danger)
            }

            // ---- 1. 当前状态 -------------------------------------------------
            StatusBanner(state = state, lanUrl = lanUrl, localUrl = localUrl)

            // ---- 2. 开关 -----------------------------------------------------
            SettingsCard(
                icon = Icons.Filled.Bolt,
                title = "开关",
                summary = when {
                    !state.enabled -> "已关闭"
                    !state.requireToken -> "已开启 · 未要求令牌（有风险）"
                    else -> "已开启 · 要求令牌"
                },
                initiallyExpanded = !state.enabled,
                highlighted = !state.enabled,
            ) {
                SettingsSwitchRow(
                    title = "允许其他设备访问",
                    subtitle = "关闭时只有这台设备上的 App 能访问服务",
                    checked = state.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                SettingsSwitchRow(
                    title = "要求访问令牌",
                    subtitle = if (state.requireToken) "调用方必须带上正确的令牌"
                    else "同一网络内任何人都能访问",
                    checked = state.requireToken,
                    onCheckedChange = onToggleRequireToken,
                    enabled = state.enabled,
                    warning = !state.requireToken,
                )
                if (!state.requireToken && state.enabled) {
                    SettingsNote(
                        "同一 Wi-Fi 下的任何设备都能读取你的收藏图片与提示词，建议保持开启令牌。",
                        NoteTone.Danger,
                    )
                }
            }

            // ---- 3. 监听范围 -------------------------------------------------
            SettingsCard(
                icon = Icons.Filled.Dns,
                title = "监听范围",
                summary = if (state.bindScope == "lan") "局域网（其他设备可连接）"
                else "仅本机（其他设备无法连接）",
                initiallyExpanded = state.enabled && state.bindScope != "lan",
            ) {
                SettingsNote(
                    "「仅本机」最安全，其他设备即使有令牌也连不上。" +
                        "要让别的设备调用，必须切到「局域网」。"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeOption(
                        title = "仅本机",
                        subtitle = "127.0.0.1",
                        selected = state.bindScope != "lan",
                        onClick = { onSaveBindScope("local") },
                        modifier = Modifier.weight(1f),
                    )
                    ScopeOption(
                        title = "局域网",
                        subtitle = "0.0.0.0",
                        selected = state.bindScope == "lan",
                        onClick = { onSaveBindScope("lan") },
                        modifier = Modifier.weight(1f),
                    )
                }

                SettingsNote(
                    "切换后会自动重启应用内服务（约十几秒），在此期间服务会短暂中断。",
                    NoteTone.Warning,
                )
            }

            // ---- 4. 令牌 -----------------------------------------------------
            if (state.enabled) {
                SettingsCard(
                    icon = Icons.Filled.Key,
                    title = "访问令牌",
                    summary = if (state.requireToken)
                        if (state.token.isBlank()) "尚未生成" else "已生成"
                    else "未启用",
                ) {
                    if (state.token.isBlank()) {
                        SettingsNote("开启访问后会自动生成一个令牌。", NoteTone.Warning)
                    } else {
                        CopyableValue(
                            value = state.token,
                            onCopied = { copied = "令牌" },
                            label = "X-DFlow-Token 的值",
                            maskable = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRotateToken) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                            Text(" 重新生成", Modifier.padding(start = 4.dp))
                        }
                    }
                    SettingsNote(
                        "重新生成会让所有已配置的客户端立即失效，需要重新填一次。",
                        NoteTone.Warning,
                    )
                }

                // ---- 5. 连接信息（页面存在的主要产出）------------------------
                SettingsCard(
                    icon = Icons.Filled.Terminal,
                    title = "连接信息",
                    summary = lanUrl?.let { it } ?: "局域网未开启，其他设备无法连接",
                    initiallyExpanded = true,
                    highlighted = state.enabled,
                ) {
                    if (state.bindScope != "lan") {
                        SettingsNote(
                            "当前是「仅本机」，其他设备连不上。要到上面把监听范围切到「局域网」。",
                            NoteTone.Warning,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (localUrl != null) {
                            CopyableValue(
                                value = localUrl,
                                onCopied = { copied = "本机地址" },
                                label = "本机（仅这台手机）",
                            )
                        }
                        if (lanUrl != null) {
                            CopyableValue(
                                value = lanUrl,
                                onCopied = { copied = "局域网地址" },
                                label = "局域网（其他设备用这个）",
                            )
                        } else if (state.enabled) {
                            SettingsNote(
                                "没有检测到局域网地址：当前可能未连接 Wi-Fi。",
                                NoteTone.Warning,
                            )
                        }
                    }

                    // The server already builds this; showing it saves the user
                    // from assembling JSON out of a URL and a header name.
                    val snippet = remember(state.token, state.requireToken, lanUrl, localUrl) {
                        buildClientConfig(
                            url = lanUrl ?: localUrl.orEmpty(),
                            token = if (state.requireToken) state.token else "",
                        )
                    }
                    if (snippet.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "客户端配置（复制到支持 MCP 的客户端）",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        text = snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            CopyButton(value = snippet, onCopied = { copied = "客户端配置" })
                        }
                    }

                    SettingsNote(
                        "在客户端里添加一个 HTTP 类型的 MCP 服务器即可。"
                            + if (state.requireToken) {
                                "请求头需带 ${state.headerName}。"
                            } else {
                                "当前未要求令牌。"
                            }
                    )
                }
            }

            // ---- 6. 说明（放在最后，不再挡住操作）---------------------------
            SettingsCard(
                icon = Icons.Filled.Lock,
                title = "可用工具与安全",
                summary = "13 个 MCP 工具 · 密钥不外泄",
            ) {
                SettingsNote(
                    "可用工具：list_prompts（列出已反推的提示词）、get_prompt（按 id 取提示词）、" +
                        "get_image（取回原图）、reverse_image（图片反推提示词）、" +
                        "list_pending、complete_pending 等，与电脑上的 MCP 一致。"
                )
                SettingsNote(
                    "reverse_image 在已配置视觉模型时会用你的 API 识别图片，产生费用。" +
                        "未配置模型时不产生费用：它返回图片与反推说明，由调用方自己的模型完成。",
                    NoteTone.Warning,
                )
                SettingsNote(
                    "令牌、Danbooru 密钥、Pixiv Cookie、翻译与 AI 密钥都不会通过远程接口返回；" +
                        "修改账户、查看日志、断开 MCP 会话等操作也只允许在本机执行。",
                    NoteTone.Info,
                )
            }

            Box(Modifier.padding(bottom = 12.dp)) { }
        }

        // Copy confirmation, so a silent copy is not mistaken for a failure.
        if (copied.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Text(
                    "已复制$copied",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The answer to "is this working right now, and from where?" in one block. */
@Composable
private fun StatusBanner(state: ApiUiState, lanUrl: String?, localUrl: String?) {
    val (headline, tone) = when {
        !state.enabled -> "对外访问已关闭" to NoteTone.Neutral
        state.bindScope != "lan" -> "已开启，但只监听本机" to NoteTone.Warning
        lanUrl == null -> "已开启，但未检测到局域网地址" to NoteTone.Warning
        else -> "可以从其他设备访问" to NoteTone.Info
    }

    Surface(
        color = when (tone) {
            NoteTone.Info -> MaterialTheme.colorScheme.primaryContainer
            NoteTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
            NoteTone.Danger -> MaterialTheme.colorScheme.errorContainer
            NoteTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    !state.enabled -> "其他设备无法访问服务。打开下面的开关即可启用。"
                    state.bindScope != "lan" -> "监听范围是 127.0.0.1，其他设备连不上。需切到「局域网」。"
                    lanUrl != null -> lanUrl
                    else -> "请确认手机已连接 Wi-Fi。"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (lanUrl != null && state.enabled && state.bindScope == "lan")
                    FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

/**
 * A bind-scope choice.
 *
 * Rendered as two explicit options rather than a pair of buttons, because the
 * current value was previously only stated in a sentence of prose that was easy
 * to miss.
 */
@Composable
private fun ScopeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Builds the client-side MCP configuration snippet.
 *
 * Mirrors what the server returns in `clientConfig`, but with the address the
 * user is actually going to connect over (the LAN one) rather than the first
 * entry, which is loopback and useless from another device.
 */
private fun buildClientConfig(url: String, token: String): String {
    if (url.isBlank()) return ""
    val header = if (token.isNotBlank()) {
        "\n          \"headers\": { \"X-DFlow-Token\": \"$token\" }"
    } else {
        ""
    }
    return """
{
  "mcpServers": {
    "dflow": {
      "type": "http",
      "url": "$url"$header
    }
  }
}
    """.trim()
}
