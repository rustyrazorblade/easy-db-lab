package com.rustyrazorblade.easydblab.services.documents

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import java.time.Duration

/** Whose object a key holds once a write of one writer's bytes was decided. */
enum class KeyHolder {
    /** The key holds exactly the writer's bytes: its own object. */
    OWN,

    /** The key holds other bytes: another writer's object. */
    OTHER,

    /** The key still held nothing after every look: a write in flight there neither landed nor aborted. */
    EMPTY,
}

/**
 * How a write was decided.
 *
 * @property holder Whose object the key holds.
 * @property looks How many times the key was looked at after the write was refused; 0 when it
 *   was written at once.
 * @property rewritten Whether a look found the key empty and this writer's write again landed
 *   there; false when the bytes read back decided, or nothing did.
 */
data class WriteDecision(
    val holder: KeyHolder,
    val looks: Int,
    val rewritten: Boolean = false,
)

/**
 * Writes an object only where none is, and tells whose object the key then holds.
 *
 * A refusal never says why it was refused: the key may hold another writer's object, this
 * writer's own (an earlier attempt committed and lost its response), or nothing yet (a write is
 * still in flight there, or one aborted). So on any refusal the key is read back, and its bytes
 * decide: exactly these bytes are this writer's own, any others are another writer's. That is
 * sound only for bytes no other writer writes to that key, which is what makes report objects
 * safe to decide this way: each zip carries its upload's random id, and each unpacked file sits
 * under the folder of the one upload whose zip holds that second.
 *
 * An empty key is looked at again on the same key, a bounded number of times, writing the same
 * bytes again while it stays empty; each look decides the same way, and a look whose write is
 * refused reads the key back in that look. Any S3 error fails at once.
 */
class ImmutableObjectWriter(
    private val store: ConditionalObjectStore,
    private val lookInterval: Duration,
) {
    /** Writes [content] to [path] only where no object is, and decides whose object [path] holds. */
    fun write(
        content: ByteArray,
        path: ClusterS3Path,
        contentType: String,
    ): WriteDecision {
        if (store.putIfAbsent(content, path, contentType) == ConditionalObjectStore.Written) {
            return WriteDecision(KeyHolder.OWN, 0)
        }
        var looks = 0
        val decided =
            pollUntil<Look?>(
                "report-write-refused",
                Constants.Observability.REPORT_IN_FLIGHT_MAX_LOOKS,
                lookInterval,
                transient = { false },
                done = { it != null },
            ) {
                looks++
                look(content, path, contentType)
            }
        return WriteDecision(decided?.holder ?: KeyHolder.EMPTY, looks, decided?.rewritten ?: false)
    }

    /** What one look decided: whose object the key holds, and whether this writer's write again put it there. */
    private data class Look(
        val holder: KeyHolder,
        val rewritten: Boolean,
    )

    /** One look at [path]: what it decided, or null while it is still empty after writing [content] again. */
    private fun look(
        content: ByteArray,
        path: ClusterS3Path,
        contentType: String,
    ): Look? =
        readBack(path, content)
            ?: if (store.putIfAbsent(content, path, contentType) == ConditionalObjectStore.Written) {
                Look(KeyHolder.OWN, rewritten = true)
            } else {
                readBack(path, content)
            }

    private fun readBack(
        path: ClusterS3Path,
        content: ByteArray,
    ): Look? = holderOf(store.readBytes(path), content)?.let { Look(it, rewritten = false) }

    private fun holderOf(
        stored: ByteArray?,
        content: ByteArray,
    ): KeyHolder? = stored?.let { if (it.contentEquals(content)) KeyHolder.OWN else KeyHolder.OTHER }
}
