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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.zyzyj.dflow.data.Account

/**
 * Settings.
 *
 * Rebuilt around three problems with the previous layout:
 *
 * 1. It was one 400-line scroll containing five unrelated topics. Finding the
 *    log export meant scrolling past twenty input fields. Now each topic is a
 *    collapsible card and the card header states the section's current status, so
 *    nothing needs to be opened just to see whether it is configured.
 *
 * 2. There were two save buttons ("save and test connectivity" and "save all
 *    settings") with no indication of which one applied. There is now one save
 *    action, shown only when there is something to save.
 *
 * 3. Edits could be silently lost: change the proxy, scroll away, and it was
 *    gone with no warning. A bar appears while the form differs from the saved
 *    state, with explicit save and discard.
 *
 * The "save then test" button is kept but moved inside the proxy card, because
 * testing before saving measures the *old* proxy — the server only installs it
 * when the setting is persisted.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    logExportName: String,
    onSave: (Account) -> Unit,
    onTestNetwork: () -> Unit,
    onExportLogs: (String) -> Unit,
    onViewLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Draft state, compared against the saved account to detect pending edits.
    var account by remember(state.state.account) { mutableStateOf(state.state.account) }
    val dirty = account != state.state.account

    val proxyConfigured = account.proxyUrl.isNotBlank()
    val danbooruConfigured = account.loginName.isNotBlank() && account.loginKey.isNotBlank()
    val pixivConfigured = account.pixivCookie.isNotBlank()
    val translatorCount = listOf(
        account.deeplKey, account.tencentSecretId, account.volcAccessKey,
    ).count { it.isNotBlank() }
    val accountCount = listOf(danbooruConfigured, pixivConfigured).count { it }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader(
                title = "设置",
                subtitle = "版本 $appVersion",
            )

            SettingsCard(
                icon = Icons.Filled.Cloud,
                title = "网络与代理",
                summary = if (proxyConfigured) "已配置 ${account.proxyUrl}" else "直连（未使用代理）",
                // Expanded when unset: this is the setting that most often needs
                // attention, and hiding it would be unhelpful.
                initiallyExpanded = !proxyConfigured,
                highlighted = !proxyConfigured,
            ) {
                SettingsNote(
                    "DFlow 的请求由应用内的服务发出，包括每一张图片的下载，所以代理要填在这里。" +
                        "只给手机挂代理、这里留空，画廊和 AI 都拉不到东西。"
                )

                SettingsField(
                    value = account.proxyUrl,
                    onValueChange = { account = account.copy(proxyUrl = it) },
                    label = "代理地址",
                    hint = "例如 127.0.0.1:7890 或 http://192.168.1.2:7890（http/https，暂不支持 socks5）",
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Save first, then probe: the server installs the proxy when
                    // the setting is persisted, so testing beforehand would
                    // measure the previous value.
                    OutlinedButton(
                        onClick = { onSave(account); onTestNetwork() },
                        enabled = !state.testing && !state.saving,
                    ) {
                        Text("保存并测试")
                    }
                    if (state.testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在探测…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.networkTest?.let { result ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("当前生效", style = MaterialTheme.typography.labelMedium)
                                StatusChip(
                                    text = result.proxy.ifBlank { "直连" },
                                    tone = if (result.results.any { it.ok }) NoteTone.Info
                                    else NoteTone.Danger,
                                )
                            }
                            result.results.forEach { probe ->
                                ProbeRow(
                                    ok = probe.ok,
                                    name = probe.name,
                                    detail = if (probe.ok) "HTTP ${probe.status}  ${probe.ms}ms"
                                    else "${probe.error.ifBlank { "HTTP ${probe.status}" }}  ${probe.ms}ms",
                                )
                            }
                            if (result.results.none { it.ok }) {
                                SettingsNote(
                                    "全部不可达。若手机本身能访问画站，请确认代理地址与端口正确，" +
                                        "部分代理需要带 http:// 前缀。",
                                    tone = NoteTone.Warning,
                                )
                            } else if (result.results.any { !it.ok }) {
                                SettingsNote(
                                    "部分站点不可达。画站被墙时通常需要代理，GitHub 通说明网络本身正常。",
                                    tone = NoteTone.Warning,
                                )
                            }
                        }
                    }
                }
            }

            SettingsCard(
                icon = Icons.Filled.Language,
                title = "画站账号",
                summary = when (accountCount) {
                    0 -> "未配置（只能浏览公开内容）"
                    2 -> "Danbooru 与 Pixiv 均已配置"
                    else -> if (danbooruConfigured) "已配置 Danbooru" else "已配置 Pixiv"
                },
                initiallyExpanded = accountCount == 0 && !proxyConfigured,
            ) {
                SettingsField(
                    value = account.loginName,
                    onValueChange = { account = account.copy(loginName = it) },
                    label = "Danbooru 用户名",
                    hint = "填账户名称，不是邮箱",
                )
                SettingsField(
                    value = account.loginKey,
                    onValueChange = { account = account.copy(loginKey = it) },
                    label = "Danbooru API Key",
                    hint = "个人资料页的 Personal API Key；留空也能浏览公开内容",
                    secret = true,
                )
                SettingsNote(
                    if (danbooruConfigured) "已填写。若登录失败，先到「网络与代理」测试连通性。"
                    else "不填也能浏览公开内容，但不能看受限制的分级。",
                    tone = if (danbooruConfigured) NoteTone.Info else NoteTone.Neutral,
                )

                SettingsField(
                    value = account.pixivCookie,
                    onValueChange = { account = account.copy(pixivCookie = it) },
                    label = "Pixiv Cookie (PHPSESSID)",
                    hint = "从浏览器开发者工具复制；手机版不提供官方登录（需要桌面 Chrome）",
                    secret = true,
                    singleLine = false,
                )
            }

            SettingsCard(
                icon = Icons.Filled.Translate,
                title = "翻译引擎",
                summary = if (translatorCount == 0) "未配置（Google 免密钥可用）"
                else "已配置 $translatorCount 个付费引擎",
            ) {
                SettingsNote(
                    "用于把日文标签翻译成中文。只填一个也能用，Google 翻译无需密钥。"
                )
                SettingsField(
                    value = account.deeplKey,
                    onValueChange = { account = account.copy(deeplKey = it) },
                    label = "DeepL Key",
                    secret = true,
                )
                SettingsField(
                    value = account.tencentSecretId,
                    onValueChange = { account = account.copy(tencentSecretId = it) },
                    label = "腾讯云 SecretId",
                    secret = true,
                )
                SettingsField(
                    value = account.tencentSecretKey,
                    onValueChange = { account = account.copy(tencentSecretKey = it) },
                    label = "腾讯云 SecretKey",
                    secret = true,
                )
                SettingsField(
                    value = account.volcAccessKey,
                    onValueChange = { account = account.copy(volcAccessKey = it) },
                    label = "火山引擎 AccessKey",
                    secret = true,
                )
                SettingsField(
                    value = account.volcSecretKey,
                    onValueChange = { account = account.copy(volcSecretKey = it) },
                    label = "火山引擎 SecretKey",
                    secret = true,
                )
                SettingsSwitchRow(
                    title = "自动切换引擎",
                    subtitle = "首选引擎失败时改用其他已配置的引擎",
                    checked = account.translateFallback,
                    onCheckedChange = { account = account.copy(translateFallback = it) },
                )
            }

            SettingsCard(
                icon = Icons.Filled.Description,
                title = "日志与诊断",
                summary = state.logs.server?.let {
                    "v${it.version}  已运行 ${it.uptimeSeconds / 60} 分钟  日志 ${it.totalBytes / 1024} KB"
                } ?: "导出运行日志用于排查问题",
            ) {
                SettingsNote(
                    "包含 App 日志、PRoot 输出、服务端日志，以及版本、设备、目录状态。" +
                        "不含任何密钥与 Cookie。"
                )

                if (state.logs.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  正在读取…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.logs.server?.let { info ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusChip(
                                    text = "服务端 v${info.version}",
                                    tone = NoteTone.Info,
                                )
                                if (info.commit.isNotBlank()) {
                                    StatusChip(
                                        text = "构建 ${info.commit.take(7)}",
                                        tone = NoteTone.Neutral,
                                    )
                                }
                            }
                            Text(
                                "Node ${info.node}  ${info.platform}/${info.arch}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusChip(
                                    text = "Danbooru ${if (info.account.danbooru) "✓" else "✗"}",
                                    tone = if (info.account.danbooru) NoteTone.Info else NoteTone.Neutral,
                                )
                                StatusChip(
                                    text = "Pixiv ${if (info.account.pixiv) "✓" else "✗"}",
                                    tone = if (info.account.pixiv) NoteTone.Info else NoteTone.Neutral,
                                )
                                StatusChip(
                                    text = if (info.account.aiConfigured) "AI 已配置"
                                    else "AI 未配置",
                                    tone = if (info.account.aiConfigured) NoteTone.Info else NoteTone.Neutral,
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onExportLogs(logExportName) },
                        enabled = !state.logs.exporting,
                    ) {
                        if (state.logs.exporting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("导出日志")
                        }
                    }
                    OutlinedButton(onClick = onViewLogs) { Text("查看") }
                    TextButton(onClick = onClearLogs) { Text("清空") }
                }

                if (state.logs.exportedTo != null) {
                    SettingsNote("已导出。若文件管理器看不到内容，确认所选位置有写入权限。", NoteTone.Info)
                }
            }

            SettingsCard(
                icon = Icons.Filled.Description,
                title = "关于",
                summary = "版本 $appVersion",
            ) {
                SettingsNote(
                    "反推提示词有两种方式：\n" +
                        "• 内置 AI —— 在「反推」页配好视觉模型，手机上直接反推；\n" +
                        "• 外部 Agent —— 在电脑上连接本机 MCP，由 Agent 读图并写回。\n" +
                        "也可以手动填写提示词。"
                )
                SettingsNote(
                    "收藏、缓存与设置都保存在应用私有目录，卸载会一并删除。",
                )
            }

            // Clearance so the last card is not hidden behind the unsaved bar.
            Box(Modifier.padding(bottom = if (dirty) 72.dp else 8.dp)) { }
        }

        UnsavedBar(
            visible = dirty,
            saving = state.saving,
            onSave = { onSave(account) },
            onDiscard = { account = state.state.account },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // The transient result of a save lives outside the cards so it is visible
    // regardless of which card is expanded.
    state.message?.let { message ->
        Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = if (state.messageIsError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
