package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Remote access screen.
 *
 * Reverse-prompting lives on the phone, so another machine can only use it if the
 * phone's server is reachable. That is a real convenience and a real risk at the
 * same time, and this screen is built around making the trade-off legible: what
 * becomes reachable, what is required to reach it, and how to turn it back off.
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
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("对外接口", style = MaterialTheme.typography.headlineSmall)
        InfoCard(
            "开放后，同一网络里的其他设备可以调用 DFlow 的反推能力：读取收藏里已反推的提示词、"
                + "取回原图、以及把图片反推成提示词（MCP over HTTP，13 个工具）。\n\n"
                + "默认关闭。开启前请确认你信任当前所在的网络。"
        )

        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  正在读取…", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        SectionTitle("开关")

        ToggleRow(
            title = "允许其他设备访问",
            subtitle = "关闭时，只有这台设备上的 App 能访问服务",
            checked = state.enabled,
            onCheckedChange = onToggleEnabled,
        )

        ToggleRow(
            title = "要求访问令牌",
            subtitle = if (state.requireToken) "调用方必须带上正确的令牌"
            else "任何人都能访问：同一网络内的设备可直接读取你的收藏与提示词",
            checked = state.requireToken,
            onCheckedChange = onToggleRequireToken,
            warning = !state.requireToken,
        )

        if (!state.requireToken) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "建议保持开启令牌。关闭后，同一 Wi-Fi 下的任何人都能读你的收藏图片与提示词。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()
        SectionTitle("令牌")

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRotateToken) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 重新生成", Modifier.padding(start = 4.dp))
            }
            if (state.token.isNotBlank()) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.token))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 复制令牌", Modifier.padding(start = 4.dp))
                }
            }
        }

        if (state.token.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = state.token,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                "尚未生成令牌。开启访问后会自动生成一个。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "重新生成会让已配置的客户端立即失效，需要重新填一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        SectionTitle("监听范围")

        Text(
            "当前：${if (state.bindScope == "lan") "局域网（0.0.0.0）" else "仅本机（127.0.0.1）"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (state.bindScope == "lan")
                "局域网内的设备可以通过下面的地址访问。"
            else
                "只监听本机时，其他设备即使有令牌也连不上——这是最安全的状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSaveBindScope("local") },
                enabled = state.bindScope != "local" && state.enabled,
            ) { Text("改为仅本机") }
            OutlinedButton(
                onClick = { onSaveBindScope("lan") },
                enabled = state.bindScope != "lan" && state.enabled,
            ) { Text("改为局域网") }
        }
        Text(
            "修改监听范围需要重启应用内的服务才会生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        SectionTitle("连接信息")

        if (state.endpoints.isEmpty()) {
            Text(
                "开启访问后这里会显示可直接填进客户端的地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.endpoints.forEach { endpoint ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (endpoint.scope == "lan") "局域网地址" else "本机地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                endpoint.url,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = {
                val text = state.endpoints.joinToString("\n") { it.url }
                clipboard.setText(AnnotatedString(text))
            }) { Text("复制全部地址") }

            Text(
                "在支持 MCP 的客户端里添加一个 HTTP 类型的服务器，地址填上面的 /mcp 地址"
                    + (if (state.requireToken) "，并在请求头加上 ${state.headerName}: <令牌>。" else "。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            InfoCard(
                "可用工具包括：list_prompts（列出已反推的提示词）、get_prompt（按 id 取提示词）、"
                    + "get_image（取回原图）、reverse_image（图片反推提示词）、list_pending、"
                    + "complete_pending 等，与电脑上的 MCP 完全一致。\n\n"
                    + "reverse_image 需要 DFlow 已配置视觉模型；未配置时会返回图片与反推说明，"
                    + "由调用方自己的模型识别。"
            )
        }

        HorizontalDivider()

        Text(
            "安全说明：无论令牌开关如何，令牌、Danbooru 密钥、Pixiv Cookie、翻译与 AI 密钥"
                + "都不会通过远程接口返回；修改账户、查看日志、断开 MCP 会话等操作也只允许在本机执行。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
            Text("刷新状态")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    warning: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (warning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
