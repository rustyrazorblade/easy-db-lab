package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.export.ExportRecorder
import com.rustyrazorblade.easydblab.export.ExportSignal
import com.rustyrazorblade.easydblab.export.ExportStackFiles
import com.rustyrazorblade.easydblab.export.ExportStore
import com.rustyrazorblade.easydblab.export.ObjectGoneException
import com.rustyrazorblade.easydblab.export.TempoTenantIndexWriter
import com.rustyrazorblade.easydblab.export.exportStep
import java.io.File
import java.time.Instant

/**
 * What a query export's zip holds, under `data/` as the offline stack reads it:
 *
 * - the stack, with every tenant of the plan and, for an experiment export, its experiment;
 * - the writer's Loki store (`loki/`), as the writer wrote it: the offline Loki reads the same
 *   filesystem layout;
 * - the writer's Tempo blocks of each tenant, and the zip's own tenant index of them in place of the
 *   writer's;
 * - each tenant's staged, rewritten metric blocks and their bucket index;
 * - the annotation backups of the selected clusters, and every report of each covered experiment,
 *   from the store.
 *
 * @property store the account bucket, read only.
 * @property stack the stack files.
 * @property tempoIndex writes the zip's Tempo tenant indexes.
 */
class QueryZipContents(
    private val store: ExportStore,
    private val stack: ExportStackFiles,
    private val tempoIndex: TempoTenantIndexWriter,
) {
    private companion object {
        val DATA = Constants.Export.DATA_DIR
        val LOGS = Constants.Observability.LOGS_ROOT
        val TRACES = Constants.Observability.TRACES_ROOT
        val METRICS = Constants.Observability.METRICS_ROOT
        const val JSON_SUFFIX = ".json"
        const val SNAPSHOT_SEPARATOR = "_"
    }

    /** Puts everything of [plan] into [zip]; [root] is the directory the zip unpacks to. */
    fun put(
        zip: ExportRecorder,
        plan: QueryPlan,
        work: QueryWorkDirectory,
        root: String,
    ) {
        exportStep(ExportSignal.STACK, "write the stack") {
            stack.files(plan.home, root, plan.tenants, plan.experiment).forEach { (path, bytes) -> zip.put(path, bytes) }
        }
        exportStep(ExportSignal.LOGS, "add the logs") { putTree(File(work.writerStore, LOGS), "$DATA/$LOGS", zip) }
        plan.tenants.forEach { tenant ->
            val blocks = File(work.writerStore, "$TRACES/$tenant").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
            if (blocks.isNotEmpty()) {
                exportStep(ExportSignal.TRACES, "add the traces of $tenant") {
                    blocks.forEach { putTree(it, "$DATA/$TRACES/$tenant/${it.name}", zip) }
                    val tenantDir = "$DATA/$TRACES/$tenant"
                    zip.put("$tenantDir/${TempoTenantIndexWriter.FILE}", tempoIndex.write(zip.directory(tenantDir)))
                }
            }
            exportStep(ExportSignal.METRICS, "add the metrics of $tenant") {
                putTree(File(work.staging, "$METRICS/$tenant"), "$DATA/$METRICS/$tenant", zip)
            }
            val annotations = "${Constants.Observability.GRAFANA_ROOT}/${Constants.Observability.ANNOTATIONS_DIR}/$tenant/"
            val ofClusters = plan.clustersOf(tenant).toSet()
            store
                .list(annotations)
                .filter {
                    it.key
                        .removePrefix(annotations)
                        .removeSuffix(JSON_SUFFIX)
                        .substringAfter(SNAPSHOT_SEPARATOR) in ofClusters
                }.forEach { obj -> exportStep(ExportSignal.ANNOTATIONS, "copy ${obj.key}") { copy(obj.key, obj.lastModified, zip) } }
        }
        plan.covered.forEach { key ->
            val reports = "${Constants.Observability.REPORTS_ROOT}/${key.tenant}/${key.name}/"
            store.list(reports).forEach { obj ->
                exportStep(ExportSignal.REPORTS, "copy ${obj.key}") { copy(obj.key, obj.lastModified, zip) }
            }
        }
    }

    /** Every file under [directory] at [prefix] in the zip, at its relative path; nothing when [directory] is absent. */
    private fun putTree(
        directory: File,
        prefix: String,
        zip: ExportRecorder,
    ) {
        if (!directory.isDirectory) return
        directory
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .forEach { file ->
                zip.put(
                    "$prefix/${file.relativeTo(directory).invariantSeparatorsPath}",
                    file.inputStream(),
                    Instant.ofEpochMilli(file.lastModified()),
                )
            }
    }

    private fun copy(
        key: String,
        lastModified: Instant,
        zip: ExportRecorder,
    ) {
        val input = store.open(key) ?: throw ObjectGoneException(key)
        zip.put("$DATA/$key", input, lastModified)
    }
}
