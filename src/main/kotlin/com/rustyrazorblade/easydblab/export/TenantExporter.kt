package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import java.io.File
import java.nio.file.Files
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
 * 2. fail before copying when the data is larger than the free space where the zip goes
 *    ([Event.Export.InsufficientSpace]), else report the size ([Event.Export.SizeEstimated]);
 * 3. stream the stack files ([ExportStackFiles]) and the copy into the zip, which [ExportZipWriter]
 *    moves to the path only when complete;
 * 4. report the zip and what it leaves out ([Event.Export.Completed]).
 *
 * On any failure no zip is left at the path, the tool's own scratch is removed, and
 * [Event.Export.Failed] names the signal and the step. It reads the bucket and writes only the local
 * zip: nothing in S3 is written, deleted or uploaded.
 *
 * @property stores opens the account bucket.
 * @property stack the files the export carries besides the data.
 * @property zipWriter writes the zip atomically.
 * @property diskSpace the free space where the zip goes.
 * @property eventBus reports each step.
 * @property clock the time the generated indexes record.
 */
class TenantExporter(
    private val stores: ExportStoreFactory,
    private val stack: ExportStackFiles,
    private val zipWriter: ExportZipWriter,
    private val diskSpace: DiskSpace,
    private val eventBus: EventBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        val EXCLUDED = listOf(Event.Export.Exclusion("profiles", Constants.Export.PROFILES_ISSUE))
        const val SCRATCH_PREFIX = ".scratch-"
    }

    /** Exports [tenant] to [target]. */
    fun export(
        target: ExportTarget,
        tenant: String,
    ) {
        stores.open().use { store ->
            val copy = TenantStoreCopy(store, MimirBucketIndexWriter(clock), TempoTenantIndexWriter(clock))
            val plan = eventBus.reportingStepFailure(target) { plan(copy, tenant) }
            val directory = target.zip.absoluteFile.parentFile
            val free = diskSpace.free(directory)
            if (plan.bytes > free) {
                eventBus.emit(Event.Export.InsufficientSpace(target.zip.path, plan.bytes, free))
                throw CommandFailedException("Not enough disk space for ${target.zip.path}")
            }
            eventBus.emit(Event.Export.SizeEstimated(tenant, plan.objects, plan.bytes, free))
            val written =
                eventBus.reportingStepFailure(target) {
                    zipWriter.write(target) { sink ->
                        val zip = ExportRecorder(sink)
                        exportStep(ExportSignal.STACK, "write the stack") {
                            stack.put(zip, tenant, root = target.root)
                        }
                        withScratch(directory, target) { scratch -> copy.copy(plan, zip, scratch) }
                        zip
                    }
                }
            eventBus.emit(Event.Export.Completed(target.zip.path, tenant, written.count, written.bytes, EXCLUDED))
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

    /** Runs [block] with a scratch directory of the tool's own beside the zip, removed afterwards. */
    private fun <T> withScratch(
        directory: File,
        target: ExportTarget,
        block: (File) -> T,
    ): T {
        val scratch =
            exportStep(ExportSignal.LOGS, "create the scratch directory for Loki's index tables in $directory") {
                Files.createTempDirectory(directory.toPath(), "${target.zip.name}$SCRATCH_PREFIX").toFile()
            }
        try {
            return block(scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }
}
