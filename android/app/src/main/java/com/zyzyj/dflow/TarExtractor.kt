package com.zyzyj.dflow

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Minimal tar reader/writer-free extractor for the Alpine rootfs.
 *
 * Why hand-rolled instead of a library: the rootfs contains symlinks and
 * hardlinks plus setuid/setgid permission bits that `java.util.zip` and most
 * "extract to File" helpers silently drop. Alpine's /bin/sh, /usr/bin/env and
 * the whole /lib tree are symlinks, so losing them produces a rootfs that
 * extracts "successfully" and then cannot run a single command.
 *
 * Security: every entry path and every symlink target is resolved and checked
 * to stay inside the destination directory. Without that check a malicious
 * archive can write outside the sandbox via `../../` or an absolute symlink
 * (the same class of bug as proot-distro's CVE-2026-54574), and we are running
 * this against a downloaded archive.
 */
object TarExtractor {

    private const val BLOCK = 512

    /** gzip magic number; a bare tar starts with a file name instead. */
    private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())

    /**
     * Extracts an archive that may be either .tar or .tar.gz.
     *
     * The format is detected from the file itself rather than from the name,
     * because aapt2 decompresses `.gz` assets during packaging and strips the
     * suffix: the archive arrives as `rootfs.tar` holding an uncompressed tar.
     * Sniffing the magic bytes works either way, so the build can rename or
     * re-compress the asset without breaking the app.
     */
    fun extractArchive(archive: File, destination: File, onProgress: (Long) -> Unit = {}) {
        val isGzip = archive.inputStream().use { input ->
            val head = ByteArray(2)
            val read = input.read(head)
            read == 2 && head[0] == GZIP_MAGIC[0] && head[1] == GZIP_MAGIC[1]
        }
        BufferedInputStream(archive.inputStream(), 1 shl 16).use { fileIn ->
            if (isGzip) {
                GZIPInputStream(fileIn, 1 shl 16).use { gz ->
                    extractTar(gz, destination, onProgress)
                }
            } else {
                extractTar(fileIn, destination, onProgress)
            }
        }
    }

    /** Extracts a .tar.gz into [destination], replacing any previous contents. */
    fun extractTarGz(archive: File, destination: File, onProgress: (Long) -> Unit = {}) {
        extractArchive(archive, destination, onProgress)
    }

    private fun extractTar(input: InputStream, destination: File, onProgress: (Long) -> Unit) {
        val destCanonical = destination.canonicalFile
        if (!destCanonical.exists() && !destCanonical.mkdirs()) {
            throw IOException("Cannot create ${destCanonical.path}")
        }

        val header = ByteArray(BLOCK)
        var extractedBytes = 0L
        // Set by a GNU 'L' entry to carry the real name for the member after it.
        var pendingLongName: String? = null

        while (true) {
            if (!readFully(input, header)) break

            // Two consecutive zero blocks mark the end of the archive. A single
            // zero block only means padding, so we must not stop on the first.
            if (header.all { it == 0.toByte() }) {
                if (!readFully(input, header) || header.all { it == 0.toByte() }) break
            }

            val name = readString(header, 0, 100)
            if (name.isEmpty()) break

            val prefix = readString(header, 345, 155)
            val typeFlag = header[156].toInt().toChar()
            val size = readOctal(header, 124, 12)
            val sizeInBlocks = ((size + BLOCK - 1) / BLOCK) * BLOCK

            // GNU long-name entry: it carries the real (over 100 char) path for
            // the *next* member. Without this the long path would be truncated
            // to 100 bytes and files would land at wrong names. Emitted by tar
            // for deep node_modules paths.
            if (typeFlag == 'L') {
                val longName = readFixedSizeString(input, size)
                skipFully(input, sizeInBlocks - size)
                pendingLongName = longName
                continue
            }
            if (typeFlag == 'K') { // long link target; not used by our archives
                skipFully(input, sizeInBlocks)
                continue
            }

            val effectiveName = pendingLongName?.also { pendingLongName = null } ?: name
            val fullName = if (prefix.isEmpty()) effectiveName else "$prefix/$effectiveName"

            val linkName = readString(header, 157, 100)
            val mode = readOctal(header, 100, 8)

            val target = safeResolve(destCanonical, fullName)
                ?: throw IOException("Unsafe path in archive: $fullName")

            when (typeFlag) {
                '5' -> { // directory
                    if (!target.isDirectory && !target.mkdirs()) {
                        throw IOException("Cannot create directory ${target.path}")
                    }
                }
                '2' -> { // symlink
                    target.parentFile?.mkdirs()
                    target.delete()
                    // Reject a link whose target escapes the rootfs. Resolving
                    // an absolute target against the archive root mirrors how
                    // the symlink will behave once the rootfs is chrooted.
                    val linkTarget = resolveLinkTarget(destCanonical, target, linkName)
                        ?: throw IOException("Symlink escapes rootfs: $fullName -> $linkName")
                    try {
                        android.system.Os.symlink(linkTarget, target.path)
                    } catch (error: Throwable) {
                        // Fall back to a copy when the filesystem refuses symlinks
                        // (external/FUSE storage). Better a wrong-looking rootfs
                        // than a hard failure during bootstrap.
                        val source = File(target.parentFile, linkTarget)
                        if (source.exists()) source.copyTo(target, overwrite = true)
                    }
                }
                '1' -> { // hardlink
                    target.parentFile?.mkdirs()
                    target.delete()
                    val linkTarget = safeResolve(destCanonical, linkName)
                    if (linkTarget != null && linkTarget.exists()) {
                        try {
                            android.system.Os.link(linkTarget.path, target.path)
                        } catch (error: Throwable) {
                            linkTarget.copyTo(target, overwrite = true)
                        }
                    }
                }
                else -> { // regular file (also covers typeflag '\0')
                    target.parentFile?.mkdirs()
                    target.delete()
                    FileOutputStream(target).use { out ->
                        copyExactly(input, out, size)
                    }
                    // The executable bits matter: PRoot refuses to run a /bin/sh
                    // without them, and reports only "'/bin/sh' is not executable".
                    //
                    // Any of the three bits is enough — an archive may legitimately
                    // have 0o100 (owner only, e.g. 0o700 files) and the guest runs
                    // as a single user, so requiring the group/other bits would
                    // skip exactly the restrictive-but-valid case.
                    //
                    // Note the mask is 0o111, not 0b001_000_000_000: the binary
                    // literal used to be here and it equals 0o1000, which is the
                    // *sticky* bit. It never matched 0o755, so no file was ever
                    // marked executable and the server could not start at all.
                    if (mode and 0b001_001_001L != 0L) {
                        target.setExecutable(true, false)
                    }
                    // setuid/setgid are meaningless here (no real root) and are
                    // deliberately dropped.
                }
            }

            // Skip the file's padding to the next block boundary.
            if (typeFlag != '5' && typeFlag != '2' && typeFlag != '1') {
                skipFully(input, sizeInBlocks - size)
                extractedBytes += size
                onProgress(extractedBytes)
            } else if (sizeInBlocks > 0) {
                skipFully(input, sizeInBlocks)
            }
        }
    }

    /**
     * Resolves [entryName] under [root], returning null if the result would
     * land outside the root. Guards against `..` traversal and absolute paths.
     *
     * An entry that names the root itself (`.`, `./`) is legitimate — GNU tar
     * emits `./` as the first member of an archive built with `tar -C dir .` —
     * so it resolves to [root] rather than being rejected.
     */
    private fun safeResolve(root: File, entryName: String): File? {
        val cleaned = entryName.trimStart('/').removePrefix("./").trimEnd('/')
        // Root itself: nothing to create, but not an attack either.
        if (cleaned.isEmpty() || cleaned == ".") return root
        val candidate = File(root, cleaned).canonicalFile
        val rootPath = root.path + File.separator
        return if (candidate.path == root.path || candidate.path.startsWith(rootPath)) candidate else null
    }

    /**
     * Validates a symlink target. Returns the literal target to write, or null
     * if it escapes the rootfs. Absolute targets are allowed (they are resolved
     * relative to the rootfs once PRoot sets it as /) but must not contain a
     * `..` sequence that climbs above the root.
     */
    private fun resolveLinkTarget(root: File, linkLocation: File, linkName: String): String? {
        if (linkName.isEmpty()) return null
        if (linkName.startsWith("/")) {
            // Absolute: check the normalized form stays in the guest tree.
            val segments = linkName.split('/').filter { it.isNotEmpty() }
            var depth = 0
            for (segment in segments) {
                when (segment) {
                    ".." -> { depth--; if (depth < 0) return null }
                    "." -> Unit
                    else -> depth++
                }
            }
            return linkName
        }
        // Relative: resolve against the directory containing the link.
        val resolved = File(linkLocation.parentFile, linkName).canonicalFile
        val rootPath = root.path + File.separator
        return if (resolved.path.startsWith(rootPath)) linkName else null
    }

    /** Reads a NUL-terminated string of exactly [length] bytes, without padding trims. */
    private fun readFixedSizeString(input: InputStream, length: Long): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) break
            read += count
        }
        var end = 0
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    private fun readString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun readOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        // GNU tar uses a base-256 encoding for very large values; the high bit of
        // the first byte is the marker. The rootfs never needs it, but reading it
        // avoids a silent zero-size file if it ever appears.
        if (length > 0 && (buffer[offset].toInt() and 0x80) != 0) {
            var value = 0L
            for (i in 0 until length) {
                value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
            }
            return value and 0x7FFFFFFFFFFFFFFFL
        }
        var value = 0L
        for (i in 0 until length) {
            val byte = buffer[offset + i].toInt() and 0xFF
            if (byte == 0 || byte == ' '.code) continue
            if (byte < '0'.code || byte > '7'.code) continue
            value = value * 8 + (byte - '0'.code)
        }
        return value
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return read > 0
            read += count
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, length: Long) {
        val buffer = ByteArray(1 shl 16)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Unexpected end of archive")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
