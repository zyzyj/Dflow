package com.zyzyj.dflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zyzyj.dflow.data.DflowApi
import com.zyzyj.dflow.ui.DflowApp
import com.zyzyj.dflow.ui.DflowViewModel
import com.zyzyj.dflow.ui.theme.DflowTheme

/**
 * Entry point.
 *
 * The first launch unpacks the Linux userland before any screen can work, so this
 * shows a real progress screen for that phase and hands over to the Compose UI
 * once the embedded server answers.
 *
 * The failure screen carries its own log export on purpose. Previously the export
 * lived only inside Settings, which is unreachable when the server never starts —
 * so the one situation where a log is essential was the one where it could not be
 * obtained, and a bug report could only be a screenshot of a truncated on-screen
 * log.
 */
class MainActivity : ComponentActivity() {

    /** Created once per activity, not per recomposition, so gallery state and
     *  the in-flight coroutines survive screen switches. */
    private val viewModel: DflowViewModel by lazy {
        DflowViewModel(DflowApi(NodeService.PORT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        // Start persisting immediately: the unpack and the first launch are the
        // phases most likely to fail, and their log has to outlive the process.
        LogStore.init(this)
        NodeService.start(this)

        setContent {
            DflowTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @Composable
    private fun Root() {
        val serviceState by NodeService.state.collectAsState()
        val logs by NodeService.log.collectAsState()

        when (val state = serviceState) {
            is NodeService.State.Ready -> DflowApp(viewModel)

            is NodeService.State.Failed -> FailureScreen(
                reason = state.reason,
                logs = logs,
                onRetry = { NodeService.start(this@MainActivity) },
            )

            NodeService.State.Stopped -> StatusScreen(
                title = "正在启动本地服务…",
                detail = "",
                logs = logs,
                onRetry = null,
            )

            NodeService.State.Bootstrapping -> StatusScreen(
                title = "正在准备运行环境…",
                detail = "首次启动需要解压运行环境，请稍候",
                logs = logs,
                onRetry = null,
            )
        }
    }
}

@Composable
private fun StatusScreen(
    title: String,
    detail: String,
    logs: String,
    onRetry: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) { Text("重试") }
        } else {
            Box(Modifier.padding(top = 24.dp)) { CircularProgressIndicator() }
        }

        if (logs.isNotBlank()) {
            Text(
                text = logs.takeLast(3000),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * Failure screen.
 *
 * Built around the fact that the user has to be able to get diagnostics *out* of a
 * broken app. It offers export, copy-to-clipboard (works with no storage access at
 * all), and the whole host log rather than a tail, plus a plain-language hint for
 * the failures that have actually been seen in the field.
 */
@Composable
private fun FailureScreen(
    reason: String,
    logs: String,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showFullLog by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val text = LogStore.buildHeader(context) + "\n--- 主机日志 ---\n" + LogStore.readAll()
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.onSuccess { exported = true }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "启动失败",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        // A specific hint beats a raw error string. These are the causes that have
        // actually occurred, matched against the message the launcher produces.
        diagnose(logs, reason)?.let { hint ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Button(onClick = onRetry) { Text("重试") }
            OutlinedButton(onClick = { showFullLog = !showFullLog }) {
                Text(if (showFullLog) "收起日志" else "查看全部日志")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            OutlinedButton(onClick = { exportLauncher.launch("dflow-failure-log.txt") }) {
                Text("导出日志")
            }
            OutlinedButton(onClick = {
                // Copying needs no permission and no file dialog, so it works even
                // if the storage picker misbehaves.
                val text = LogStore.buildHeader(context) + "\n--- 主机日志 ---\n" + LogStore.readAll()
                clipboard.setText(AnnotatedString(text.take(100_000)))
            }) {
                Text("复制日志")
            }
        }

        if (exported) {
            Text(
                "已导出到所选位置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            SelectionContainer {
                Text(
                    text = (if (showFullLog) LogStore.readAll() else logs.takeLast(2500))
                        .ifBlank { "（暂无日志）" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(10.dp)
                        .heightIn(max = if (showFullLog) 6000.dp else 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        Box(Modifier.padding(bottom = 20.dp)) { LinearProgressIndicator() }
    }
}

/**
 * Maps a known failure to an actionable hint.
 *
 * The launcher can only report "the server did not start"; the useful part is in
 * the linker or Node output. Recognising the signatures turns a dead end into
 * something the user can act on (or report accurately).
 */
private fun diagnose(logs: String, reason: String): String? {
    val all = logs + "\n" + reason
    return when {
        all.contains("libandroid-shmem.so") || all.contains("CANNOT LINK EXECUTABLE") ->
            "运行环境依赖库缺失（系统链接器启动 PRoot 失败）。这是打包问题，不是你的操作有误。" +
                "请把这份日志发给开发者；如果是旧版本，请安装最新测试包。"

        all.contains("Too many symbolic links") || all.contains("Too many levels of symbolic links") ->
            "解压运行环境时遇到符号链接循环，通常是上一次解压残留导致的。" +
                "可以在系统设置里清除本应用数据后重新打开（会同时删掉收藏缓存）。"

        all.contains("ENOSPC") || all.contains("No space left") ->
            "存储空间不足。运行环境需要解压出约 300 MB，请先清理空间再重试。"

        all.contains("Cannot find module") ->
            "运行环境中的服务端文件不完整（打包遗漏）。请安装最新测试包并把日志发给开发者。"

        all.contains("command not found") || all.contains("not found: node") ->
            "运行环境里没有找到 Node。可能是解压未完成，清除应用数据后重试。"

        reason.contains("超时") || reason.contains("timeout") ->
            "服务在超时前没有就绪。可能是首次解压较慢，也可能是运行环境有问题；" +
                "先重试一次，仍失败请导出日志。"

        else -> null
    }
}
