package com.androidagent.app.privileged

import android.os.StatFs
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class EnvironmentInstallResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val error: String? = null,
)

internal class PrivilegedEnvironmentInstaller(
    private val applicationId: String,
    private val nativeLibraryDir: String,
) {
    fun install(archivePath: String, mirrorId: String, toolIds: String): EnvironmentInstallResult {
        val started = System.currentTimeMillis()
        val mirror = MIRRORS[mirrorId]
            ?: return EnvironmentInstallResult(-1, error = "Unsupported mirror: $mirrorId")
        val tools = toolIds.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        if (tools.isEmpty() || !ALLOWED_TOOLS.containsAll(tools)) {
            return EnvironmentInstallResult(-1, error = "Invalid environment tool selection")
        }

        val archive = runCatching { validateArchive(archivePath) }
            .getOrElse { return EnvironmentInstallResult(-1, error = it.message) }
        if (sha256(archive) != ROOTFS_SHA256) {
            return EnvironmentInstallResult(-1, error = "Ubuntu Base SHA-256 mismatch")
        }
        val requiredBytes = if ("java" in tools) 650L * 1_024 * 1_024 else 420L * 1_024 * 1_024
        val base = Paths.get(BASE_DIR)
        val availableBytes = runCatching {
            availableBytesAfterCreatingDirectory(base) { path -> StatFs(path).availableBytes }
        }.getOrElse { error ->
            return EnvironmentInstallResult(-1, error = "Could not prepare environment directory: ${error.message}")
        }
        if (availableBytes < requiredBytes) {
            return EnvironmentInstallResult(-1, error = "Insufficient storage: at least ${requiredBytes / 1_024 / 1_024} MiB free is required")
        }

        val rootfs = base.resolve("rootfs")
        val staging = base.resolve("rootfs.staging")
        val backup = base.resolve("rootfs.backup")
        val shims = base.resolve("shims")
        val shimsStaging = base.resolve("shims.staging")
        val shimsBackup = base.resolve("shims.backup")
        val manifest = base.resolve("install.json")
        var swapped = false
        var rootfsBackedUp = false
        var shimsSwapped = false
        var shimsBackedUp = false
        return try {
            deleteTree(staging)
            deleteTree(backup)
            deleteTree(shimsStaging)
            deleteTree(shimsBackup)
            Files.createDirectories(staging)
            extractRootfs(archive.toPath(), staging)
            configureRootfs(staging, mirror)
            installRuntime(base)

            if (Files.exists(rootfs, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(rootfs, backup)
                rootfsBackedUp = true
            }
            Files.move(staging, rootfs)
            swapped = true

            val packages = packagesFor(tools)
            val commandResult = runGuest(
                base = base,
                command = "export DEBIAN_FRONTEND=noninteractive; " +
                    "apt-get update && apt-get install -y --no-install-recommends $packages && " +
                    "apt-get clean && rm -rf /var/lib/apt/lists/*",
            )
            if (commandResult.exitCode != 0 || commandResult.timedOut) {
                error(commandResult.error ?: commandResult.stderr.ifBlank { "Package installation failed" })
            }

            prepareShims(base, shimsStaging, tools)
            if (Files.exists(shims, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(shims, shimsBackup)
                shimsBackedUp = true
            }
            Files.move(shimsStaging, shims)
            shimsSwapped = true
            writeManifest(manifest, mirrorId, tools)
            deleteTree(backup)
            deleteTree(shimsBackup)
            EnvironmentInstallResult(
                exitCode = 0,
                stdout = "Ubuntu $ROOTFS_VERSION installed with ${tools.sorted().joinToString(", ")} in ${System.currentTimeMillis() - started} ms",
            )
        } catch (error: Throwable) {
            if (swapped) runCatching { deleteTree(rootfs) }
            if (rootfsBackedUp && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.move(backup, rootfs) }
            if (shimsSwapped) runCatching { deleteTree(shims) }
            if (shimsBackedUp && Files.exists(shimsBackup, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.move(shimsBackup, shims) }
            runCatching { deleteTree(staging) }
            runCatching { deleteTree(shimsStaging) }
            EnvironmentInstallResult(-1, error = error.message ?: error::class.java.simpleName)
        }
    }

    private fun validateArchive(rawPath: String): File {
        val archive = File(rawPath).canonicalFile
        require(isAllowedEnvironmentArchivePath(archive.path, applicationId, ROOTFS_FILE)) {
            "Archive is outside the app download directory"
        }
        require(archive.isFile && archive.name == ROOTFS_FILE) { "Ubuntu Base archive is missing" }
        return archive
    }

    private fun extractRootfs(archive: Path, destination: Path) {
        val deferredHardLinks = mutableListOf<Pair<Path, Path>>()
        var entryCount = 0
        var extractedBytes = 0L
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive.toFile()))),
        ).use { input ->
            while (true) {
                val entry = input.nextEntry as? TarArchiveEntry ?: break
                entryCount++
                require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Rootfs archive contains too many entries" }
                if (entry.name.removePrefix("./").trim('/').isBlank()) continue
                val output = safeArchivePath(destination, entry.name)
                ensureParentDirectories(destination, output.parent)
                when {
                    entry.isDirectory -> Files.createDirectories(output)
                    entry.isSymbolicLink -> {
                        Files.deleteIfExists(output)
                        Files.createSymbolicLink(output, Paths.get(entry.linkName))
                    }
                    entry.isLink -> {
                        val target = safeArchivePath(destination, entry.linkName)
                        deferredHardLinks += output to target
                    }
                    entry.isFile -> {
                        extractedBytes += entry.size
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Rootfs archive is unexpectedly large" }
                        Files.newOutputStream(
                            output,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        ).use { fileOutput -> input.copyTo(fileOutput) }
                    }
                    else -> Unit
                }
                if (entry.isDirectory || entry.isFile) runCatching { Os.chmod(output.toString(), entry.mode and 0x1FF) }
            }
        }
        repeat(2) {
            val iterator = deferredHardLinks.iterator()
            while (iterator.hasNext()) {
                val (link, target) = iterator.next()
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(link)
                    runCatching { Files.createLink(link, target) }
                        .getOrElse { Files.copy(target, link, StandardCopyOption.REPLACE_EXISTING) }
                    iterator.remove()
                }
            }
        }
        require(deferredHardLinks.isEmpty()) { "Rootfs archive contains unresolved hard links" }
    }

    private fun configureRootfs(rootfs: Path, aptBaseUrl: String) {
        val aptDirectory = rootfs.resolve("etc/apt/sources.list.d")
        Files.createDirectories(aptDirectory)
        writeUtf8(rootfs.resolve("etc/apt/sources.list"), "# Managed by Muse\n")
        writeUtf8(
            aptDirectory.resolve("ubuntu.sources"),
            """
                Types: deb
                URIs: $aptBaseUrl
                Suites: noble noble-updates noble-backports noble-security
                Components: main restricted universe multiverse
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
            """.trimIndent() + "\n",
        )
        val resolv = rootfs.resolve("etc/resolv.conf")
        Files.deleteIfExists(resolv)
        writeUtf8(resolv, "nameserver 223.5.5.5\nnameserver 119.29.29.29\n")
        Files.createDirectories(rootfs.resolve("root"))
        Files.createDirectories(rootfs.resolve("tmp"))
        Os.chmod(rootfs.resolve("tmp").toString(), 0x1FF)
    }

    private fun installRuntime(base: Path) {
        val sourceDirectory = Paths.get(nativeLibraryDir)
        val targetDirectory = base.resolve("bin")
        Files.createDirectories(targetDirectory)
        RUNTIME_LIBRARIES.forEach { name ->
            val source = sourceDirectory.resolve(name)
            require(Files.isRegularFile(source)) { "Embedded runtime component is missing: $name" }
            val target = targetDirectory.resolve(name)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            Os.chmod(target.toString(), 0x1ED)
        }
        Files.createDirectories(base.resolve("tmp"))
    }

    private fun runGuest(base: Path, command: String): EnvironmentInstallResult {
        val process = ProcessBuilder(
            base.resolve("bin/libproroot.so").toString(),
            "-r", base.resolve("rootfs").toString(),
            "-0",
            "--link2symlink",
            "-w", "/root",
            "/bin/sh", "-lc", command,
        )
            .redirectErrorStream(true)
            .apply { environment()["PROROOT_TMP_DIR"] = base.resolve("tmp").toString() }
            .start()
        process.outputStream.close()
        val reader = Executors.newSingleThreadExecutor()
        val outputFuture = reader.submit<String> {
            process.inputStream.bufferedReader().useLines { lines ->
                buildString {
                    lines.forEach { line ->
                        if (length < MAX_PROCESS_OUTPUT) appendLine(line.take(MAX_PROCESS_OUTPUT - length))
                    }
                }
            }
        }
        val finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        reader.shutdownNow()
        return EnvironmentInstallResult(
            exitCode = if (finished) process.exitValue() else -1,
            stderr = if (finished && process.exitValue() == 0) "" else output,
            timedOut = !finished,
            error = if (finished) null else "Environment package installation timed out",
        )
    }

    private fun prepareShims(base: Path, shims: Path, tools: Set<String>) {
        deleteTree(shims)
        Files.createDirectories(shims)
        val commands = buildMap {
            if ("python" in tools) {
                put("python", "/usr/bin/python3")
                put("python3", "/usr/bin/python3")
                put("pip", "/usr/bin/pip3")
                put("pip3", "/usr/bin/pip3")
            }
            if ("node" in tools) {
                put("node", "/usr/bin/node")
                put("npm", "/usr/bin/npm")
                put("npx", "/usr/bin/npx")
            }
            if ("ssh" in tools) {
                put("ssh", "/usr/bin/ssh")
                put("scp", "/usr/bin/scp")
                put("sftp", "/usr/bin/sftp")
                put("git", "/usr/bin/git")
            }
            if ("java" in tools) put("java", "/usr/bin/java")
        }
        commands.forEach { (name, guestCommand) ->
            val script = """
                #!/system/bin/sh
                workdir=/root
                case "${'$'}PWD" in
                  /sdcard|/sdcard/*) workdir="${'$'}PWD" ;;
                esac
                export PROROOT_TMP_DIR=$BASE_DIR/tmp
                exec $BASE_DIR/bin/libproroot.so -r $BASE_DIR/rootfs -0 --link2symlink -b /sdcard:/sdcard -w "${'$'}workdir" $guestCommand "${'$'}@"
            """.trimIndent() + "\n"
            val path = shims.resolve(name)
            writeUtf8(path, script)
            Os.chmod(path.toString(), 0x1ED)
        }
    }

    private fun writeManifest(path: Path, mirrorId: String, tools: Set<String>) {
        val json = JSONObject()
            .put("version", ROOTFS_VERSION)
            .put("mirror", mirrorId)
            .put("tools", JSONArray(tools.sorted()))
            .put("installedAt", System.currentTimeMillis())
        val temporary = path.resolveSibling("install.json.tmp")
        writeUtf8(temporary, json.toString())
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun packagesFor(tools: Set<String>): String = buildList {
        add("ca-certificates")
        add("curl")
        if ("python" in tools) addAll(listOf("python3", "python3-pip"))
        if ("node" in tools) addAll(listOf("nodejs", "npm"))
        if ("ssh" in tools) addAll(listOf("openssh-client", "git"))
        if ("java" in tools) add("openjdk-17-jre-headless")
    }.joinToString(" ")

    private fun safeArchivePath(root: Path, rawName: String): Path {
        val normalizedName = rawName.removePrefix("./")
        require(normalizedName.isNotBlank() && !normalizedName.startsWith('/')) { "Invalid rootfs archive path" }
        val output = root.resolve(normalizedName).normalize()
        require(output.startsWith(root)) { "Rootfs archive path escapes destination" }
        return output
    }

    private fun ensureParentDirectories(root: Path, parent: Path?) {
        if (parent == null) return
        var current = root
        root.relativize(parent).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Rootfs archive traverses a symbolic link" }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun writeUtf8(path: Path, value: String) {
        Files.write(
            path,
            value.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BASE_DIR = "/data/local/tmp/muse"
        const val ROOTFS_VERSION = "24.04.4"
        const val ROOTFS_FILE = "ubuntu-base-24.04.4-base-arm64.tar.gz"
        const val ROOTFS_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
        const val MAX_ARCHIVE_ENTRIES = 150_000
        const val MAX_EXTRACTED_BYTES = 2L * 1_024 * 1_024 * 1_024
        const val MAX_PROCESS_OUTPUT = 64 * 1_024
        const val INSTALL_TIMEOUT_MINUTES = 20L

        val ALLOWED_TOOLS = setOf("ssh", "python", "node", "java")
        val MIRRORS = mapOf(
            "tuna" to "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            "ustc" to "https://mirrors.ustc.edu.cn/ubuntu-ports",
            "bfsu" to "https://mirrors.bfsu.edu.cn/ubuntu-ports",
        )
        val RUNTIME_LIBRARIES = listOf(
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-bridge.so",
            "libproroot-linker.so",
            "libproroot-stub-loader.so",
        )
    }
}

internal fun availableBytesAfterCreatingDirectory(
    directory: Path,
    storageProbe: (String) -> Long,
): Long {
    Files.createDirectories(directory)
    return storageProbe(directory.toString())
}

internal fun isAllowedEnvironmentArchivePath(
    canonicalPath: String,
    applicationId: String,
    expectedFileName: String,
): Boolean {
    if (applicationId.isBlank() || applicationId.any { it == '/' || it == '\\' }) return false
    if (expectedFileName.isBlank() || expectedFileName.any { it == '/' || it == '\\' }) return false
    val normalized = canonicalPath.replace('\\', '/').trimEnd('/')
    val isExternalStorage = normalized.startsWith("/storage/") ||
        normalized.startsWith("/mnt/") ||
        normalized.startsWith("/sdcard/")
    val expectedSuffix = "/Android/data/$applicationId/files/environment-installer/$expectedFileName"
    return isExternalStorage && normalized.endsWith(expectedSuffix)
}
