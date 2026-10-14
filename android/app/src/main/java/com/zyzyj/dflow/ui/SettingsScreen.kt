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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zyzyj.dflow.data.Account

/**
 * Settings, written natively so the fields follow the platform's own text
 * handling, autofill and keyboard behaviour instead of a web form's.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onSave: (Account) -> Unit,
    onTestNetwork: () -> Unit,
    onMessageShown: () -> Unit,
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
            hint = "个人资料页里的 Personal API Key",
            secret = true,
        )
        Field(
            value = account.pixivCookie,
            onValueChange = { account = account.copy(pixivCookie = it) },
            label = "Pixiv Cookie (PHPSESSID)",
            hint = "浏览器登录后从开发者工具复制",
            secret = true,
            singleLine = false,
        )

        HorizontalDivider()

        SectionTitle("网络")
        Text(
            text = "服务端自己访问画站（含图片代理），所以代理要配在这里，而不是只给手机挂代理。留空表示直连。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(
            value = account.proxyUrl,
            onValueChange = { account = account.copy(proxyUrl = it) },
            label = "代理地址",
            hint = "例如 127.0.0.1:7890，可省略 http://",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onSave(account); onTestNetwork() }, enabled = !state.testing) {
                Text("保存并测试连通性")
            }
            if (state.testing) CircularProgressIndicator(Modifier.size(18.dp))
        }

        state.networkTest?.let { result ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "当前生效代理：${result.proxy.ifBlank { "无（直连）" }}",
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
                        val detail = if (probe.ok) "HTTP ${probe.status}" else probe.error.ifBlank { "HTTP ${probe.status}" }
                        Text(
                            text = "${probe.name} — $detail（${probe.ms}ms）",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        SectionTitle("翻译引擎")
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
            LaunchedEffect(message) { onMessageShown() }
        }

        HorizontalDivider()
        SectionTitle("关于")
        Text(
            text = "版本 $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "本地服务运行在应用内部（Alpine + Node），数据保存在应用私有目录，卸载前请注意备份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
