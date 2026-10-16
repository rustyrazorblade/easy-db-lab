package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.export.ExportRecorder
import com.rustyrazorblade.easydblab.export.ExportSelector
import com.rustyrazorblade.easydblab.export.ExportSignal
import com.rustyrazorblade.easydblab.export.ExportStackFiles
import com.rustyrazorblade.easydblab.export.ExportStepFailed
import com.rustyrazorblade.easydblab.export.ExportTarget
import com.rustyrazorblade.easydblab.export.ExportZipWriter
import com.rustyrazorblade.easydblab.export.MimirBucketIndexWriter
import com.rustyrazorblade.easydblab.export.TempoTenantIndexWriter
import com.rustyrazorblade.easydblab.export.exportStep
import com.rustyrazorblade.easydblab.export.reportingStepFailure
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.LokiTailFlush
import com.rustyrazorblade.easydblab.services.TenantDirectory
import java.time.Clock
import java.time.Duration

/**
 * What a query export copied of each signal.
 *
 * @property metrics the metric blocks.
 * @property logs the log lines.
 * @property traces the traces.
 */
data class QueryCounts(
    val metrics: SignalCount,
    val logs: SignalCount,
    val traces: SignalCount,
)

/**
 * `observability export --cluster <c>...` and `--experiment <e>`: an export by query of the selected
 * clusters (design section 7), which opens offline as the local stack, as a tenant export does.
 *
 * In order, stopping at the first failure:
 * 1. open the account bucket, checked readable ([QueryStoreFactory]), and make the work directory
 *    `<path>.work-<id>/` ([QueryWorkDirectory]);
 * 2. start the temporary reader over the live store ([QueryRuntime.reader]) and plan the export from
 *    its Mimir ([QueryPlanner]): a cluster or an experiment that is not there fails with its own event;
 * 3. start the temporary writer, copy each cluster's logs ([LogExport]) and flush the writer's Loki,
 *    copy each cluster's traces ([TraceExport]) and drain the writer's Tempo ([WriterTempoDrain]),
 *    stopping each writer only once it has written everything;
 * 4. stage, rewrite and index each tenant's metric blocks ([MetricsExport]);
 * 5. write the zip ([QueryZipContents], [ExportZipWriter]: atomic, only the tool's own partial file is
 *    ever removed), then report each signal, zero as zero ([Event.Export.SignalExported]), and the zip.
 *
 * Both stacks are removed when the export ends, the work directory never: its place and size are
 * reported last, on success and on failure ([Event.Export.WorkDirectoryKept]). Nothing in S3 is
 * written: the reader's gate refuses every write, and the store is only listed and read.
 *
 * @property stores opens the account bucket.
 * @property runtime starts the stacks and the rewrite.
 * @property stack the files the export carries besides the data.
 * @property zipWriter writes the zip atomically.
 * @property eventBus reports each step.
 * @property clock now, and the time the generated indexes record.
 */
