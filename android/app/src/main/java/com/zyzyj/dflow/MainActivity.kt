package com.zyzyj.dflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zyzyj.dflow.data.DflowApi
import com.zyzyj.dflow.ui.DflowApp
import com.zyzyj.dflow.ui.DflowViewModel
import com.zyzyj.dflow.ui.theme.DflowTheme

/**
 * Entry point.
 *
 * The first launch has to unpack ~140 MB of Linux userland before any screen can
 * work, so the activity shows a real progress screen for that phase and hands
 * over to the Compose UI once the embedded server answers.
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

        when (serviceState) {
            is NodeService.State.Ready -> DflowApp(viewModel)

            is NodeService.State.Failed -> StatusScreen(
                title = "启动失败",
                detail = (serviceState as NodeService.State.Failed).reason,
                logs = logs,
                onRetry = { NodeService.start(this@MainActivity) },
            )

            NodeService.State.Stopped -> StatusScreen(
                title = "正在启动本地服务…",
                detail = "",
                logs = logs,
                onRetry = { NodeService.start(this@MainActivity) },
            )

            NodeService.State.Bootstrapping -> StatusScreen(
                title = "正在准备运行环境…",
                detail = "首次启动需要解压约 140 MB，请稍候",
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
            Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) {
                Text("重试")
            }
        } else {
            Box(Modifier.padding(top = 24.dp)) {
                CircularProgressIndicator()
            }
        }

        // The log is the only way to see what the sandbox is doing; a silent
        // spinner would leave a stuck launch undiagnosable.
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
