package com.rustyrazorblade.easydblab.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.zip.GZIPOutputStream

/**
 * A Mimir bucket index, version 2, as Mimir 3.2.1's bucket index updater writes it to
 * `<prefix>/<tenant>/bucket-index.json.gz`.
 *
 * @property version always 2.
 * @property blocks one entry per block.
 * @property blockDeletionMarks always empty: an export copies no block marked for deletion.
 * @property updatedAt when the index was written, in Unix seconds.
 */
@Serializable
data class MimirBucketIndex(
    val version: Int,
    val blocks: List<MimirIndexedBlock>,
    @SerialName("block_deletion_marks")
    val blockDeletionMarks: List<JsonObject>,
    @SerialName("updated_at")
    val updatedAt: Long,
)

/**
 * One block of a [MimirBucketIndex]. A null field is left out, as Mimir's `omitempty` leaves out an
 * empty one.
 */
@Serializable
data class MimirIndexedBlock(
    @SerialName("block_id")
    val blockId: String,
    @SerialName("min_time")
    val minTime: Long,
    @SerialName("max_time")
    val maxTime: Long,
    @SerialName("segments_format")
    val segmentsFormat: String? = null,
    @SerialName("segments_num")
    val segmentsNum: Int? = null,
    @SerialName("uploaded_at")
    val uploadedAt: Long,
    @SerialName("compactor_shard_id")
    val compactorShardId: String? = null,
    val source: String? = null,
    @SerialName("compaction_level")
    val compactionLevel: Int? = null,
    @SerialName("out_of_order")
    val outOfOrder: Boolean? = null,
    val labels: Map<String, String>? = null,
)

/**
 * Writes a Mimir tenant's bucket index from the tenant directory it indexes, the same way Mimir's
 * own `bucketindex.BlockFromThanosMeta` does, field for field apart from the timestamps (spike S4).
 * No Mimir tool writes an index offline, and running a Mimir compactor over the directory would also
 * merge and mark its blocks.
 *
 * The index lists exactly the blocks in the directory and is checked against it before it is
 * returned: Mimir answers every query with a silent gap for a block the index leaves out, and fails
 * every query for one it lists that is not there. Write it last, once nothing more is added to or
 * removed from the directory. A reader needs no `markers/` and no deletion marks.
 *
 * @property clock the time the index records as its update.
 */
class MimirBucketIndexWriter(
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** The index's name in the tenant directory. */
        const val FILE = "bucket-index.json.gz"

        /** Mimir's per-tenant marker directory, the one subdirectory that is not a block. */
        private const val MARKERS_DIR = "markers"

        private const val VERSION = 2

        /** Mimir's name for chunk segment files named `000001` up to `00000N`. */
        private const val SEGMENTS_1_BASED_6_DIGITS = "1b6d"
        private const val CHUNKS_PREFIX = "chunks/"
        private const val SHARD_LABEL = "__compactor_shard_id__"
        private const val OUT_OF_ORDER_HINT = "from-out-of-order"
        private const val WHAT = "Mimir bucket index"

        private val JSON =
            Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            }
    }

    /** The index of [directory], every block with a `meta.json`; fails on a block without one. */
    fun index(directory: BlockDirectory): MimirBucketIndex =
        MimirBucketIndex(
            version = VERSION,
            blocks = indexableBlocks(WHAT, directory, setOf(MARKERS_DIR)).map(::entry),
            blockDeletionMarks = emptyList(),
            updatedAt = clock.instant().epochSecond,
        )

    /** Fails unless [index] lists exactly the blocks of [directory]. */
    fun verify(
        index: MimirBucketIndex,
        directory: BlockDirectory,
    ) = requireExactBlocks(WHAT, index.blocks.map { it.blockId }, directory.blocks().keys - MARKERS_DIR, directory)

    /** The gzipped index of [directory], checked against it. */
    fun write(directory: BlockDirectory): ByteArray {
        val index = index(directory)
        verify(index, directory)
        return gzip(JSON.encodeToString(MimirBucketIndex.serializer(), index).toByteArray())
    }

    private fun entry(block: StagedBlock): MimirIndexedBlock {
        val meta = Json.parseToJsonElement(checkNotNull(block.meta).decodeToString()).jsonObject
        val thanos = meta["thanos"] as? JsonObject ?: JsonObject(emptyMap())
        val compaction = meta["compaction"] as? JsonObject ?: JsonObject(emptyMap())
        val labels = (thanos["labels"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        val segments = segmentCount(thanos)
        val hints = (compaction["hints"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val outOfOrder = (meta["out_of_order"] as? JsonPrimitive)?.booleanOrNull == true || OUT_OF_ORDER_HINT in hints
        return MimirIndexedBlock(
            blockId = meta.getValue("ulid").jsonPrimitive.content,
            minTime = meta.getValue("minTime").jsonPrimitive.long,
            maxTime = meta.getValue("maxTime").jsonPrimitive.long,
            segmentsFormat = segments?.let { SEGMENTS_1_BASED_6_DIGITS },
            segmentsNum = segments,
            uploadedAt = block.metaWrittenAt.epochSecond,
            compactorShardId = labels[SHARD_LABEL],
            source = (thanos["source"] as? JsonPrimitive)?.content?.ifEmpty { null },
            compactionLevel = (compaction["level"] as? JsonPrimitive)?.intOrNull?.takeIf { it != 0 },
            outOfOrder = outOfOrder.takeIf { it },
            labels = labels.ifEmpty { null },
        )
    }

    /**
     * How many chunk segment files the block has, when they are `000001` up to `00000N` with none
     * missing; null otherwise. From `thanos.segment_files`, or the `chunks/` entries of `thanos.files`.
     */
    private fun segmentCount(thanos: JsonObject): Int? {
        val named = (thanos["segment_files"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val segments =
            named.ifEmpty {
                (thanos["files"] as? JsonArray)
                    .orEmpty()
                    .map {
                        it.jsonObject
                            .getValue("rel_path")
                            .jsonPrimitive.content
                    }.filter { it.startsWith(CHUNKS_PREFIX) }
                    .map { it.removePrefix(CHUNKS_PREFIX) }
            }
        if (segments.isEmpty()) return null
        return segments.size.takeIf { segments.sorted() == (1..segments.size).map { "%06d".format(it) } }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                GZIPOutputStream(out).use {
                    it.write(bytes)
                }
            }.toByteArray()
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
