package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.export.ExportSignal
import com.rustyrazorblade.easydblab.export.ExportStore
import com.rustyrazorblade.easydblab.export.FileBlockDirectory
import com.rustyrazorblade.easydblab.export.MimirBucketIndexWriter
import com.rustyrazorblade.easydblab.export.MimirStoreIndex
import com.rustyrazorblade.easydblab.export.ObjectGoneException
import com.rustyrazorblade.easydblab.export.exportStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.Instant

/** Rewrites staged Mimir blocks to hold only some clusters' series. */
fun interface BlockRewrite {
    /**
     * Rewrites [blocks] of the filesystem object store rooted at [tenantDir] into new blocks beside
     * them that hold only the series of [clusters], with [tmpDir] (outside [tenantDir]) as scratch.
     * The source blocks are left where they are.
     */
    fun rewrite(
        tenantDir: File,
        tmpDir: File,
        blocks: List<String>,
        clusters: List<String>,
    )
}

/**
 * A query export's metrics for one tenant (design section 7, spike S4). Mimir has no query that
 * exports blocks, and no Mimir writer runs here (its shipper needs an xattr a macOS bind mount does
 * not have), so the tenant's own blocks are copied and rewritten:
 *
 * 1. from one read of the tenant's bucket index, every block with no deletion mark that overlaps one
 *    of the clusters' ranges is staged at `<staging>/mimir/<tenant>/<ulid>/`, marker files left out;
 * 2. [rewrite] writes new blocks that keep only the selected clusters' series;
 * 3. the source blocks and the scratch are removed, then every rewritten block with no series;
 * 4. the bucket index is written last, from exactly the blocks left ([MimirBucketIndexWriter]).
 *
 * It only reads the store.
 *
 * @property store the account bucket.
 * @property rewrite the block rewrite (thanos).
 * @property indexWriter writes the bucket index.
 */
class MetricsExport(
    private val store: ExportStore,
    private val rewrite: BlockRewrite,
    private val indexWriter: MimirBucketIndexWriter,
) {
    private companion object {
        val METRICS = Constants.Observability.METRICS_ROOT
        const val META = "meta.json"
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** The start of [tenant]'s oldest block, or null when its index lists none. */
    fun oldestBlock(tenant: String): Instant? =
        exportStep(ExportSignal.METRICS, "read the bucket index of $tenant") { MimirStoreIndex.read(store, tenant) }
            .live
            .minOfOrNull { it.minTime }
            ?.let(Instant::ofEpochMilli)

    /** Stages, rewrites and indexes [tenant]'s blocks of [clusters] in [ranges] under [staging]; returns the blocks kept and their bytes. */
    fun export(
        tenant: String,
        clusters: List<String>,
        ranges: List<TimeRange>,
        staging: File,
    ): SignalCount {
        val index = exportStep(ExportSignal.METRICS, "read the bucket index of $tenant") { MimirStoreIndex.read(store, tenant) }
        val chosen =
            index.live
                .filter { block ->
                    val span = TimeRange(Instant.ofEpochMilli(block.minTime), Instant.ofEpochMilli(maxOf(block.minTime, block.maxTime)))
                    ranges.any { it.overlaps(span) }
                }.map { it.id }
                .sorted()
        if (chosen.isEmpty()) return SignalCount.ZERO
        val tenantDir = File(staging, "$METRICS/$tenant")
        chosen.forEach { id -> exportStep(ExportSignal.METRICS, "stage block $id of $tenant") { stage(tenant, id, tenantDir) } }
        val tmpDir = File(staging, ".thanos-tmp-$tenant")
        exportStep(ExportSignal.METRICS, "rewrite the blocks of $tenant to keep only ${clusters.joinToString(", ")}") {
            rewrite.rewrite(tenantDir, tmpDir, chosen, clusters)
        }
        chosen.forEach { File(tenantDir, it).deleteRecursively() }
        tmpDir.deleteRecursively()
        tenantDir.listFiles { file -> file.isDirectory }.orEmpty().filter { series(it) == 0L }.forEach { it.deleteRecursively() }
        val kept = tenantDir.listFiles { file -> file.isDirectory }.orEmpty().size
        exportStep(ExportSignal.METRICS, "write the bucket index of $tenant") {
            File(tenantDir, MimirBucketIndexWriter.FILE).writeBytes(indexWriter.write(FileBlockDirectory(tenantDir)))
        }
        return SignalCount(kept.toLong(), tenantDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    /** Copies block [id] of [tenant] from the store into [tenantDir], without its marker files. */
    private fun stage(
        tenant: String,
        id: String,
        tenantDir: File,
    ) {
        val prefix = "$METRICS/$tenant/$id/"
        store
            .list(prefix)
            .filter { it.key.substringAfterLast('/') !in MimirStoreIndex.MARKER_FILES }
            .forEach { obj ->
                val target = File(tenantDir, "$id/${obj.key.removePrefix(prefix)}").apply { parentFile.mkdirs() }
                val input = store.open(obj.key) ?: throw ObjectGoneException(obj.key)
                input.use { source -> target.outputStream().use { source.copyTo(it, Constants.Export.COPY_BUFFER_BYTES) } }
            }
    }

    /** The series [block] holds, by its `meta.json`; 0 when the stats name none. */
    private fun series(block: File): Long {
        val meta = File(block, META).takeIf { it.isFile } ?: return 0
        val stats = JSON.parseToJsonElement(meta.readText()).jsonObject["stats"]?.jsonObject
        return stats?.get("numSeries")?.jsonPrimitive?.long ?: 0
    }
}
