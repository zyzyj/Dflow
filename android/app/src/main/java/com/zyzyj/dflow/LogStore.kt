package com.zyzyj.dflow

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent log for the whole app.
 *
 * Why a file: the host kept only an in-memory ring buffer for the startup screen,
 * so once the process was killed (or the phone rebooted) whatever went wrong was
 * unrecoverable. A user reporting "it didn't work" had nothing to send, and the
 * failure usually happened long before they could look at the screen.
 *
 * Writes are synchronous and best-effort. Losing a line is acceptable; blocking or
 * crashing the server because the disk is full is not.
 */
object LogStore {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 5
    private const val MEMORY_LINES = 500

    private val lock = Any()
    private val memory = ConcurrentLinkedDeque<String>()
    private val broken = AtomicBoolean(false)
    private var directory: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Called once at startup; safe to call again. */
    fun init(context: Context) {
        synchronized(lock) {
            if (directory == null) {
                directory = File(context.filesDir, "logs").apply { mkdirs() }
            }
        }
    }

    private fun currentFile(): File? {
        val dir = directory ?: return null
        return File(dir, "host.log")
    }

    /**
     * Appends one line.
     *
     * The in-memory copy is always kept so the log can be shown instantly without
     * reading the file back, which matters on the startup-failure screen.
     */
    fun append(tag: String, message: String) {
        if (broken.get()) return
        val line = "${stamp.format(Date())} [$tag] $message"
        memory.addLast(line)
        while (memory.size > MEMORY_LINES) memory.pollFirst()

        synchronized(lock) {
            val file = currentFile() ?: return
            try {
                rotateIfNeeded(file)
                file.appendText(line + "\n")
            } catch (_: Throwable) {
                // A read-only or full filesystem must not take the app down.
                broken.set(true)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.isFile || file.length() < MAX_FILE_BYTES) return
        val dir = file.parentFile ?: return
        // host.log -> host.log.1 -> ... and drop the oldest.
        for (index in MAX_FILES - 1 downTo 1) {
            val from = File(dir, if (index == 1) "host.log" else "host.log.${index - 1}")
            val to = File(dir, "host.log.$index")
            if (from.isFile) {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
    }

    /** Recent lines from memory, for the in-app viewer. */
    fun tail(maxLines: Int = 300): String =
        memory.toList().takeLast(maxLines).joinToString("\n")

    /**
     * Full log text, oldest first.
     *
     * Read from disk rather than memory so an export includes everything that
     * survived an earlier session.
     */
    fun readAll(): String {
        val dir = directory ?: return tail(MEMORY_LINES)
        val files = dir.listFiles { f -> f.name.startsWith("host.log") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return tail(MEMORY_LINES)
        return buildString {
            files.forEach { file ->
                runCatching { append(file.readText()) }
            }
        }
    }

    fun sizeBytes(): Long {
        val dir = directory ?: return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun clear() {
        memory.clear()
        val dir = directory ?: return
        dir.listFiles { f -> f.name.startsWith("host.log") }?.forEach { it.delete() }
    }

    /**
     * Describes the app and device, the way a maintainer needs it.
     *
     * Included at the top of every export: without the version and the state of
     * the runtime directories, a log alone leaves too many possibilities open.
     */
    fun buildHeader(context: Context): String {
        val paths = RuntimePaths(context)
        fun dirInfo(dir: File): String {
            val exists = if (dir.isDirectory) "存在" else "缺失"
            val size = if (dir.isDirectory) "${dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576} MB" else "-"
            return "$exists / $size"
        }
        val hostStamp = runCatching { paths.stampFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return buildString {
            appendLine("===== DFlow 日志导出 =====")
            appendLine("导出时间: ${stamp.format(Date())}")
            appendLine("应用版本: ${BuildConfigSafe.versionName()} (${BuildConfigSafe.versionCode()})")
            appendLine("包名: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("服务端口: ${NodeService.PORT}")
            appendLine("服务状态: ${NodeService.state.value}")
            appendLine("解压标记: ${hostStamp ?: "(无)"}")
            appendLine("app 目录: ${dirInfo(paths.appDir)}")
            appendLine("data 目录: ${dirInfo(paths.dataDir)}")
            appendLine("rootfs: ${dirInfo(paths.rootfsDir)}")
            appendLine("日志大小: ${sizeBytes()} 字节")
            appendLine("===== 日志正文 =====")
        }
    }
}
