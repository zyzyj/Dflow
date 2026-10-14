package com.zyzyj.dflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zyzyj.dflow.data.Account

/**
 * Settings.
 *
 * The proxy section carries the most explanation because it is the least
 * intuitive part: the app's server makes the outbound requests (including the
 * one that fetches every image), so a proxy configured only on the phone has no
 * effect. The connectivity test exists so the user can see which sites actually
 * answer instead of guessing.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onSave: (Account) -> Unit,
    onTestNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var account by remember(state.state.account) { mutableStateOf(state.state.account) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("网络与代理")

        InfoCard(
            "DFlow 的请求由应用内的服务发出（包括每一张图片的代理下载），" +
                "所以代理要填在这里。只给手机挂代理、这里留空，画廊仍然拉不到图。"
        )

        Field(
            value = account.proxyUrl,
            onValueChange = { account = account.copy(proxyUrl = it) },
            label = "代理地址",
            hint = "例如 127.0.0.1:7890 或 http://192.168.1.2:7890（支持 http/https，暂不支持 socks5）",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Save first, then probe: the server only installs the proxy when the
            // setting is saved, so testing before saving would measure the old one.
            OutlinedButton(
                onClick = { onSave(account); onTestNetwork() },
                enabled = !state.testing && !state.saving,
            ) {
                Text("保存并测试连通性")
            }
            if (state.testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        state.networkTest?.let { result ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "当前生效：${result.proxy.ifBlank { "无（直连）" }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    result.results.forEach { probe ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = if (probe.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (probe.ok) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            val detail = if (probe.ok) "HTTP ${probe.status}"
                            else probe.error.ifBlank { "HTTP ${probe.status}" }
                            Text(
                                text = "${probe.name} — $detail（${probe.ms}ms）",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    if (result.results.none { it.ok }) {
                        Text(
                            text = "全部不可达。若手机本身能访问画站，请确认代理地址与端口正确；" +
                                "部分代理需要带 http:// 前缀。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        SectionTitle("画站账号")

        Field(
            value = account.loginName,
            onValueChange = { account = account.copy(loginName = it) },
            label = "Danbooru 用户名",
            hint = "填账户名称（不是邮箱）",
        )
        Field(
            value = account.loginKey,
            onValueChange = { account = account.copy(loginKey = it) },
            label = "Danbooru API Key",
            hint = "个人资料页里的 Personal API Key；不填也能浏览公开内容",
            secret = true,
        )
        Field(
            value = account.pixivCookie,
            onValueChange = { account = account.copy(pixivCookie = it) },
            label = "Pixiv Cookie (PHPSESSID)",
            hint = "手机版不提供 Pixiv 官方登录（需要桌面 Chrome），请从浏览器开发者工具复制",
            secret = true,
            singleLine = false,
        )

        HorizontalDivider()
        SectionTitle("翻译引擎")
        Text(
            text = "用于把日文标签翻译成中文。只填一个也能用，Google 翻译无需密钥。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(
            value = account.deeplKey,
            onValueChange = { account = account.copy(deeplKey = it) },
            label = "DeepL Key",
            secret = true,
        )
        Field(
            value = account.tencentSecretId,
            onValueChange = { account = account.copy(tencentSecretId = it) },
            label = "腾讯云 SecretId",
            secret = true,
        )
        Field(
            value = account.tencentSecretKey,
            onValueChange = { account = account.copy(tencentSecretKey = it) },
            label = "腾讯云 SecretKey",
            secret = true,
        )
        Field(
            value = account.volcAccessKey,
            onValueChange = { account = account.copy(volcAccessKey = it) },
            label = "火山引擎 AccessKey",
            secret = true,
        )
        Field(
            value = account.volcSecretKey,
            onValueChange = { account = account.copy(volcSecretKey = it) },
            label = "火山引擎 SecretKey",
            secret = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = account.translateFallback,
                onCheckedChange = { account = account.copy(translateFallback = it) },
            )
            Text("首选引擎失败时自动切换其他引擎", Modifier.padding(start = 8.dp))
        }

        Button(
            onClick = { onSave(account) },
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("保存全部设置")
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.messageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        HorizontalDivider()
        SectionTitle("关于")
        Text(
            text = "版本 $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            "反推提示词有两种方式：\n" +
                "• 内置 AI——在「反推」页配好视觉模型，手机上就能自动反推；\n" +
                "• 外部 Agent——在电脑上连接本机 MCP，由 Agent 读取图片并写回。\n" +
                "也可以直接手动填写提示词。\n\n" +
                "收藏、缓存与设置都保存在应用私有目录，卸载会一并删除。"
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Explanatory block; kept visually distinct from input fields. */
@Composable
private fun InfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String = "",
    secret: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (hint.isNotBlank()) ({ Text(hint) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
