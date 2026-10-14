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
import java.io.File

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
        // Start persisting before the bootstrap, so a first-launch failure is
        // recoverable from the log instead of only visible on screen.
        LogStore.init(this)
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

        // Restart is handled in-process: tear the guest down, then launch again
        // with the (re-read) bind address. Tearing down first is what makes the
        // port free for the new listener.
        if (intent?.action == ACTION_RESTART) {
            appendLog("[host] restart requested (bind scope changed)")
            stopServer()
            scope.launch { bootstrapAndLaunch() }
            return START_STICKY
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
                    if (mb > 0) {
                        // Say what is happening and roughly how far along. A bare MB
                        // counter reads like a download and invites the question
                        // "why is it downloading 140 MB when I already installed the
                        // app" — it is unpacking what was already inside the APK.
                        val percent = progress.totalBytes?.takeIf { it > 0 }?.let {
                            (progress.bytes * 100 / it).coerceIn(0, 100)
                        }
                        notify(
                            if (percent != null) "${progress.phase}（本地解压）$percent%"
                            else "${progress.phase}（本地解压）${"%.0f".format(mb)} MB"
                        )
                    }
                }
            }
            _state.value = State.Bootstrapping

            paths.dataDir.mkdirs()
            // Bind address is read from a plain file rather than from server state:
            // it has to be known *before* the guest starts, and the guest is what
            // owns that state. Changing it therefore requires a restart, which the
            // settings screen says explicitly.
            val bindHost = readBindHost()
            val server = ProotLauncher(paths, PORT, bindHost, onLog = ::appendLog)
            launcher = server
            notify("正在启动 Node 服务…")
            appendLog("[host] launching PRoot on $bindHost:$PORT")
            val ready = server.startAndAwait()
            if (ready) {
                appendLog("[host] server is listening on 127.0.0.1:$PORT")
                _state.value = State.Ready
                notify("DFlow 正在运行（127.0.0.1:$PORT）")
            } else {
                // Use the launcher's diagnosis: "exited immediately" (bad
                // environment) and "never listened" (too slow) need different
                // responses from the user, and both used to read as
                // "未能在超时前启动".
                val reason = server.failureReason()
                appendLog("[host] 启动失败：$reason")
                _state.value = State.Failed(reason)
                notify("服务启动失败")
            }
        } catch (error: Throwable) {
            appendLog("[host] bootstrap failed: ${error.message}")
            _state.value = State.Failed(error.message ?: "启动失败")
            notify("启动失败：${error.message}")
        }
    }

    /** Reads the requested bind scope; defaults to loopback-only. */
    private fun readBindHost(): String {
        val file = File(paths.filesDir, BIND_FILE)
        val value = runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return if (value == "lan") "0.0.0.0" else "127.0.0.1"
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
    /** 上一行原文，用于合并连续重复输出。 */
    private var lastLine = ""
    private var repeatCount = 0

    @Synchronized
    private fun appendLog(line: String) {
        // The launcher retries and the guest repeats itself verbatim, so one error
        // can fill the whole buffer and push out the lines that differ — in the
        // field that turned a linker failure into a screen of identical text.
        // Consecutive duplicates are collapsed to a single line plus a count.
        if (line == lastLine) {
            repeatCount += 1
            if (logs.isNotEmpty()) {
                logs.removeLast()
                logs.addLast(if (repeatCount == 2) line else "$line    （重复 $repeatCount 次）")
                _log.value = logs.joinToString("\n")
            }
            LogStore.append("node", line)
            return
        }

        lastLine = line
        repeatCount = 1
        logs.addLast(line)
        while (logs.size > MAX_LOG_LINES) logs.removeFirst()
        _log.value = logs.joinToString("\n")
        // Also persist it: the ring buffer above only survives this process.
        LogStore.append("node", line)
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
        private const val MAX_LOG_LINES = 2000
        const val ACTION_STOP = "com.zyzyj.dflow.STOP"
        const val ACTION_RESTART = "com.zyzyj.dflow.RESTART"

        /** Holds "local" or "lan"; read at startup by [readBindHost]. */
        const val BIND_FILE = "bind-scope.txt"

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
            // startForegroundService, not startService: once the activity is in the
            // background Android 8+ rejects a plain startService with
            // IllegalStateException, and the user would see the bind-scope change
            // silently fail to restart the service.
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops and starts in one command.
         *
         * Done as a single action rather than a stop() then start() from outside,
         * because stopSelf() is asynchronous: the follow-up start could be
         * delivered to the instance that is still shutting down and be dropped,
         * leaving no server running at all.
         */
        fun restart(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
