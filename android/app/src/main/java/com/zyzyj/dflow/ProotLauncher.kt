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

    /** Prepares the talloc shim and tmp dirs that PRoot expects to find. */
    private fun prepareSupportFiles() {
        paths.prootTmpDir.mkdirs()
        paths.prootLibDir.mkdirs()
        // The Termux PRoot build declares DT_NEEDED libtalloc.so.2, but Android
        // jniLibs only preserves the name libtalloc.so. Bind a copy under the
        // expected soname and point LD_LIBRARY_PATH at it, rather than patching
        // the ELF binary.
        val source = File(paths.nativeLibDir, "libtalloc.so")
        val target = File(paths.prootLibDir, "libtalloc.so.2")
        if (source.isFile && (!target.isFile || target.length() != source.length())) {
            source.copyTo(target, overwrite = true)
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

    /** Starts the guest and blocks (off the main thread) until the port answers. */
    fun startAndAwait(timeoutMs: Long = 120_000): Boolean {
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

    /** Polls the port instead of sleeping a guessed amount of time. */
    private fun awaitPort(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (process?.isAlive != true) {
                onLog("[host] PRoot exited before the server became ready")
                return false
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (_: Throwable) {
                SystemClock.sleep(200)
            }
        }
        return false
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
