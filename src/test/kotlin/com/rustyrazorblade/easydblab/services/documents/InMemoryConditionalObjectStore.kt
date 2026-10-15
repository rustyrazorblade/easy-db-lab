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
    private var loseResponse: ((String) -> Boolean)? = null
    private var refusal: Pair<String, () -> Unit>? = null
    private var conflict: InFlight? = null

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

    /**
     * Makes the next conditional write whose condition holds store its content and then report
     * [ConditionalObjectStore.RefusedAfterRetry], as S3 does when a write commits, its response is
     * lost, and the retried attempt of the same request finds the object already there. A write
     * refused on its first attempt reports [ConditionalObjectStore.AlreadyExists] or
     * [ConditionalObjectStore.PreconditionFailed].
     */
    fun loseNextWriteResponse() {
        loseResponse = { true }
    }

    /** As [loseNextWriteResponse], for the next conditional write to [path] only. */
    fun loseWriteResponseAt(path: ClusterS3Path) {
        loseResponse = { it == path.toUri() }
    }

    /**
     * Makes the next conditional write to [path] run [otherWriter] and then report
     * [ConditionalObjectStore.RefusedAfterRetry] without storing anything, as S3 does when an
     * attempt fails with no commit, another writer's object lands between the attempts, and the
     * retried attempt is refused because of it.
     */
    fun refuseAfterRetryAt(
        path: ClusterS3Path,
        otherWriter: () -> Unit,
    ) {
        refusal = path.toUri() to otherWriter
    }

    /**
     * Makes the next conditional write to [path] report [ConditionalObjectStore.ConflictAfterRetry]
     * without storing anything, as S3 does when a retried attempt meets a conditional write to the
     * key that is still in flight. That write lands only once [path] has been read [landsAfterReads]
     * times, holding [inFlight] applied to the refused write's content: its own content for this
     * writer's earlier attempt, other bytes for another writer's. Until it lands, every conditional
     * write to [path] is refused as S3 refuses a write that meets one in flight.
     */
    fun conflictAfterRetryAt(
        path: ClusterS3Path,
        landsAfterReads: Int,
        inFlight: (ByteArray) -> ByteArray = { it },
    ) {
        conflict = InFlight(path.toUri(), landsAfterReads, inFlight)
    }

    /** Stores [content] at [path] unconditionally, as another writer would. */
    fun overwrite(
        path: ClusterS3Path,
        content: String,
    ) {
        overwrite(path, content.toByteArray())
    }

    /** Stores [content] at [path] unconditionally, as another writer would. */
    fun overwrite(
        path: ClusterS3Path,
        content: ByteArray,
    ) {
        store(path, content, "text/html")
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
        if (refusedAfterRetry(remotePath)) return ConditionalObjectStore.RefusedAfterRetry
        when (conflicting(remotePath, content, contentType)) {
            InFlightConflict.FIRST -> return ConditionalObjectStore.ConflictAfterRetry
            InFlightConflict.PENDING -> return ConditionalObjectStore.AlreadyExists
            null -> Unit
        }
        if (objects.containsKey(remotePath.toUri())) {
            refusedWrites++
            return ConditionalObjectStore.AlreadyExists
        }
        store(remotePath, content, contentType)
        return if (responseLost(remotePath)) ConditionalObjectStore.RefusedAfterRetry else ConditionalObjectStore.Written
    }

    override fun putIfMatch(
        content: ByteArray,
        remotePath: ClusterS3Path,
        etag: String,
        contentType: String,
    ): ConditionalObjectStore.PutIfMatchResult {
        runHook()
        if (refusedAfterRetry(remotePath)) return ConditionalObjectStore.RefusedAfterRetry
        when (conflicting(remotePath, content, contentType)) {
            InFlightConflict.FIRST -> return ConditionalObjectStore.ConflictAfterRetry
            InFlightConflict.PENDING -> return ConditionalObjectStore.PreconditionFailed
            null -> Unit
        }
        if (objects[remotePath.toUri()]?.etag != etag) {
            refusedWrites++
            return ConditionalObjectStore.PreconditionFailed
        }
        store(remotePath, content, contentType)
        return if (responseLost(remotePath)) ConditionalObjectStore.RefusedAfterRetry else ConditionalObjectStore.Written
    }

    override fun readVersioned(remotePath: ClusterS3Path): ConditionalObjectStore.Versioned? {
        landAfterRead(remotePath)
        return objects[remotePath.toUri()]?.let { ConditionalObjectStore.Versioned(it.bytes.decodeToString(), it.etag) }
    }

    override fun readBytes(remotePath: ClusterS3Path): ByteArray? {
        landAfterRead(remotePath)
        return bytesAt(remotePath)
    }

    /** A conditional write to one key that is in flight, landing after a number of reads. */
    private class InFlight(
        val uri: String,
        var readsLeft: Int,
        val content: (ByteArray) -> ByteArray,
    ) {
        var bytes: ByteArray? = null
        var contentType = ""
    }

    /** How a conditional write meets the write in flight at its key. */
    private enum class InFlightConflict {
        /** The write the conflict was arranged for: refused after a retry; its content goes in flight. */
        FIRST,

        /** A later write while it is still in flight: refused as on a first attempt. */
        PENDING,
    }

    private fun conflicting(
        path: ClusterS3Path,
        content: ByteArray,
        contentType: String,
    ): InFlightConflict? {
        val inFlight = conflict?.takeIf { it.uri == path.toUri() } ?: return null
        refusedWrites++
        if (inFlight.bytes != null) return InFlightConflict.PENDING
        inFlight.bytes = inFlight.content(content)
        inFlight.contentType = contentType
        return InFlightConflict.FIRST
    }

    private fun landAfterRead(path: ClusterS3Path) {
        val inFlight = conflict?.takeIf { it.uri == path.toUri() } ?: return
        val bytes = inFlight.bytes ?: return
        inFlight.readsLeft--
        if (inFlight.readsLeft > 0) return
        conflict = null
        store(path, bytes, inFlight.contentType)
    }

    private fun store(
        path: ClusterS3Path,
        bytes: ByteArray,
        contentType: String,
    ) {
        objects[path.toUri()] = Stored(bytes, contentType, "\"v${++version}\"")
    }

    private fun refusedAfterRetry(path: ClusterS3Path): Boolean {
        val (uri, otherWriter) = refusal ?: return false
        if (uri != path.toUri()) return false
        refusal = null
        otherWriter()
        refusedWrites++
        return true
    }

    private fun responseLost(path: ClusterS3Path): Boolean {
        val lost = loseResponse?.invoke(path.toUri()) == true
        if (lost) loseResponse = null
        return lost
    }

    private fun runHook() {
        val action = hook ?: return
        if (!hookEveryWrite) hook = null
        action()
    }
}
