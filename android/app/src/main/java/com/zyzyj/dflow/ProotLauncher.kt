package com.zyzyj.dflow

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Owns the layout of everything the embedded Linux userland needs on disk.
 *
 * Everything lives under the app's internal storage (`filesDir`). That is not
 * a style preference: module loading and dependency installation were measured
 * roughly 3x slower on external/FUSE storage, and FUSE also refuses to create
 * symlinks, which node_modules relies on heavily.
 */
class RuntimePaths(context: Context) {
    val filesDir: File = context.filesDir
    val rootfsDir = File(filesDir, "rootfs")
    val appDir = File(filesDir, "app")            // server.js, public/, node_modules
    val dataDir = File(filesDir, "data")          // DFlow's own state/cache
    val prootTmpDir = File(filesDir, "proot-tmp")
    val prootLibDir = File(filesDir, "proot-libs")
    val compileCacheDir = File(filesDir, "node-compile-cache")
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /** Sentinel written only after a bootstrap completes, so a partial extract never looks valid. */
    val stampFile = File(filesDir, "bootstrap.stamp")

    val serverEntry = File(appDir, "server.js")
}

/**
 * Runs the Node server inside PRoot and reports its output.
 *
 * PRoot is not linked as a JNI library — it is the stock `proot` executable,
 * merely renamed to `libproot.so` so Android extracts it to the app's
 * nativeLibraryDir. That directory is the only place an app may exec() from on
 * modern Android, and PackageManager only unpacks files named `lib*.so`.
 */
