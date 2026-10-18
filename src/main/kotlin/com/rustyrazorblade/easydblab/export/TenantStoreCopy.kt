package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.util.Base64

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

    /**
     * The bytes the export needs on the disk of the zip and its work directory beside it: the zip's
     * data ([bytes]), and in the work directory the local copy of Loki's index and the split's output
     * beside it, each up to the planned index's size.
     */
    val diskBytes: Long
        get() = bytes + Constants.Export.LOKI_WORK_INDEX_COPIES * lokiIndex.sumOf { it.size }

    /** The number of objects the plan copies, from its listings. */
    val objects: Int
        get() =
            lokiIndex.size + tempoBlocks.values.sumOf { it.size } + mimirBlocks.values.sumOf { it.size } +
                immutable.values.sumOf { it.size }
}

/**
 * Copies one tenant's store into an export, consistently while the account compactor runs. Each
 * object goes to `data/<key>`. Docker runs for one step only: Loki's compactor splitting the shared
 * index files ([LokiIndexSplit]).
 *
 * The order (design D15):
 * 1. Loki's index, table by table ([LokiIndexCopy]). The compactor replaces a table's files with a
 *    merged one and removes the merged ones; a table whose listed file is gone when read is listed and
 *    copied again from scratch, so the zip never holds half of one listing. The shared multi-tenant
 *    files are split per tenant on the local copy, and only the tenant's own files go into the zip.
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
 * @param lokiSplit splits Loki's shared index files per tenant.
 */
class TenantStoreCopy(
    private val store: ExportStore,
    private val mimirIndex: MimirBucketIndexWriter,
    private val tempoIndex: TempoTenantIndexWriter,
    lokiSplit: LokiIndexSplit,
) {
    private val lokiIndex = LokiIndexCopy(store, lokiSplit)

    companion object {
        private val METRICS = Constants.Observability.METRICS_ROOT
        private val LOGS = Constants.Observability.LOGS_ROOT
        private val TRACES = Constants.Observability.TRACES_ROOT
        private val LOKI_INDEX = "$LOGS/index/"
        private const val META = "meta.json"
        private const val COMPACTED_META = "meta.compacted.json"

        /** Loki's index copy and its split, in the work directory. */
        private const val LOKI_WORK_DIR = "loki"

        /** The logs the Loki index split saved in the work directory [work], those that exist. */
        fun savedLogs(work: File): List<File> = LokiIndexCopy.savedLogs(File(work, LOKI_WORK_DIR))
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
            lokiIndex = tables.flatMap { table -> exportStep(ExportSignal.LOGS, "list $table") { lokiIndex.files(table, tenant) } },
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
     * Copies [plan] into [zip], in the order the class describes. [work] is the export's kept work
     * directory, where the local copy of Loki's index is made and split.
     */
    fun copy(
        plan: TenantCopyPlan,
        zip: ExportRecorder,
        work: File,
    ) {
        lokiIndex.copy(plan.lokiTables, plan.tenant, zip, File(work, LOKI_WORK_DIR))

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
        val live =
            exportStep(ExportSignal.METRICS, "read ${MimirStoreIndex.key(tenant)}") { MimirStoreIndex.read(store, tenant) }
                .live
                .map { it.id }
                .toSet()
        return byBlock(listing, "$METRICS/$tenant/")
            .filterKeys { it in live }
            .mapValues { (_, objects) -> objects.filter { it.key.substringAfterLast('/') !in MimirStoreIndex.MARKER_FILES } }
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
