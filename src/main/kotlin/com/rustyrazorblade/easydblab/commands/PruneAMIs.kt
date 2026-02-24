package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import com.rustyrazorblade.easydblab.services.aws.AMIService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Prune older private AMIs while keeping the newest N per architecture and type.
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "prune-amis",
    description = ["Prune older private AMIs while keeping the newest N per architecture and type"],
)
class PruneAMIs : PicoBaseCommand() {
    private val service: AMIService by inject()

    @Option(
        names = ["--pattern"],
        description = ["Name pattern for AMIs to prune (supports wildcards)"],
    )
    var pattern: String = "rustyrazorblade/images/easy-db-lab-*"

    @Option(
        names = ["--keep"],
        description = ["Number of newest AMIs to keep per architecture/type combination"],
    )
    var keep: Int = 2

    @Option(
        names = ["--dry-run"],
        description = ["Show what would be deleted without actually deleting"],
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--type"],
        description = ["Filter to only prune AMIs of specific type (e.g., 'cassandra', 'base')"],
    )
    var type: String? = null

    override fun execute() {
        eventBus.emit(Event.Ami.PruningStarting(pattern, type, keep))
        eventBus.emit(Event.Ami.Separator)

        val preview = service.pruneAMIs(namePattern = pattern, keepCount = keep, dryRun = true, typeFilter = type)

        displayKeptAMIs(preview)

        if (preview.deleted.isEmpty()) {
            eventBus.emit(Event.Ami.NoAmisToDelete)
            return
        }

        if (dryRun) {
            displayDryRunPreview(preview)
            return
        }

        deleteWithConfirmation(preview)
    }

    private fun displayKeptAMIs(preview: AMIService.PruneResult) {
        if (preview.kept.isNotEmpty()) {
            eventBus.emit(
                Event.Ami.KeptAmis(
                    preview.kept.map {
                        Event.Ami.AmiSummary(it.id, it.name, it.architecture, it.creationDate.toString())
                    },
                ),
            )
            eventBus.emit(Event.Ami.Separator)
        }
    }

    private fun displayDryRunPreview(preview: AMIService.PruneResult) {
        eventBus.emit(
            Event.Ami.DryRunPreview(
                preview.deleted.map {
                    Event.Ami.AmiDeleteCandidate(
                        it.id,
                        it.name,
                        it.architecture,
                        it.creationDate.toString(),
                        it.ownerId,
                        it.isPublic,
                        it.snapshotIds,
                    )
                },
            ),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteWithConfirmation(preview: AMIService.PruneResult) {
        eventBus.emit(Event.Ami.DeletionStarting(preview.deleted.size))

        val actuallyDeleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (ami in preview.deleted) {
            displayAMIDetails(ami)

            print("Delete this AMI? [y/N]: ")
            val response = readlnOrNull()?.trim()?.lowercase() ?: "n"
            val shouldDelete = response == "y" || response == "yes"

            if (shouldDelete) {
                try {
                    service.deregisterAMI(ami.id)
                    for (snapshotId in ami.snapshotIds) {
                        service.deleteSnapshot(snapshotId)
                    }
                    eventBus.emit(Event.Ami.Deleted)
                    actuallyDeleted.add(ami.id)
                } catch (e: Exception) {
                    eventBus.emit(Event.Ami.DeleteFailed(e.message ?: "unknown error"))
                }
            } else {
                eventBus.emit(Event.Ami.Skipped)
                skipped.add(ami.id)
            }
        }

        eventBus.emit(Event.Ami.PruningSummary(actuallyDeleted.size, skipped.size, preview.kept.size))
    }

    private fun displayAMIDetails(ami: AMI) {
        eventBus.emit(
            Event.Ami.AmiDetails(
                ami.id,
                ami.name,
                ami.architecture,
                ami.creationDate.toString(),
                ami.ownerId,
                ami.isPublic,
                ami.snapshotIds,
            ),
        )
    }
}
