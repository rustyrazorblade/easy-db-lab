package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Where Loki's filesystem store keeps a chunk. The offline Loki reads the export as a filesystem store,
 * which names a schema v12+ chunk `<tenant>/<fingerprint>/<base64 of the last key part>` (standard
 * base64, padded), where S3 keeps the last part as it is (`from:through:checksum`). Loki's file path
 * drops the empty parts a `/` in the encoding leaves, and so does this. The index is not renamed: its
 * files keep their keys on a filesystem store too.
 */
internal object LokiFilesystemLayout {
    /** The filesystem key of the chunk stored in S3 at [key]. */
    fun chunkPath(key: String): String {
        val encoded = Base64.getEncoder().encodeToString(key.substringAfterLast('/').toByteArray())
        return "${key.substringBeforeLast('/')}/$encoded".split('/').filter { it.isNotEmpty() }.joinToString("/")
    }
}

/** A tenant has no metrics, logs or traces under any prefix the export looked under. */
class EmptyTenantException(
    val tenant: String,
    val prefixes: List<String>,
) : IllegalStateException("Tenant $tenant has no data in the store: nothing under ${prefixes.joinToString(", ")}")

/**
 * What one tenant export will copy, from the listings made before the copy starts.
 *
 * @property tenant the tenant.
 * @property lokiTables Loki's index tables, each `loki/index/<table>/`; each is listed again when copied.
 * @property lokiIndex the tenant's index objects in each table when the plan was made, for the size.
 * @property tempoBlocks the objects of each Tempo block with a `meta.json` and no compacted marker.
 * @property mimirBlocks the objects of each Mimir block the bucket index lists with no deletion mark.
 * @property immutable the objects no compactor rewrites: Loki's chunks, the annotation backups and the reports.
 */
data class TenantCopyPlan(
    val tenant: String,
    val lokiTables: List<String>,
    val lokiIndex: List<StoreObject>,
    val tempoBlocks: Map<String, List<StoreObject>>,
    val mimirBlocks: Map<String, List<StoreObject>>,
    val immutable: Map<ExportSignal, List<StoreObject>>,
) {
    /** The bytes the plan copies, from its listings. */
    val bytes: Long
        get() =
            (lokiIndex + tempoBlocks.values.flatten() + mimirBlocks.values.flatten() + immutable.values.flatten())
                .sumOf { it.size }

    /** The number of objects the plan copies, from its listings. */
    val objects: Int
        get() =
            lokiIndex.size + tempoBlocks.values.sumOf { it.size } + mimirBlocks.values.sumOf { it.size } +
                immutable.values.sumOf { it.size }
}

/**
 * Copies one tenant's store into an export, consistently while the account compactor runs, with no
 * docker. Each object goes to `data/<key>`.
 *
 * The order (design D15):
 * 1. Loki's index, table by table. The compactor replaces a table's files with a merged one and
 *    removes the merged ones; a table whose listed file is gone when read is listed and copied again
 *    from scratch, so the zip never holds half of one listing.
 * 2. Tempo, from one listing of `tempo/<tenant>/`: every block with a `meta.json` and no
 *    `meta.compacted.json` (a merged source stays for 48 hours after its marker), then the zip's own
 *    tenant index of those blocks ([TempoTenantIndexWriter]).
 * 3. Mimir, from one read of the tenant's `bucket-index.json.gz`: every block it lists with no deletion
 *    mark (a marked block is deleted 12 hours later, so these stay readable), then the zip's own bucket
 *    index ([MimirBucketIndexWriter]), last, from the blocks in the zip.
 * 4. The immutable objects: Loki's chunks, the annotation backups and the reports.
 *
 * It only lists and reads ([ExportStore]); nothing is written to S3.
 *
 * @property store the account bucket.
 * @property mimirIndex writes the zip's Mimir bucket index.
 * @property tempoIndex writes the zip's Tempo tenant index.
 */