class QueryExporter(
    private val stores: QueryStoreFactory,
    private val runtime: QueryRuntime,
    private val stack: ExportStackFiles,
    private val zipWriter: ExportZipWriter,
    private val eventBus: EventBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        val EXCLUDED = listOf(Event.Export.Exclusion("profiles", Constants.Export.PROFILES_ISSUE))
        val LOKI_FLUSH_TIMEOUT: Duration = Duration.ofMinutes(10)
    }

    /** Exports [selector] to [target]; [workspace] is the cluster workspace the command runs in, if any. */
    fun export(
        target: ExportTarget,
        selector: ExportSelector,
        workspace: WorkspaceCluster?,
    ) {
        require(selector !is ExportSelector.Tenant) { "A tenant export copies the store; it is not a query export" }
        val access = stores.open()
        access.store.use {
            val work = QueryWorkDirectory.create(target)
            try {
                eventBus.reportingStepFailure(target, logs = { work.stackLogs().map { it.path } }) {
                    run(target, selector, workspace, access, work)
                }
            } finally {
                eventBus.emit(Event.Export.WorkDirectoryKept(work.root.path, work.size()))
            }
        }
    }

    private fun run(
        target: ExportTarget,
        selector: ExportSelector,
        workspace: WorkspaceCluster?,
        access: QueryStoreAccess,
        work: QueryWorkDirectory,
    ) {
        val store = access.store
        val metricsRoot = "${Constants.Observability.METRICS_ROOT}/"
        val storeTenants =
            exportStep(ExportSignal.METRICS, "list the tenants") {
                TenantDirectory.tenantNames(store.listDirectories(metricsRoot).map { it.removePrefix(metricsRoot).trimEnd('/') })
            }
        val readerHome =
            (selector as? ExportSelector.Experiment)?.key?.tenant ?: storeTenants.firstOrNull() ?: Constants.Observability.DEFAULT_TENANT
        val metrics = MetricsExport(store, runtime.rewrite(work), MimirBucketIndexWriter(clock))
        val (plan, counts) =
            exportStep(ExportSignal.STACK, "start the reader stack") { runtime.reader(work, access.live(readerHome)) }.use { reader ->
                val plan = plan(QueryPlanner(MimirApi(reader::http, clock), metrics::oldestBlock, clock), selector, storeTenants, workspace)
                eventBus.emit(
                    Event.Export.ClustersSelected(plan.clusters.map { Event.Export.SelectedCluster(it.key, it.value) }, plan.home),
                )
                val (logs, traces) =
                    exportStep(ExportSignal.STACK, "start the writer stack") {
                        runtime.writer(work, plan.home)
                    }.use { copy(plan, reader, it, work) }
                val metricBlocks =
                    plan.tenants.fold(SignalCount.ZERO) { count, tenant ->
                        val clusters = plan.clustersOf(tenant)
                        count + metrics.export(tenant, clusters, clusters.map { plan.ranges.getValue(it) }, work.staging)
                    }
                plan to QueryCounts(metricBlocks, logs, traces)
            }
        val written =
            zipWriter.write(target) { sink ->
                ExportRecorder(sink).also { QueryZipContents(store, stack, TempoTenantIndexWriter(clock)).put(it, plan, work, target.root) }
            }
        eventBus.emit(Event.Export.SignalExported(ExportSignal.METRICS.label, counts.metrics.items, counts.metrics.bytes))
        eventBus.emit(Event.Export.SignalExported(ExportSignal.LOGS.label, counts.logs.items, counts.logs.bytes))
        eventBus.emit(Event.Export.SignalExported(ExportSignal.TRACES.label, counts.traces.items, counts.traces.bytes))
        eventBus.emit(
            Event.Export.QueryCompleted(target.zip.path, plan.clusters.keys.toList(), plan.tenants, written.count, written.bytes, EXCLUDED),
        )
    }

    /** The plan of [selector]; a selection that cannot be found fails with its own event. */
    private fun plan(
        planner: QueryPlanner,
        selector: ExportSelector,
        storeTenants: List<String>,
        workspace: WorkspaceCluster?,
    ): QueryPlan =
        try {
            planner.plan(selector, storeTenants, workspace)
        } catch (e: QueryExportRefused) {
            eventBus.emit(refusal(e))
            throw CommandFailedException(e.message ?: "The export cannot find what it was asked for")
        } catch (e: ExportStepFailed) {
            throw e
        } catch (e: Exception) {
            throw ExportStepFailed(ExportSignal.METRICS, "find the clusters, their tenants and when they ran", e)
        }

    private fun refusal(e: QueryExportRefused): Event.Export =
        when (e) {
            is ClusterNotFoundException -> Event.Export.ClusterNotFound(e.cluster, e.searched)
            is ClusterInSeveralTenantsException -> Event.Export.ClusterInSeveralTenants(e.cluster, e.tenants)
            is ClusterTenantUnknownException -> Event.Export.ClusterTenantUnknown(e.cluster, e.tenantSet)
            is ExperimentNotFoundException -> Event.Export.ExperimentNotFound(e.key.name, e.key.tenant, e.query)
        }

    /** Copies every cluster's logs and traces into [writer], flushing and stopping each writer once it holds them. */
    private fun copy(
        plan: QueryPlan,
        reader: ExportStack,
        writer: ExportStack,
        work: QueryWorkDirectory,
    ): Pair<SignalCount, SignalCount> {
        val logExport = LogExport(reader::http, writer::http)
        val logs =
            plan.clusters.entries.fold(SignalCount.ZERO) { count, (cluster, tenant) ->
                count +
                    exportStep(
                        ExportSignal.LOGS,
                        "copy the logs of $cluster",
                    ) { logExport.export(tenant, cluster, plan.ranges.getValue(cluster)) }
            }
        exportStep(ExportSignal.LOGS, "flush the writer's Loki") {
            val flushed =
                writer
                    .http(
                        plan.home,
                    ).post(Constants.K8s.LOKI_HTTP_PORT, LokiTailFlush.SHUTDOWN_PATH, timeout = LOKI_FLUSH_TIMEOUT)
            check(
                flushed.code == Constants.HttpStatus.NO_CONTENT,
            ) { "The writer's Loki did not flush (status ${flushed.code}): ${flushed.body}" }
            // Its index head is uploaded as it stops.
            writer.stop(Constants.LocalStack.Services.LOKI)
        }
        val traceExport = TraceExport(reader::http, writer::http)
        val traces =
            plan.clusters.entries.fold(SignalCount.ZERO) { count, (cluster, tenant) ->
                count +
                    exportStep(
                        ExportSignal.TRACES,
                        "copy the traces of $cluster",
                    ) { traceExport.export(tenant, cluster, plan.ranges.getValue(cluster)) }
            }
        exportStep(ExportSignal.TRACES, "drain the writer's Tempo") {
            WriterTempoDrain(writer.http(plan.home), work.writerTempoWal).await()
            writer.stop(Constants.LocalStack.Services.TEMPO)
        }
        return logs to traces
    }
}
