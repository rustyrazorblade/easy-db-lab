package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.compactorlease.CreateResult
import com.rustyrazorblade.easydblab.compactorlease.InMemoryConditionalObjects
import com.rustyrazorblade.easydblab.compactorlease.ObjectVersion
import com.rustyrazorblade.easydblab.compactorlease.ReplaceResult
import com.rustyrazorblade.easydblab.compactorlease.VersionedObject
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The whole [ObjectStore] in memory, for tests that need a store with real semantics and no S3.
 *
 * Each bucket is one [InMemoryConditionalObjects], the same fixture the lease tests use, so a plain
 * write here changes the version a conditional replace expects, exactly as a plain PUT changes an
 * S3 ETag. Listings follow S3's rules: a directory is listed under `<key>/`, and a non-recursive
 * listing rolls nested objects up into their first-level directory.
 */
class InMemoryObjectStore : ObjectStore {
    private val buckets = ConcurrentHashMap<String, InMemoryConditionalObjects>()
    private val modified = ConcurrentHashMap<String, Instant>()

    private fun bucket(path: ClusterS3Path): InMemoryConditionalObjects =
        buckets.computeIfAbsent(path.bucket) { InMemoryConditionalObjects() }

    private fun touch(path: ClusterS3Path) {
        modified[path.toUri()] = Instant.now()
    }

    private fun put(
        path: ClusterS3Path,
        content: String,
    ) {
        bucket(path).put(path.getKey(), content)
        touch(path)
    }

    private fun info(path: ClusterS3Path): ObjectStore.FileInfo? =
        bucket(path).read(path.getKey())?.let {
            ObjectStore.FileInfo(
                path,
                it.content
                    .toByteArray()
                    .size
                    .toLong(),
                modified[path.toUri()].toString(),
            )
        }

    override fun uploadFile(
        localFile: File,
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ): ObjectStore.UploadResult {
        require(localFile.exists()) { "Local file does not exist: ${localFile.absolutePath}" }
        put(remotePath, localFile.readText())
        return ObjectStore.UploadResult(remotePath, localFile.length())
    }

    override fun downloadFile(
        remotePath: ClusterS3Path,
        localPath: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadResult {
        val content = readContent(remotePath)
        Files.writeString(localPath, content)
        return ObjectStore.DownloadResult(localPath, localPath.toFile().length())
    }

    override fun listFiles(
        remotePath: ClusterS3Path,
        recursive: Boolean,
        startAfter: String,
    ): List<ObjectStore.FileInfo> {
        val key = remotePath.getKey()
        val prefix = if (key.isEmpty()) "" else "$key/"
        val keys =
            bucket(remotePath)
                .keys()
                .filter { it.startsWith(prefix) && it > startAfter }
                .sorted()
        if (recursive) return keys.mapNotNull { info(ClusterS3Path.fromKey(remotePath.bucket, it)) }
        val (nested, direct) = keys.partition { it.removePrefix(prefix).contains('/') }
        val directories =
            nested
                .map { prefix + it.removePrefix(prefix).substringBefore('/') }
                .distinct()
                .map { ObjectStore.FileInfo(ClusterS3Path.fromKey(remotePath.bucket, it), 0, "") }
        return direct.mapNotNull { info(ClusterS3Path.fromKey(remotePath.bucket, it)) } + directories
    }

    override fun deleteFile(
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ) {
        bucket(remotePath).remove(remotePath.getKey())
        modified.remove(remotePath.toUri())
    }

    override fun fileExists(remotePath: ClusterS3Path): Boolean = bucket(remotePath).read(remotePath.getKey()) != null

    override fun getFileInfo(remotePath: ClusterS3Path): ObjectStore.FileInfo? = info(remotePath)

    override fun downloadDirectory(
        remotePath: ClusterS3Path,
        localDir: Path,
        showProgress: Boolean,
    ): ObjectStore.DownloadDirectoryResult {
        val files = listFiles(remotePath, recursive = true)
        val prefixLength = remotePath.getKey().length
        files.forEach { file ->
            val target =
                localDir.resolve(
                    file.path
                        .getKey()
                        .substring(prefixLength)
                        .trimStart('/'),
                )
            Files.createDirectories(target.parent)
            downloadFile(file.path, target, showProgress = false)
        }
        return ObjectStore.DownloadDirectoryResult(localDir, files.size, files.sumOf { it.size })
    }

    override fun uploadDirectory(
        localDir: Path,
        remotePath: ClusterS3Path,
        showProgress: Boolean,
    ): ObjectStore.UploadDirectoryResult {
        val files =
            localDir
                .toFile()
                .walkTopDown()
                .filter { it.isFile }
                .toList()
        files.forEach { uploadFile(it, remotePath.resolve(localDir.relativize(it.toPath()).toString()), showProgress = false) }
        return ObjectStore.UploadDirectoryResult(remotePath, files.size, files.sumOf { it.length() })
    }

    override fun uploadContent(
        content: String,
        remotePath: ClusterS3Path,
    ) = put(remotePath, content)

    override fun readContent(remotePath: ClusterS3Path): String =
        checkNotNull(bucket(remotePath).read(remotePath.getKey())) { "No object at ${remotePath.toUri()}" }.content

    override fun directoryExists(remotePath: ClusterS3Path): Boolean = listFiles(remotePath, recursive = true).isNotEmpty()

    override fun readVersioned(remotePath: ClusterS3Path): VersionedObject? = bucket(remotePath).read(remotePath.getKey())

    override fun createIfAbsent(
        remotePath: ClusterS3Path,
        content: String,
    ): CreateResult =
        bucket(remotePath).createIfAbsent(remotePath.getKey(), content).also { if (it is CreateResult.Created) touch(remotePath) }

    override fun replaceIfVersionMatches(
        remotePath: ClusterS3Path,
        content: String,
        expected: ObjectVersion,
    ): ReplaceResult =
        bucket(remotePath).replaceIfVersionMatches(remotePath.getKey(), content, expected).also {
            if (it is ReplaceResult.Replaced) touch(remotePath)
        }
}
