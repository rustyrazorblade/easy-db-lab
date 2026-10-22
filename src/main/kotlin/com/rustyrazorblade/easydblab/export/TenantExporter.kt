package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import java.io.File
import java.time.Clock

/** Opens the account bucket for an export to read. */
fun interface ExportStoreFactory {
    /** The account bucket, read-only; the caller closes it. */
    fun open(): ExportStore
}

/** The free space of the file system a directory is on. */
fun interface DiskSpace {
    /** The bytes free for a new file in [directory]. */
    fun free(directory: File): Long
}

/**
 * `observability export --tenant <t>`: a file copy of one tenant's store into a zip that opens offline
 * as the local stack.
 *
 * In order, stopping at the first failure:
 * 1. list everything the export copies ([TenantStoreCopy.plan]); a tenant with no data fails,
 *    naming the tenant and the prefixes ([Event.Export.EmptyTenant]);
 * 2. fail before copying when the free space where the zip goes cannot hold the data and, in the work
 *    directory beside it, the local copy of Loki's index and the split's output
 *    ([TenantCopyPlan.diskBytes], [Event.Export.InsufficientSpace]), else report the size
 *    ([Event.Export.SizeEstimated]);
 * 3. make the work directory ([ExportWorkDirectory]), where Loki's index is copied and split;
 * 4. stream the stack files ([ExportStackFiles]) and the copy into the zip, which [ExportZipWriter]
 *    moves to the path only when complete;
 * 5. report the zip and what it leaves out ([Event.Export.Completed]).
 *
 * The work directory is never removed: its place and size are reported last, on success and on
 * failure ([Event.Export.WorkDirectoryKept]). On any failure no zip is left at the path, and
 * [Event.Export.Failed] names the signal and the step, and the split's saved logs when it ran. It reads
 * the bucket and writes only the local zip and the work directory: nothing in S3 is written, deleted or
 * uploaded.
 *
 * @property stores opens the account bucket.
 * @property stack the files the export carries besides the data.
 * @property zipWriter writes the zip atomically.
 * @property diskSpace the free space where the zip goes.
 * @property eventBus reports each step.
 * @property lokiSplit splits Loki's shared index files per tenant (Loki's compactor in docker).
 * @property clock the time the generated indexes record.
 */
class TenantExporter(
    private val stores: ExportStoreFactory,
    private val stack: ExportStackFiles,
    private val zipWriter: ExportZipWriter,
    private val diskSpace: DiskSpace,
    private val eventBus: EventBus,
    private val lokiSplit: LokiIndexSplit,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        val EXCLUDED = listOf(Event.Export.Exclusion("profiles", Constants.Export.PROFILES_ISSUE))
    }

    /** Exports [tenant] to [target]. */
    fun export(
        target: ExportTarget,
        tenant: String,
    ) {
        stores.open().use { store ->
            val copy = TenantStoreCopy(store, MimirBucketIndexWriter(clock), TempoTenantIndexWriter(clock), lokiSplit)
            val plan = eventBus.reportingStepFailure(target) { plan(copy, tenant) }
            val directory = target.zip.absoluteFile.parentFile
            val free = diskSpace.free(directory)
            if (plan.diskBytes > free) {
                eventBus.emit(Event.Export.InsufficientSpace(target.zip.path, plan.diskBytes, free))
                throw CommandFailedException("Not enough disk space for ${target.zip.path}")
            }
            eventBus.emit(Event.Export.SizeEstimated(tenant, plan.objects, plan.bytes, free))
            val work =
                eventBus.reportingStepFailure(target) {
                    exportStep(ExportSignal.STACK, "create the work directory beside ${target.zip.path}") {
                        ExportWorkDirectory.create(target)
                    }
                }
            try {
                val written =
                    eventBus.reportingStepFailure(target, logs = { TenantStoreCopy.savedLogs(work.root).map { it.path } }) {
                        zipWriter.write(target) { sink ->
                            val zip = ExportRecorder(sink)
                            exportStep(ExportSignal.STACK, "write the stack") {
                                stack.put(zip, tenant, root = target.root)
                            }
                            copy.copy(plan, zip, work.root)
                            zip
                        }
                    }
                eventBus.emit(Event.Export.Completed(target.zip.path, tenant, written.count, written.bytes, EXCLUDED))
            } finally {
                eventBus.emit(Event.Export.WorkDirectoryKept(work.root.path, work.size()))
            }
        }
    }

    private fun plan(
        copy: TenantStoreCopy,
        tenant: String,
    ): TenantCopyPlan =
        try {
            copy.plan(tenant)
        } catch (e: EmptyTenantException) {
            eventBus.emit(Event.Export.EmptyTenant(e.tenant, e.prefixes))
            throw CommandFailedException(e.message ?: "Tenant $tenant has no data")
        }
}
