package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStoragePath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.concurrent.ConcurrentSkipListMap

/**
 * An [ObjectStore] that keeps every object in process memory.
 *
 * It exists so a test that only needs somewhere to put objects exercises the real contract instead
 * of stubbing a mock call by call. It is in the main source set so both test tiers can use it. It
 * follows `S3ObjectStore`'s semantics where they matter to callers: listings are in key order, a
 * directory is listed under `<key>/` so a sibling whose name begins with it is never included, a
 * non-recursive listing rolls deeper keys up into one entry for their directory, and reading a
 * missing object throws. It has no policy layer: anything may be deleted.
 *
 * @param clock gives each object its last-modified time when it is written, so a test can place an
 *   upload before or after a moment its code under test compares against.
 */
class InMemoryObjectStore(
    private val clock: Clock = Clock.systemUTC(),
) : ObjectStore {
    private data class StoredObject(
        val bytes: ByteArray,
        val lastModified: String,
    )

    /** Every object, by `bucket/key`; sorted, so listings come out in key order. */
    private val objects = ConcurrentSkipListMap<String, StoredObject>()

    private fun idOf(path: ClusterStoragePath) = "${path.bucket}/${path.getKey()}"

    private fun put(
        path: ClusterStoragePath,
        bytes: ByteArray,
    ) {
        objects[idOf(path)] = StoredObject(bytes, clock.instant().toString())
    }

    private fun get(path: ClusterStoragePath): StoredObject =
        objects[idOf(path)] ?: throw NoSuchElementException("No object at ${uriOf(path)}")

    override fun uploadFile(
        localFile: File,
        remotePath: ClusterStoragePath,
        showProgress: Boolean,
    ): ObjectStore.UploadResult {
        require(localFile.isFile) { "Local file does not exist: ${localFile.absolutePath}" }
        val bytes = localFile.readBytes()
        put(remotePath, bytes)
        return ObjectStore.UploadResult(remotePath, bytes.size.toLong())
    }

    override fun downloadFile(
        remotePath: ClusterStoragePath,
        localPath: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadResult {
        val bytes = get(remotePath).bytes
        localPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val staged = Files.createTempFile(localPath.toAbsolutePath().parent, ".download", ".tmp")
        try {
            Files.write(staged, bytes)
            Files.move(staged, localPath, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
        return ObjectStore.DownloadResult(localPath, bytes.size.toLong())
    }

    override fun listFiles(
        remotePath: ClusterStoragePath,
        recursive: Boolean,
        startAfter: String,
    ): List<ObjectStore.FileInfo> {
        val directory = remotePath.getKey()
        val keyPrefix = if (directory.isEmpty()) "" else "$directory/"
        val idPrefix = "${remotePath.bucket}/$keyPrefix"
        val matching =
            objects
                .tailMap(idPrefix)
                .entries
                .asSequence()
                .takeWhile { it.key.startsWith(idPrefix) }
                .map { it.key.removePrefix("${remotePath.bucket}/") to it.value }
                .filter { (key, _) -> startAfter.isEmpty() || key > startAfter }
        if (recursive) {
            return matching.map { (key, stored) -> fileInfo(remotePath.bucket, key, stored) }.toList()
        }
        val children = sortedMapOf<String, ObjectStore.FileInfo>()
        for ((key, stored) in matching) {
            val rest = key.removePrefix(keyPrefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                children[key] = fileInfo(remotePath.bucket, key, stored)
            } else {
                val child = keyPrefix + rest.substring(0, slash)
                children.putIfAbsent(child, ObjectStore.FileInfo(ClusterStoragePath.fromKey(remotePath.bucket, child), 0, ""))
            }
        }
        return children.values.toList()
    }

    private fun fileInfo(
        bucket: String,
        key: String,
        stored: StoredObject,
    ) = ObjectStore.FileInfo(ClusterStoragePath.fromKey(bucket, key), stored.bytes.size.toLong(), stored.lastModified)

    override fun deleteFile(
        remotePath: ClusterStoragePath,
        showProgress: Boolean,
    ) {
        objects.remove(idOf(remotePath))
    }

    override fun fileExists(remotePath: ClusterStoragePath): Boolean = objects.containsKey(idOf(remotePath))

    override fun getFileInfo(remotePath: ClusterStoragePath): ObjectStore.FileInfo? =
        objects[idOf(remotePath)]?.let { fileInfo(remotePath.bucket, remotePath.getKey(), it) }

    override fun downloadDirectory(
        remotePath: ClusterStoragePath,
        localDir: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadDirectoryResult {
        val base = localDir.toAbsolutePath()
        Files.createDirectories(base)
        val directory = remotePath.getKey()
        val files = listFiles(remotePath, recursive = true)
        for (file in files) {
            val relative =
                file.path
                    .getKey()
                    .removePrefix(directory)
                    .trimStart('/')
            downloadFile(file.path, base.resolve(relative), showProgress = false)
        }
        return ObjectStore.DownloadDirectoryResult(localDir, files.size, files.sumOf { it.size })
    }

    override fun uploadDirectory(
        localDir: Path,
        remotePath: ClusterStoragePath,
        showProgress: Boolean,
    ): ObjectStore.UploadDirectoryResult {
        val dir = localDir.toFile()
        require(dir.isDirectory) { "Local directory does not exist or is not a directory: ${localDir.toAbsolutePath()}" }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var totalBytes = 0L
        for (file in files) {
            totalBytes += uploadFile(file, remotePath.resolve(file.relativeTo(dir).invariantSeparatorsPath), showProgress = false).fileSize
        }
        return ObjectStore.UploadDirectoryResult(remotePath, files.size, totalBytes)
    }

    override fun uploadContent(
        content: String,
        remotePath: ClusterStoragePath,
    ) = put(remotePath, content.toByteArray())

    override fun readContent(remotePath: ClusterStoragePath): String = String(get(remotePath).bytes)

    override fun directoryExists(remotePath: ClusterStoragePath): Boolean = listFiles(remotePath, recursive = true).isNotEmpty()

    override fun uriOf(path: ClusterStoragePath): String = "mem://${path.bucket}/${path.getKey()}"
}
