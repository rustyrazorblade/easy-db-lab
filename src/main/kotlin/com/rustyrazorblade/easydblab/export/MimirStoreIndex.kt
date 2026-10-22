package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.zip.GZIPInputStream

/**
 * One block a tenant's bucket index lists.
 *
 * @property id the block's ULID.
 * @property minTime its first sample time, Unix milliseconds.
 * @property maxTime its last sample time (exclusive), Unix milliseconds.
 */
data class IndexedBlockRange(
    val id: String,
    val minTime: Long,
    val maxTime: Long,
)

/**
 * A tenant's Mimir bucket index in the store, read once: the account compactor rewrites it every
 * minute, and an export takes the blocks it lists with no deletion mark (a marked block is deleted
 * 12 hours later, so those it takes stay readable while they are copied).
 *
 * @property blocks every block it lists.
 * @property deletionMarked the blocks marked for deletion.
 */
data class MimirStoreIndex(
    val blocks: List<IndexedBlockRange>,
    val deletionMarked: Set<String>,
) {
    /** The blocks with no deletion mark: what an export copies. */
    val live: List<IndexedBlockRange> get() = blocks.filter { it.id !in deletionMarked }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Marker files in a Mimir block directory; a reader needs none, so an export copies none. */
        val MARKER_FILES = setOf("deletion-mark.json", "no-compact-mark.json")

        /** The key of [tenant]'s bucket index. */
        fun key(tenant: String): String = "${Constants.Observability.METRICS_ROOT}/$tenant/${MimirBucketIndexWriter.FILE}"

        /** [tenant]'s bucket index in [store]; fails when it is missing. */
        fun read(
            store: ExportStore,
            tenant: String,
        ): MimirStoreIndex {
            val key = key(tenant)
            val input =
                store.open(key)
                    ?: error("$key is missing: the account compactor writes it every minute; start it with 'observability compactor start'")
            val index = JSON.parseToJsonElement(GZIPInputStream(input).use { it.readBytes() }.decodeToString()).jsonObject

            fun entries(field: String) = index[field]?.jsonArray.orEmpty().map { it as JsonObject }
            return MimirStoreIndex(
                blocks =
                    entries("blocks").map {
                        IndexedBlockRange(
                            id = it.getValue("block_id").jsonPrimitive.content,
                            minTime = it["min_time"]?.jsonPrimitive?.long ?: 0,
                            maxTime = it["max_time"]?.jsonPrimitive?.long ?: 0,
                        )
                    },
                deletionMarked = entries("block_deletion_marks").map { it.getValue("block_id").jsonPrimitive.content }.toSet(),
            )
        }
    }
}
