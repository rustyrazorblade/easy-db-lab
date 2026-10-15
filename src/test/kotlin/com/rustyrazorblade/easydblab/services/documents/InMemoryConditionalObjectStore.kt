package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore

/**
 * A [ConditionalObjectStore] in memory with S3's semantics: each write gets a new ETag, and a
 * conditional write whose condition fails changes nothing. A hook runs just before a conditional
 * write, standing in for another writer that acts between the read and the write.
 *
 * It lets the report code's decisions be tested without S3; the real conditional writes are
 * tested against MinIO.
 */
class InMemoryConditionalObjectStore : ConditionalObjectStore {
    private class Stored(
        val bytes: ByteArray,
        val contentType: String,
        val etag: String,
    )

    private val objects = sortedMapOf<String, Stored>()
    private var version = 0
    private var hook: (() -> Unit)? = null
    private var hookEveryWrite = false

    /** How many conditional writes were refused. */
    var refusedWrites = 0
        private set

    /** Runs [action] just before the next conditional write only. */
    fun beforeNextWrite(action: () -> Unit) {
        hook = action
    }

    /** Runs [action] just before every conditional write. */
    fun beforeEveryWrite(action: () -> Unit) {
        hook = action
        hookEveryWrite = true
    }

    /** Stores [content] at [path] unconditionally, as another writer would. */
    fun overwrite(
        path: ClusterS3Path,
        content: String,
    ) {
        store(path, content.toByteArray(), "text/html")
    }

    /** Every stored key, without its bucket, in order. */
    fun keys(): List<String> = objects.keys.map { it.removePrefix("s3://").substringAfter('/') }

    /** Every stored object's `s3://bucket/key` URI, in order. */
    fun uris(): List<String> = objects.keys.toList()

    /** The bytes stored at [path], or null. */
    fun bytesAt(path: ClusterS3Path): ByteArray? = objects[path.toUri()]?.bytes

    /** The content type [path] was stored with, or null. */
    fun contentTypeAt(path: ClusterS3Path): String? = objects[path.toUri()]?.contentType

    override fun putIfAbsent(
        content: ByteArray,
        remotePath: ClusterS3Path,
        contentType: String,
    ): ConditionalObjectStore.PutIfAbsentResult {
        runHook()
        if (objects.containsKey(remotePath.toUri())) {
            refusedWrites++
            return ConditionalObjectStore.AlreadyExists
        }
        store(remotePath, content, contentType)
        return ConditionalObjectStore.Written
    }

    override fun putIfMatch(
        content: ByteArray,
        remotePath: ClusterS3Path,
        etag: String,
        contentType: String,
    ): ConditionalObjectStore.PutIfMatchResult {
        runHook()
        if (objects[remotePath.toUri()]?.etag != etag) {
            refusedWrites++
            return ConditionalObjectStore.PreconditionFailed
        }
        store(remotePath, content, contentType)
        return ConditionalObjectStore.Written
    }

    override fun readVersioned(remotePath: ClusterS3Path): ConditionalObjectStore.Versioned? =
        objects[remotePath.toUri()]?.let { ConditionalObjectStore.Versioned(it.bytes.decodeToString(), it.etag) }

    private fun store(
        path: ClusterS3Path,
        bytes: ByteArray,
        contentType: String,
    ) {
        objects[path.toUri()] = Stored(bytes, contentType, "\"v${++version}\"")
    }

    private fun runHook() {
        val action = hook ?: return
        if (!hookEveryWrite) hook = null
        action()
    }
}
