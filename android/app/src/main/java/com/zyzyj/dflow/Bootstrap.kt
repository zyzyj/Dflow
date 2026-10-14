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

    data class Progress(val phase: String, val bytes: Long, val totalBytes: Long = 0)

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

        // Materialise once: this copies the archive out of assets into a real file,
        // and doing it per progress tick would re-copy tens of MB repeatedly.
        val archive = materialiseAsset(ASSET_ROOTFS)
        val total = archive.length()
        onProgress(Progress("正在解压运行环境（本地，不联网）", 0, total))
        // Start from a clean tree: a half-extracted rootfs must never be reused.
        paths.rootfsDir.deleteRecursively()
        paths.rootfsDir.mkdirs()

        // Extract straight into the destination. An earlier version unpacked to
        // a staging directory and then copied it across, which destroyed the
        // rootfs: copying follows symlinks, so links to directories were walked
        // into (var/run -> ../run and var/spool/mail -> ../mail form a loop, and
        // the OS reports "Too many symbolic links") and dangling links like
        // etc/mtab -> ../proc/mounts failed outright. The tar itself is the
        // atomic unit here, not the directory copy.
        TarExtractor.extractArchive(
            archive = archive,
            destination = paths.rootfsDir,
            onProgress = { bytes ->
                onProgress(Progress("正在解压运行环境（本地，不联网）", bytes, total))
            },
        )

        marker.writeText("ok")
        ensureGuestRuntimeFiles()
    }

    private fun installAppTree(onProgress: (Progress) -> Unit) {
        val archive = materialiseAsset(ASSET_APP)
        val total = archive.length()
        onProgress(Progress("正在安装应用文件（本地，不联网）", 0, total))

        // The archive is rooted at app/, so it lands in its own directory; the
        // app tree is then assembled by moving children into place rather than
        // copying them, for the same symlink reason as the rootfs.
        val staged = File(paths.filesDir, "app.staging")
        staged.deleteRecursively()
        staged.mkdirs()

        TarExtractor.extractArchive(
            archive = archive,
            destination = staged,
            onProgress = { bytes ->
                onProgress(Progress("正在安装应用文件（本地，不联网）", bytes, total))
            },
        )

        // The tar is rooted at "app/", so prefer that nested directory.
        val inner = File(staged, "app")
        val source = if (inner.isDirectory) inner else staged

        // Replace app code but never touch the user's data directory.
        paths.appDir.mkdirs()
        source.listFiles()?.forEach { file ->
            if (file.name == "data") return@forEach
            val target = File(paths.appDir, file.name)
            if (target.exists()) target.deleteRecursively()
            // rename() moves the directory entry itself, so symlinks and their
            // targets inside node_modules survive intact. A recursive copy would
            // dereference them.
            if (!file.renameTo(target)) {
                file.copyRecursively(target, overwrite = true)
                file.deleteRecursively()
            }
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

    /**
     * Resolves an asset and copies it to a real file.
     *
     * aapt2 decompresses `.gz` assets and drops the suffix, so the shipped name
     * may be either `rootfs.tar.gz` or `rootfs.tar`. Both are probed rather
     * than assumed, because a wrong guess costs the user a failed first launch.
     */
    private fun materialiseAsset(baseName: String): File {
        val candidates = listOf(baseName, baseName.removeSuffix(".gz"))
        val temp = File(context.cacheDir, "asset.tmp")
        for (name in candidates) {
            val stream = runCatching {
                context.assets.open(name, android.content.res.AssetManager.ACCESS_STREAMING)
            }.getOrNull() ?: continue
            temp.delete()
            stream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            if (temp.length() > 0) return temp
        }
        throw IOException("缺少资源文件：${candidates.joinToString(" 或 ")}")
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
