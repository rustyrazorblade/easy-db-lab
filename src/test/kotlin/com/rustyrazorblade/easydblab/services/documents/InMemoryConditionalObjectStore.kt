package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore

/**
 * A [ConditionalObjectStore] in memory with S3's semantics: each write gets a new ETag, and a
 * conditional write whose condition fails changes nothing and is refused. A refusal never says
 * why, as with S3, so the store can refuse a write for each reason S3 can: another writer's object
 * at the key (stored with [overwrite], or by a hook that runs just before a write), a write still
 * in flight at the key ([writeInFlightAt]), or this writer's own write whose response was lost
 * ([loseNextWriteResponse]). It can also fail a read with an S3 error ([failReadAt]).
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
    private var inFlight: PendingWrite? = null
    private val reads = mutableMapOf<String, Int>()
    private val readFailures = mutableMapOf<String, Pair<Int, RuntimeException>>()

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
     * Makes the next conditional write whose condition holds store its content and then report a
     * refusal ([ConditionalObjectStore.AlreadyExists] or [ConditionalObjectStore.PreconditionFailed]),
     * as S3 does when a write commits, its response is lost, and the retried attempt of the same
     * request finds the object already there.
     */
    fun loseNextWriteResponse() {
        loseResponse = { true }
    }

    /** As [loseNextWriteResponse], for the next conditional write to [path] only. */
    fun loseWriteResponseAt(path: ClusterS3Path) {
        loseResponse = { it == path.toUri() }
    }

    /**
     * Puts a conditional write in flight at [path]: until it ends, every conditional write to
     * [path] is refused as S3 refuses a write that meets one in flight, and a read sees whatever
     * was there before (nothing, for a new key). It ends on the [endsOnRead]th read of [path],
     * counted from now, just before that read returns, as [end] says.
     */
    fun writeInFlightAt(
        path: ClusterS3Path,
        endsOnRead: Int,
        end: InFlightEnd,
    ) {
        inFlight = PendingWrite(path.toUri(), endsOnRead, end)
    }

    /** Makes the [readNumber]th read of [path] (counting every read of it) throw [error]. */
    fun failReadAt(
        path: ClusterS3Path,
        readNumber: Int,
        error: RuntimeException,
    ) {
        readFailures[path.toUri()] = readNumber to error
    }

    /** How many times [path] was read. */
    fun readsOf(path: ClusterS3Path): Int = reads[path.toUri()] ?: 0

    /** How a write put in flight with [writeInFlightAt] ends. */
    sealed interface InFlightEnd {
        /** It lands holding the content of the first write it refused: that writer's own earlier attempt. */
        data object LandsOwn : InFlightEnd

        /** It lands holding [bytes]: another writer's object. */
        class LandsOther(
            val bytes: ByteArray,
        ) : InFlightEnd

        /** It never lands; the key is left as it was. */
        data object Aborts : InFlightEnd
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
    ): ConditionalObjectStore.PutIfAbsentResult =
        if (conditionalWrite(remotePath, content, contentType) { objects.containsKey(remotePath.toUri()) }) {
            ConditionalObjectStore.Written
        } else {
            ConditionalObjectStore.AlreadyExists
        }

    override fun putIfMatch(
        content: ByteArray,
        remotePath: ClusterS3Path,
        etag: String,
        contentType: String,
    ): ConditionalObjectStore.PutIfMatchResult =
        if (conditionalWrite(remotePath, content, contentType) { objects[remotePath.toUri()]?.etag != etag }) {
            ConditionalObjectStore.Written
        } else {
            ConditionalObjectStore.PreconditionFailed
        }

    override fun readVersioned(remotePath: ClusterS3Path): ConditionalObjectStore.Versioned? {
        read(remotePath)
        return objects[remotePath.toUri()]?.let { ConditionalObjectStore.Versioned(it.bytes.decodeToString(), it.etag) }
    }

    override fun readBytes(remotePath: ClusterS3Path): ByteArray? {
        read(remotePath)
        return bytesAt(remotePath)
    }

    /**
     * Runs one conditional write: refused while a write is in flight at [path] or while [fails]
     * holds; otherwise [content] is stored and the write reports written unless its response is
     * lost.
     */
    private fun conditionalWrite(
        path: ClusterS3Path,
        content: ByteArray,
        contentType: String,
        fails: () -> Boolean,
    ): Boolean {
        runHook()
        val pending = inFlight?.takeIf { it.uri == path.toUri() }
        if (pending != null || fails()) {
            if (pending != null && pending.refused == null) pending.refused = Stored(content, contentType, "")
            refusedWrites++
            return false
        }
        store(path, content, contentType)
        return !responseLost(path)
    }

    /** A write put in flight by [writeInFlightAt]. */
    private class PendingWrite(
        val uri: String,
        val endsOnRead: Int,
        val end: InFlightEnd,
    ) {
        var reads = 0
        var refused: Stored? = null
    }

    /** Counts a read of [path], fails it when arranged, and ends the write in flight there on its read. */
    private fun read(path: ClusterS3Path) {
        val uri = path.toUri()
        val count = (reads[uri] ?: 0) + 1
        reads[uri] = count
        readFailures[uri]?.takeIf { it.first == count }?.let { throw it.second }
        val pending = inFlight?.takeIf { it.uri == uri } ?: return
        if (++pending.reads < pending.endsOnRead) return
        inFlight = null
        when (val end = pending.end) {
            InFlightEnd.LandsOwn -> {
                val own = checkNotNull(pending.refused) { "no write met the write in flight at $uri" }
                store(path, own.bytes, own.contentType)
            }
            is InFlightEnd.LandsOther -> store(path, end.bytes, "application/octet-stream")
            InFlightEnd.Aborts -> Unit
        }
    }

    private fun store(
        path: ClusterS3Path,
        bytes: ByteArray,
        contentType: String,
    ) {
        objects[path.toUri()] = Stored(bytes, contentType, "\"v${++version}\"")
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
