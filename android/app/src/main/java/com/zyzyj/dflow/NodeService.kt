package com.zyzyj.dflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the embedded Node server as a foreground service.
 *
 * Why a foreground service at all: the user's normal flow is to open the
 * gallery, then switch away. A background process would be the first thing the
 * low-memory killer reclaims, and the WebView would then start failing with
 * connection errors that look like a bug in DFlow. A foreground service is the
 * only supported way to make the process expensive to kill.
 *
 * The type is `specialUse`: Android 15 restricts `dataSync` foreground services
 * to 6 hours per 24, and a gallery server is expected to stay reachable.
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var paths: RuntimePaths
    private var launcher: ProotLauncher? = null

    override fun onCreate() {
        super.onCreate()
        paths = RuntimePaths(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // showModalOverLockscreen is not needed; a low-importance ongoing
        // notification is enough to keep the process privileged.
        startForeground(NOTIFICATION_ID, buildNotification("正在启动本地服务…"))

        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        if (launcher?.isRunning == true) return START_STICKY

        scope.launch { bootstrapAndLaunch() }
        // START_STICKY: if Android kills us under memory pressure, bring the
        // server back rather than leaving the WebView permanently broken.
        return START_STICKY
    }

    private suspend fun bootstrapAndLaunch() {
        try {
            val bootstrap = Bootstrap(this, paths)
            if (!bootstrap.isInstalled()) {
                notify("正在解压运行环境…")
                bootstrap.install { progress ->
                    val mb = progress.bytes / (1024.0 * 1024.0)
                    if (mb > 0) notify("${progress.phase}  ${"%.1f".format(mb)} MB")
                }
            }
            _state.value = State.Bootstrapping

            paths.dataDir.mkdirs()
            val server = ProotLauncher(paths, PORT, onLog = ::appendLog)
            launcher = server
            notify("正在启动 Node 服务…")
            appendLog("[host] launching PRoot on port $PORT")
            val ready = server.startAndAwait()
            if (ready) {
                appendLog("[host] server is listening on 127.0.0.1:$PORT")
                _state.value = State.Ready
                notify("DFlow 正在运行（127.0.0.1:$PORT）")
            } else {
                appendLog("[host] server did not become ready in time")
                _state.value = State.Failed("服务未能在超时前启动，请查看日志")
                notify("服务启动失败")
            }
        } catch (error: Throwable) {
            appendLog("[host] bootstrap failed: ${error.message}")
            _state.value = State.Failed(error.message ?: "启动失败")
            notify("启动失败：${error.message}")
        }
    }

    private fun stopServer() {
        launcher?.stop()
        launcher = null
        _state.value = State.Stopped
    }

    override fun onDestroy() {
        // The server must not outlive the service, or the port stays bound and
        // the next launch cannot start.
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- logging -----------------------------------------------------------

    private val logs = ArrayDeque<String>()

    @Synchronized
    private fun appendLog(line: String) {
        logs.addLast(line)
        while (logs.size > MAX_LOG_LINES) logs.removeFirst()
        _log.value = logs.joinToString("\n")
    }

    // ---- notification ------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地服务",
            NotificationManager.IMPORTANCE_LOW,   // no sound: this runs constantly
        ).apply { description = "保持 DFlow 的本地服务运行" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DFlow")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    sealed interface State {
        data object Stopped : State
        data object Bootstrapping : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    companion object {
        const val PORT = 4173
        private const val CHANNEL_ID = "dflow-node"
        private const val NOTIFICATION_ID = 4173
        private const val MAX_LOG_LINES = 400
        const val ACTION_STOP = "com.zyzyj.dflow.STOP"

        private val _state = MutableStateFlow<State>(State.Stopped)
        val state: StateFlow<State> = _state

        private val _log = MutableStateFlow("")
        val log: StateFlow<String> = _log

        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NodeService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
