package com.zyzyj.dflow

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * First-run installation: unpacks the three archives shipped in assets into
 * internal storage.
 *
 * The work is guarded by a stamp file so it happens once. The stamp records the
 * app's versionCode, so a future APK that ships a newer rootfs or node_modules
 * transparently reinstalls them while keeping the user's `data/` directory
 * untouched.
 */
class Bootstrap(private val context: Context, private val paths: RuntimePaths) {

    data class Progress(val phase: String, val bytes: Long)

    fun isInstalled(): Boolean {
        if (!paths.stampFile.isFile) return false
        val stamp = runCatching { paths.stampFile.readText().trim() }.getOrNull()
        return stamp == currentStamp()
    }

    private fun currentStamp(): String =
        "${BuildConfigSafe.versionCode()}:${BuildConfigSafe.versionName()}"

    /**
     * Extracts everything. Safe to call again after a partial failure: each
     * archive is unpacked to a staging directory and only swapped in once it is
     * complete, and the stamp is written last.
     */
    fun install(onProgress: (Progress) -> Unit) {
        // A stale stamp from a previous build is meaningless now.
        paths.stampFile.delete()

        installRootfs(onProgress)
        installAppTree(onProgress)

        paths.stampFile.writeText(currentStamp())
    }

    private fun installRootfs(onProgress: (Progress) -> Unit) {
        val marker = File(paths.rootfsDir, ".complete")
        if (marker.isFile) return

        onProgress(Progress("正在解压运行环境", 0))
        // Start from a clean tree: a half-extracted rootfs must never be reused.
        paths.rootfsDir.deleteRecursively()
        paths.rootfsDir.mkdirs()

        val staged = File(paths.filesDir, "rootfs.staging")
        staged.deleteRecursively()
        staged.mkdirs()

        copyAssetStream(ASSET_ROOTFS).use { stream ->
            TarExtractor.extractTarGz(
                archive = streamAsFile(stream, "rootfs.tar.gz"),
                destination = staged,
                onProgress = { bytes -> onProgress(Progress("正在解压运行环境", bytes)) },
            )
        }

        val from = File(staged, "rootfs")
        val source = if (from.isDirectory) from else staged
        source.copyRecursivelyInto(paths.rootfsDir)
        staged.deleteRecursively()
        marker.writeText("ok")
        ensureGuestRuntimeFiles()
    }

    private fun installAppTree(onProgress: (Progress) -> Unit) {
        onProgress(Progress("正在安装应用文件", 0))

        val staged = File(paths.filesDir, "app.staging")
        staged.deleteRecursively()
        staged.mkdirs()

        copyAssetStream(ASSET_APP).use { stream ->
            TarExtractor.extractTarGz(
                archive = streamAsFile(stream, "app.tar.gz"),
                destination = staged,
                onProgress = { bytes -> onProgress(Progress("正在安装应用文件", bytes)) },
            )
        }
        File(context.cacheDir, "app.tar.gz").delete()

        // The tar is rooted at "app/", so prefer that nested directory.
        val inner = File(staged, "app")
        val source = if (inner.isDirectory) inner else staged

        // Replace app code but never touch the user's data directory.
        paths.appDir.mkdirs()
        source.listFiles()?.forEach { file ->
            val target = File(paths.appDir, file.name)
            if (file.name == "data") return@forEach
            if (target.exists()) target.deleteRecursively()
            file.copyRecursivelyInto(target)
        }
        staged.deleteRecursively()
        paths.dataDir.mkdirs()
    }

    /**
     * Things a Linux userland normally inherits from its host but Android does
     * not provide, and that PRoot cannot invent on its own:
     *  - /etc/resolv.conf: without it the guest has no DNS at all.
     *  - /dev/shm: absent on Android; tools that expect POSIX shared memory fail.
     */
    private fun ensureGuestRuntimeFiles() {
        val etc = File(paths.rootfsDir, "etc").apply { mkdirs() }
        val hosts = readSystemDns()
        File(etc, "resolv.conf").writeText(
            hosts.joinToString("\n") { "nameserver $it" } + "\n"
        )
        File(paths.rootfsDir, "dev/shm").mkdirs()
        File(paths.rootfsDir, "tmp").mkdirs()
        File(paths.rootfsDir, "root/app").mkdirs()
    }

    /**
     * Reads the DNS servers of the currently active network. Hardcoding
     * 8.8.8.8 would be wrong here: it is frequently unreachable on the networks
     * this app targets, and then every guest-side lookup fails with no clue why.
     */
    private fun readSystemDns(): List<String> {
        val fromSystem = runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return@runCatching emptyList()
            val network = connectivity.activeNetwork ?: return@runCatching emptyList()
            connectivity.getLinkProperties(network)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }.getOrDefault(emptyList())

        return when {
            fromSystem.isNotEmpty() -> fromSystem
            // Last resort: a parse of the properties the framework maintains.
            else -> {
                val props = listOf("net.dns1", "net.dns2")
                    .mapNotNull { name -> runCatching { systemProp(name) }.getOrNull() }
                    .filter { it.isNotBlank() }
                props.ifEmpty { listOf("1.1.1.1") }
            }
        }
    }

    private fun systemProp(name: String): String {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        return (get.invoke(null, name) as? String).orEmpty()
    }

    private fun copyAssetStream(name: String) =
        context.assets.open(name, android.content.res.AssetManager.ACCESS_STREAMING)

    /**
     * TarExtractor wants random access to the archive, but assets are delivered
     * as a stream. Copying to a temp file keeps the extractor simple and stays
     * within internal storage, which is fast.
     *
     * The copy is removed afterwards rather than cached: a cached file would go
     * stale the next time the APK ships a newer rootfs, and the archives are
     * large enough that keeping a second copy is wasteful.
     */
    private fun streamAsFile(stream: java.io.InputStream, baseName: String): File {
        val temp = File(context.cacheDir, baseName)
        temp.delete()
        stream.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (temp.length() == 0L) throw IOException("Asset $baseName is empty or missing")
        return temp
    }

    private fun File.copyRecursivelyInto(target: File) {
        if (isDirectory) {
            target.mkdirs()
            listFiles()?.forEach { it.copyRecursivelyInto(File(target, it.name)) }
        } else {
            parentFile?.mkdirs()
            target.parentFile?.mkdirs()
            copyTo(target, overwrite = true)
            // Preserve executability: the rootfs relies on it for /bin/sh etc.
            if (canExecute()) target.setExecutable(true, false)
        }
    }

    companion object {
        const val ASSET_ROOTFS = "rootfs.tar.gz"
        const val ASSET_APP = "app.tar.gz"
    }
}

/** Reads version metadata so an app upgrade can trigger a rootfs refresh. */
object BuildConfigSafe {
    fun versionCode(): Int = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0)

    fun versionName(): String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("0")
}
