package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Configuration for the built-in vision model.
 *
 * This exists because reverse-prompting used to require an MCP agent on another
 * machine. Pointing DFlow at an OpenAI-compatible vision endpoint lets the phone
 * reverse cards by itself. The page is separate from Settings because it is a
 * workflow of its own — fetch models, pick one, tune, run a batch — not a field
 * to set once.
 */
@Composable
fun AiScreen(
    state: AiUiState,
    pendingCount: Int,
    onFieldChange: (
        baseUrl: String?, apiKey: String?, model: String?,
        temperature: Float?, maxTokens: Int?, customInstruction: String?,
    ) -> Unit,
    onFetchModels: () -> Unit,
    onSave: () -> Unit,
    onReverseAll: () -> Unit,
    onStopBatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("内置 AI 反推", style = MaterialTheme.typography.headlineSmall)
        Explanation(
            "反推由 DFlow 直接调用视觉模型完成，不再依赖电脑上的 Agent。" +
                "任何兼容 OpenAI 接口的服务都可以：OpenAI、DeepSeek、通义、各类中转，\n" +
                "以及本机或局域网自建的 Ollama / LM Studio / vLLM。"
        )

        HorizontalDivider()
        SectionTitle("接口")

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { onFieldChange(it, null, null, null, null, null) },
            label = { Text("API 地址") },
            placeholder = { Text("https://api.openai.com/v1") },
            // Spell out the rule instead of silently rewriting the value: an
            // earlier build appended /v1 whenever the path lacked a version
            // segment, which broke relays mounted at /openai/v1 or /api and made
            // the failure look like a bad key.
            supportingText = {
                val hint = androidx.compose.runtime.remember(state.baseUrl) { baseUrlWarning(state.baseUrl) }
                Text(
                    text = hint.ifBlank {
                        "按服务商的完整路径填写，原样使用，不会自动补 /v1。" +
                            "可省略 http(s)://（本机与内网自动用 http）。"
                    },
                    color = if (hint.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { onFieldChange(null, it, null, null, null, null) },
            label = { Text("API Key") },
            supportingText = { Text("本机服务若不校验密钥，可留空") },
            singleLine = true,
            // No PasswordVisualTransformation and no KeyboardType.Password on
            // purpose. A masked field plus the system's secure keyboard makes it
            // impossible to verify an API key you are pasting or typing by hand,
            // and secure input methods frequently refuse to offer clipboard
            // suggestions — which is how keys are normally entered.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onFetchModels, enabled = !state.fetchingModels) {
                if (state.fetchingModels) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 拉取模型列表", Modifier.padding(start = 4.dp))
                }
            }
            Text(
                "${state.models.size} 个可用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.modelsError?.let { error ->
            // Multi-line: the server includes the resolved URL and a path hint,
            // which is what makes a 404 explainable instead of mysterious.
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Show exactly what will be requested, so a mismatch with the provider's
        // documentation is visible before pressing the button.
        RequestPreview(baseUrl = state.baseUrl, model = state.model)

        // Model picker. A dropdown when the list was fetched, a plain field
        // otherwise, so an endpoint that does not implement /models is still usable.
        if (state.models.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("模型", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                state.model.ifBlank { "（未选择）" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        TextButton(onClick = { modelMenuOpen = true }) {
                            Text("选择")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        state.models.forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (name == state.model) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    onFieldChange(null, null, name, null, null, null)
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = state.model,
                onValueChange = { onFieldChange(null, null, it, null, null, null) },
                label = { Text("模型名") },
                supportingText = { Text("也可以直接手填，例如 gpt-4o-mini") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider()
        SectionTitle("生成参数")

        Labelled("温度 ${"%.1f".format(state.temperature)}") {
            Slider(
                value = state.temperature,
                onValueChange = { onFieldChange(null, null, null, it, null, null) },
                valueRange = 0f..2f,
                steps = 19,
            )
        }
        Text(
            "偏低（0.2–0.5）更贴近画面事实，偏高（0.8+）描述更丰富但可能加戏。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.maxTokens.toString(),
            onValueChange = { text ->
                text.filter { it.isDigit() }.toIntOrNull()?.let {
                    onFieldChange(null, null, null, null, it, null)
                }
            },
            label = { Text("最大输出长度") },
            supportingText = { Text("提示词很长时调大，默认 2048") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.customInstruction,
            onValueChange = { onFieldChange(null, null, null, null, null, it) },
            label = { Text("额外要求（可选）") },
            supportingText = { Text("附加到每次反推，例如「只输出英文标签」「不要写画师名」") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存配置") }

        HorizontalDivider()
        SectionTitle("批量反推")

        Text(
            "按队列顺序逐张处理，一张失败不影响其余。反推成功的卡片会自动移到「有词区」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.batch.running) {
            val fraction = if (state.batch.total > 0)
                state.batch.processed.toFloat() / state.batch.total else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "进行中 ${state.batch.processed}/${state.batch.total}" +
                    (if (state.batch.failed > 0) "（失败 ${state.batch.failed}）" else ""),
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedButton(onClick = onStopBatch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 停止", Modifier.padding(start = 4.dp))
            }
        } else {
            Button(
                onClick = onReverseAll,
                enabled = state.configured && pendingCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    if (pendingCount > 0) " 开始反推（$pendingCount 张待处理）"
                    else " 没有待反推的卡片",
                    Modifier.padding(start = 4.dp),
                )
            }
            if (!state.configured) {
                Text(
                    "先填写上面的接口与模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.progressText.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "正在生成…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.progressText.takeLast(400),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Client-side mirror of the server's URL rule.
 *
 * The server is authoritative (it is the one making the request), but showing the
 * consequence while typing avoids a round trip just to find out that the path was
 * wrong. Kept deliberately narrow: it only *warns*, never rewrites.
 */
private fun baseUrlWarning(input: String): String {
    val value = input.trim()
    if (value.isEmpty()) return ""
    // Drop scheme for the path check; a malformed URL is not worth guessing at.
    val withoutScheme = value.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
    val slash = withoutScheme.indexOf('/')
    val path = if (slash >= 0) withoutScheme.substring(slash) else ""
    val host = if (slash >= 0) withoutScheme.substring(0, slash) else withoutScheme
    if (host.isBlank() || !host.contains('.')) {
        // Hostnames without a dot are legal on a LAN but usually a typo.
        if (!host.contains(":") && !host.contains(".")) {
            return "地址看起来不完整：缺少主机名或端口。"
        }
    }
    if (path.isEmpty() || path == "/") {
        return "没填路径。多数 OpenAI 兼容服务需要 /v1，请按服务商文档补全（本应用不会自动添加）。"
    }
    if (!Regex("/v\\d+$").containsMatchIn(path)) {
        return "路径为 $path。确认服务端确实在这里监听；若需要 /v1，请自行补全。"
    }
    return ""
}

/**
 * Shows the exact URL that will be requested.
 *
 * The single most useful thing when a fetch fails, because "I typed it exactly
 * like the docs" and the actual request can differ in ways the field does not
 * reveal — a dropped /v1, an added scheme, a missing path.
 */
@Composable
private fun RequestPreview(baseUrl: String, model: String) {
    val resolved = remember(baseUrl) { resolvePreview(baseUrl) }
    if (resolved.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "实际请求地址",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$resolved/models",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "$resolved/chat/completions" + if (model.isNotBlank()) "   （模型 $model）" else "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Mirrors the server's normalisation closely enough to preview the URL. */
private fun resolvePreview(input: String): String {
    val value = input.trim()
    if (value.isEmpty()) return ""
    var url = value
    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
        val host = url.split('/')[0].split(':')[0]
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        url = (if (local) "http://" else "https://") + url
    }
    url = url.trimEnd('/')
    url = url.replace(Regex("/chat/completions$", RegexOption.IGNORE_CASE), "")
    return url
}

@Composable
private fun Explanation(text: String) {
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
private fun ResultRow(ok: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        content()
    }
}
