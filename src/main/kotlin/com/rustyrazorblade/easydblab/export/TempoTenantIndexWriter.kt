package com.rustyrazorblade.easydblab.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.zip.GZIPOutputStream

/**
 * A Tempo tenant index, `<tenant>/index.json.gz`: each block's `meta.json` and the compacted blocks.
 *
 * @property createdAt when the index was written, RFC 3339.
 * @property meta each block's `meta.json`, as written.
 * @property compacted always empty: an export copies no compacted block.
 */
@Serializable
data class TempoTenantIndex(
    @SerialName("created_at")
    val createdAt: String,
    val meta: List<JsonObject>,
    val compacted: List<JsonObject>,
)

/**
 * Writes a Tempo tenant index from the tenant directory it indexes: `{created_at, meta, compacted}`
 * with every block's `meta.json` and no compacted block, gzipped JSON with no `index.pb.zst` beside it,
 * which a split Tempo reader reads (spike S5). The single binary the offline stack runs lists the
 * directory itself; the index is for any other reader.
 *
 * The index lists exactly the blocks in the directory and is checked against it before it is
 * returned, so write it once the directory is complete.
 *
 * @property clock the time the index records as its creation.
 */
class TempoTenantIndexWriter(
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** The index's name in the tenant directory. */
        const val FILE = "index.json.gz"

        private const val BLOCK_ID = "blockID"
        private const val WHAT = "Tempo tenant index"
    }

    /** The index of [directory], every block with a `meta.json`; fails on a block without one. */
    fun index(directory: BlockDirectory): TempoTenantIndex =
        TempoTenantIndex(
            createdAt = clock.instant().toString(),
            meta = indexableBlocks(WHAT, directory, emptySet()).map { Json.parseToJsonElement(checkNotNull(it.meta).decodeToString()) as JsonObject },
            compacted = emptyList(),
        )

    /** Fails unless [index] lists exactly the blocks of [directory]. */
    fun verify(
        index: TempoTenantIndex,
        directory: BlockDirectory,
    ) = requireExactBlocks(WHAT, index.meta.map { it.getValue(BLOCK_ID).jsonPrimitive.content }, directory.blocks().keys, directory)

    /** The gzipped index of [directory], checked against it. */
    fun write(directory: BlockDirectory): ByteArray {
        val index = index(directory)
        verify(index, directory)
        val bytes = Json.encodeToString(TempoTenantIndex.serializer(), index).toByteArray()
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    }
}
