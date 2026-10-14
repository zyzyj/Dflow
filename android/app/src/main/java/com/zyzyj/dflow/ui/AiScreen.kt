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
            placeholder = { Text("api.openai.com 或 192.168.1.5:11434") },
            supportingText = {
                Text("可省略 http(s):// 与 /v1；本机与内网地址会自动用 http")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { onFieldChange(null, it, null, null, null, null) },
            label = { Text("API Key") },
            supportingText = { Text("本机服务若不校验密钥，可留空") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            ResultRow(ok = false, text = error)
        }

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