class TenantStoreCopy(
    private val store: ExportStore,
    private val mimirIndex: MimirBucketIndexWriter,
    private val tempoIndex: TempoTenantIndexWriter,
) {
    private companion object {
        val METRICS = Constants.Observability.METRICS_ROOT
        val LOGS = Constants.Observability.LOGS_ROOT
        val TRACES = Constants.Observability.TRACES_ROOT
        val LOKI_INDEX = "$LOGS/index/"
        const val META = "meta.json"
        const val COMPACTED_META = "meta.compacted.json"

        /** Marker files in a Mimir block directory; a reader needs none. */
        val MIMIR_MARKS = setOf("deletion-mark.json", "no-compact-mark.json")
        val JSON = Json { ignoreUnknownKeys = true }
    }

    /** Lists everything the export of [tenant] copies; fails when the tenant has no data. */
    fun plan(tenant: String): TenantCopyPlan {
        val mimirListing = exportStep(ExportSignal.METRICS, "list $METRICS/$tenant/") { store.list("$METRICS/$tenant/") }
        val tempoListing = exportStep(ExportSignal.TRACES, "list $TRACES/$tenant/") { store.list("$TRACES/$tenant/") }
        val chunks = exportStep(ExportSignal.LOGS, "list $LOGS/$tenant/") { store.list("$LOGS/$tenant/") }
        if (mimirListing.isEmpty() && tempoListing.isEmpty() && chunks.isEmpty()) {
            throw EmptyTenantException(tenant, listOf("$METRICS/$tenant/", "$LOGS/$tenant/", LOKI_INDEX, "$TRACES/$tenant/"))
        }
        val tables = exportStep(ExportSignal.LOGS, "list $LOKI_INDEX") { store.listDirectories(LOKI_INDEX) }
        val annotations = "${Constants.Observability.GRAFANA_ROOT}/${Constants.Observability.ANNOTATIONS_DIR}/$tenant/"
        val reports = "${Constants.Observability.REPORTS_ROOT}/$tenant/"
        return TenantCopyPlan(
            tenant = tenant,
            lokiTables = tables,
            lokiIndex = tables.flatMap { table -> exportStep(ExportSignal.LOGS, "list $table") { tenantIndexFiles(table, tenant) } },
            tempoBlocks = tempoBlocks(tempoListing, tenant),
            mimirBlocks = if (mimirListing.isEmpty()) emptyMap() else mimirBlocks(mimirListing, tenant),
            immutable =
                mapOf(
                    ExportSignal.LOGS to chunks,
                    ExportSignal.ANNOTATIONS to exportStep(ExportSignal.ANNOTATIONS, "list $annotations") { store.list(annotations) },
                    ExportSignal.REPORTS to exportStep(ExportSignal.REPORTS, "list $reports") { store.list(reports) },
                ),
        )
    }

    /**
     * Copies [plan] into [zip], in the order the class describes. [scratch] is a directory of the
     * tool's own where a Loki index table is held until all of it has been read.
     */
    fun copy(
        plan: TenantCopyPlan,
        zip: ExportRecorder,
        scratch: File,
    ) {
        plan.lokiTables.forEach { table -> copyLokiTable(table, plan.tenant, zip, scratch) }

        plan.tempoBlocks.forEach { (id, objects) ->
            exportStep(ExportSignal.TRACES, "copy block $id of ${plan.tenant}") {
                objects.forEach { copyObject(it, zip) }
            }
        }
        if (plan.tempoBlocks.isNotEmpty()) {
            val tenantDir = "${Constants.Export.DATA_DIR}/$TRACES/${plan.tenant}"
            exportStep(ExportSignal.TRACES, "write the tenant index of ${plan.tenant}") {
                zip.put("$tenantDir/${TempoTenantIndexWriter.FILE}", tempoIndex.write(zip.directory(tenantDir)))
            }
        }

        plan.mimirBlocks.forEach { (id, objects) ->
            exportStep(ExportSignal.METRICS, "copy block $id of ${plan.tenant}") {
                objects.forEach { copyObject(it, zip) }
            }
        }
        if (plan.mimirBlocks.isNotEmpty()) {
            val tenantDir = "${Constants.Export.DATA_DIR}/$METRICS/${plan.tenant}"
            exportStep(ExportSignal.METRICS, "write the bucket index of ${plan.tenant}") {
                zip.put("$tenantDir/${MimirBucketIndexWriter.FILE}", mimirIndex.write(zip.directory(tenantDir)))
            }
        }

        plan.immutable.forEach { (signal, objects) ->
            val path: (String) -> String = if (signal == ExportSignal.LOGS) LokiFilesystemLayout::chunkPath else { key -> key }
            objects.forEach { exportStep(signal, "copy ${it.key}") { copyObject(it, zip, path(it.key)) } }
        }
    }

    /** The tenant's files in one index table: the multi-tenant files at its root and the tenant's own directory. */
    private fun tenantIndexFiles(
        table: String,
        tenant: String,
    ): List<StoreObject> =
        store.list(table).filter { obj ->
            val rest = obj.key.removePrefix(table)
            !rest.contains('/') || rest.startsWith("$tenant/")
        }

    /**
     * Copies one Loki index table: every file of the listing is read into [scratch] first and put into
     * the zip only once all of them were read. A file that is gone means the compactor replaced the
     * table meanwhile, so the table is listed and read again.
     */
    private fun copyLokiTable(
        table: String,
        tenant: String,
        zip: ExportRecorder,
        scratch: File,
    ) = exportStep(ExportSignal.LOGS, "copy index table $table") {
        val staging = Files.createTempDirectory(scratch.toPath(), "loki-table-").toFile()
        try {
            val read =
                (1..Constants.Export.LOKI_TABLE_ATTEMPTS).firstNotNullOfOrNull { _ ->
                    staging.listFiles()?.forEach { it.deleteRecursively() }
                    readTable(tenantIndexFiles(table, tenant), staging)
                } ?: error("$table was replaced while it was read, ${Constants.Export.LOKI_TABLE_ATTEMPTS} times")
            read.forEach { (obj, file) -> zip.put("${Constants.Export.DATA_DIR}/${obj.key}", file.inputStream(), obj.lastModified) }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Every object of [listing] in [staging], or null when one of them is gone. */
    private fun readTable(
        listing: List<StoreObject>,
        staging: File,
    ): List<Pair<StoreObject, File>>? =
        listing.withIndex().map { (n, obj) ->
            val file = File(staging, n.toString())
            val input = store.open(obj.key) ?: return null
            input.use { source -> file.outputStream().use { source.copyTo(it, Constants.Export.COPY_BUFFER_BYTES) } }
            obj to file
        }

    /** Copies [obj] to `data/<path>`, its key unless the offline backend names the object otherwise. */
    private fun copyObject(
        obj: StoreObject,
        zip: ExportRecorder,
        path: String = obj.key,
    ) {
        val input = store.open(obj.key) ?: throw ObjectGoneException(obj.key)
        zip.put("${Constants.Export.DATA_DIR}/$path", input, obj.lastModified)
    }

    /** The objects of every block in [listing] with a `meta.json` and no compacted marker, by block. */
    private fun tempoBlocks(
        listing: List<StoreObject>,
        tenant: String,
    ): Map<String, List<StoreObject>> =
        byBlock(listing, "$TRACES/$tenant/").filterValues { objects ->
            val names = objects.map { it.key.substringAfterLast('/') }
            META in names && COMPACTED_META !in names
        }

    /** The objects of every block the bucket index lists with no deletion mark, by block, from one read of the index. */
    private fun mimirBlocks(
        listing: List<StoreObject>,
        tenant: String,
    ): Map<String, List<StoreObject>> {
        val indexKey = "$METRICS/$tenant/${MimirBucketIndexWriter.FILE}"
        val index =
            exportStep(ExportSignal.METRICS, "read $indexKey") {
                val input =
                    store.open(indexKey)
                        ?: error(
                            "$indexKey is missing: the account compactor writes it every minute; start it with 'observability compactor start'",
                        )
                JSON.parseToJsonElement(GZIPInputStream(input).use { it.readBytes() }.decodeToString()).jsonObject
            }

        fun ids(field: String) =
            index[field]
                ?.jsonArray
                .orEmpty()
                .map { (it as JsonObject).getValue("block_id").jsonPrimitive.content }
                .toSet()
        val live = ids("blocks") - ids("block_deletion_marks")
        return byBlock(listing, "$METRICS/$tenant/")
            .filterKeys { it in live }
            .mapValues { (_, objects) -> objects.filter { it.key.substringAfterLast('/') !in MIMIR_MARKS } }
    }

    /** The objects of [listing] under a block directory of [tenantPrefix], by block; files at the tenant's root are left out. */
    private fun byBlock(
        listing: List<StoreObject>,
        tenantPrefix: String,
    ): Map<String, List<StoreObject>> =
        listing
            .filter { it.key.removePrefix(tenantPrefix).contains('/') }
            .groupBy { it.key.removePrefix(tenantPrefix).substringBefore('/') }
            .toSortedMap()
}