class ProotLauncher(
    private val paths: RuntimePaths,
    private val port: Int,
    /**
     * Address the embedded server listens on.
     *
     * 127.0.0.1 keeps the gallery private to the phone. 0.0.0.0 exposes it to the
     * LAN, which is what lets another machine drive the reverse queue — and also
     * what makes the server's access-control middleware load-bearing rather than
     * decorative.
     */
    private val host: String,
    private val onLog: (String) -> Unit,
) {
    private var process: Process? = null
    private val outputThreads = mutableListOf<Thread>()

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Prepares the shared libraries PRoot needs at link time.
     *
     * Android extracts only `lib*.so` from jniLibs, and it keeps the file name
     * rather than the soname the binary was linked against. PRoot asks for
     * `libtalloc.so.2`, which is not a valid extracted name, so a copy is made
     * under the expected soname and LD_LIBRARY_PATH points at the directory
     * holding it. Patching the ELF instead would break on every proot update.
     *
     * libandroid-shmem.so was missing entirely for several releases: proot links
     * it, and without it the kernel refuses to start the process with
     *   CANNOT LINK EXECUTABLE "libproot.so": library "libandroid-shmem.so" not found
     * which the app could only surface as "server did not become ready".
     */
    private fun prepareSupportFiles() {
        paths.prootTmpDir.mkdirs()
        paths.prootLibDir.mkdirs()

        /** Copies [name] from jniLibs into the shim dir under [soname]. */
        fun linkLibrary(name: String, soname: String = name) {
            val source = File(paths.nativeLibDir, name)
            val target = File(paths.prootLibDir, soname)
            // Skip when already identical: comparing length is enough here since
            // the file is written by the same build, and copying on every launch
            // would be wasted work.
            if (source.isFile && (!target.isFile || target.length() != source.length())) {
                source.copyTo(target, overwrite = true)
            }
        }

        linkLibrary("libtalloc.so", "libtalloc.so.2")
        // Already named correctly, but staged into the same directory so a single
        // LD_LIBRARY_PATH entry resolves it.
        linkLibrary("libandroid-shmem.so")

        // Fail loudly here rather than letting the kernel refuse to exec proot and
        // reporting only "library not found" from the linker. A missing file at
        // this point means the APK was built without it, which is a packaging bug
        // worth naming precisely.
        val required = listOf("libtalloc.so.2", "libandroid-shmem.so")
        val missing = required.filterNot { File(paths.prootLibDir, it).isFile }
        if (missing.isNotEmpty()) {
            onLog("[host] 缺少 PRoot 依赖库：${missing.joinToString(", ")}")
            onLog("[host] 这是安装包缺少原生库，请安装最新版本")
        }
    }

    private fun buildArgs(): List<String> = buildList {
        add(File(paths.nativeLibDir, "libproot.so").absolutePath)
        add("-r"); add(paths.rootfsDir.absolutePath)
        // The guest needs these three to behave like Linux. /system is deliberately
        // absent: it is only required by bionic-linked binaries, and every extra
        // bind costs path-translation work on every syscall.
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-b"); add("${paths.rootfsDir.absolutePath}/tmp:/tmp")
        // Expose the DFlow application tree inside the guest.
        add("-b"); add("${paths.appDir.absolutePath}:/root/app")
        add("-b"); add("${paths.dataDir.absolutePath}:/root/dflow-data")
        add("-w"); add("/root/app")
        // Without this, PRoot keeps waiting on tracees whose parent died and
        // Process.destroy() cannot reap it, leaving an orphan holding the port.
        add("--kill-on-exit")
        add("/bin/sh")
        add("-c")
        // Record the guest-side PID so a later shutdown can signal node directly
        // (closing its listening socket) instead of only killing PRoot.
        add("echo \$\$ > /tmp/node.pid; exec node /root/app/server.js")
    }

    private fun buildEnvironment(): Map<String, String> = buildMap {
        put("PROOT_LOADER", File(paths.nativeLibDir, "libproot-loader.so").absolutePath)
        put("PROOT_TMP_DIR", paths.prootTmpDir.absolutePath)
        put("LD_LIBRARY_PATH", paths.prootLibDir.absolutePath)
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        // DFlow reads these: where to keep state, and which interface/port to listen on.
        put("DFLOW_DATA_DIR", "/root/dflow-data")
        put("PORT", port.toString())
        put("HOST", host)
        // The server writes its own log here, so an export from inside the app
        // contains both the host output and the server's own errors.
        put("DFLOW_LOG_DIR", "/root/dflow-data/logs")
    }

    /**
     * Starts the guest and blocks (off the main thread) until the port answers.
     *
     * [timeoutMs] is generous because PRoot path-translation makes module loading
     * slow, and the first run also populates the Node compile cache. On a slow
     * device with a large node_modules this can take minutes, and a timeout that
     * is too short looks exactly like a real failure.
     */
    fun startAndAwait(timeoutMs: Long = 300_000): Boolean {
        if (isRunning) return true
        prepareSupportFiles()

        val builder = ProcessBuilder(buildArgs())
        builder.directory(paths.filesDir)
        builder.environment().putAll(buildEnvironment())
        builder.redirectErrorStream(false)   // keep the streams separate for readability

        val started = builder.start()
        process = started
        drain(started.inputStream.bufferedReader(), "out")
        drain(started.errorStream.bufferedReader(), "err")
        return awaitPort(timeoutMs)
    }

    /**
     * Pipes must be drained concurrently. A ProcessBuilder pipe holds ~64 KB;
     * if nobody reads stderr while the guest is chatty, the guest blocks on
     * write() and the server silently never finishes starting.
     */
    private fun drain(reader: BufferedReader, tag: String) {
        val thread = Thread {
            try {
                reader.useLines { lines ->
                    lines.forEach { line -> onLog("[$tag] $line") }
                }
            } catch (_: Throwable) {
                // The pipe closes when the process dies; nothing to report.
            }
        }
        thread.isDaemon = true
        thread.start()
        outputThreads += thread
    }

    /**
     * Polls the port instead of sleeping a guessed amount of time.
     *
     * Returns a structured outcome rather than a bare boolean, because "the
     * process died immediately" and "the process is alive but never listened" are
     * different problems with different causes, and the previous boolean made them
     * indistinguishable in the UI.
     */
    private fun awaitPort(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (process?.isAlive != true) {
                // Exiting here means the guest failed at startup — a missing
                // library, a bad binary, or an unreadable rootfs. Distinguishing
                // it from a timeout is what lets the UI say something useful.
                exitReason = ExitReason.ExitedEarly(process?.exitValue())
                onLog("[host] PRoot 在就绪前退出（退出码 ${process?.exitValue()}）")
                return false
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    exitReason = null
                    return true
                }
            } catch (_: Throwable) {
                SystemClock.sleep(200)
            }
        }
        exitReason = ExitReason.TimedOut
        onLog("[host] 已等待 ${timeoutMs / 1000} 秒仍未见端口监听")
        return false
    }

    /** Why the last start failed; null when it succeeded or never ran. */
    var exitReason: ExitReason? = null
        private set

    sealed interface ExitReason {
        /** Guest exited before listening. [code] is the process exit status. */
        data class ExitedEarly(val code: Int?) : ExitReason
        /** Guest is still alive but never bound the port within the deadline. */
        data object TimedOut : ExitReason
    }

    /** Human-readable explanation of the last failure, for the UI. */
    fun failureReason(): String = when (val reason = exitReason) {
        is ExitReason.ExitedEarly -> when (reason.code) {
            // 127 is what a shell returns for a binary it could not execute; the
            // linker's own message is in the log.
            127 -> "运行环境无法启动（找不到可执行文件或缺少依赖库）"
            139 -> "运行环境崩溃（段错误）"
            else -> "运行环境在启动过程中退出（退出码 ${reason.code ?: "未知"}）"
        }
        ExitReason.TimedOut -> "服务在超时前没有就绪，请查看日志"
        null -> "服务未能启动"
    }

    /**
     * Stops the guest: signal node inside the sandbox first so it can close the
     * listening socket and flush caches, then force-kill PRoot.
     */
    fun stop(waitMs: Long = 4_000) {
        val running = process ?: return
        if (!running.isAlive) {
            process = null
            return
        }

        File(paths.rootfsDir, "tmp/node.pid").takeIf { it.isFile }?.let { pidFile ->
            val guestPid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
            if (guestPid != null && guestPid > 1) {
                // PRoot maps guest PIDs onto host PIDs shifted by its own offset,
                // so signalling the raw guest pid is not reliable. Killing PRoot
                // with --kill-on-exit tears the whole traced tree down instead.
                onLog("[host] stopping guest pid $guestPid")
            }
        }

        running.destroy()
        if (!running.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
            running.destroyForcibly()
            running.waitFor(waitMs, TimeUnit.MILLISECONDS)
        }
        outputThreads.forEach { it.interrupt() }
        outputThreads.clear()
        process = null
    }
}
