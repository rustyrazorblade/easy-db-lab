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
        eventBus.emit(Event.Message("Pruning AMIs matching pattern: $pattern"))
        if (type != null) {
            eventBus.emit(Event.Message("Filtering by type: $type"))
        }
        eventBus.emit(Event.Message("Keeping newest $keep AMIs per architecture/type combination"))
        eventBus.emit(Event.Message(""))

        val preview = service.pruneAMIs(namePattern = pattern, keepCount = keep, dryRun = true, typeFilter = type)

        displayKeptAMIs(preview)

        if (preview.deleted.isEmpty()) {
            eventBus.emit(Event.Message("No AMIs to delete"))
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
            eventBus.emit(Event.Message("Will keep ${preview.kept.size} AMIs:"))
            for (ami in preview.kept) {
                eventBus.emit(Event.Message("  ✓ ${ami.id}: ${ami.name} (${ami.architecture}, ${ami.creationDate})"))
            }
            eventBus.emit(Event.Message(""))
        }
    }

    private fun displayDryRunPreview(preview: AMIService.PruneResult) {
        eventBus.emit(Event.Message("DRY RUN - Would delete ${preview.deleted.size} AMIs:"))
        for (ami in preview.deleted) {
            val visibility = if (ami.isPublic) "public" else "private"
            eventBus.emit(Event.Message("  × ${ami.id}: ${ami.name} (${ami.architecture}, ${ami.creationDate})"))
            eventBus.emit(Event.Message("    Owner: ${ami.ownerId}, Visibility: $visibility"))
            if (ami.snapshotIds.isNotEmpty()) {
                eventBus.emit(Event.Message("    Snapshots: ${ami.snapshotIds.joinToString(", ")}"))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteWithConfirmation(preview: AMIService.PruneResult) {
        eventBus.emit(Event.Message("Found ${preview.deleted.size} AMIs to delete"))

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
                    eventBus.emit(Event.Message("  ✓ Deleted"))
                    actuallyDeleted.add(ami.id)
                } catch (e: Exception) {
                    eventBus.emit(Event.Message("  ✗ Error deleting: ${e.message}"))
                }
            } else {
                eventBus.emit(Event.Message("  - Skipped"))
                skipped.add(ami.id)
            }
        }

        eventBus.emit(
            Event.Message(
                """
            |
            |Summary:
            |  Deleted: ${actuallyDeleted.size} AMIs
            |  Skipped: ${skipped.size} AMIs
            |  Kept: ${preview.kept.size} AMIs
                """.trimMargin(),
            ),
        )
    }

    private fun displayAMIDetails(ami: AMI) {
        val visibility = if (ami.isPublic) "public" else "private"
        eventBus.emit(
            Event.Message(
                """
            |
            |AMI: ${ami.id}
            |  Name: ${ami.name}
            |  Architecture: ${ami.architecture}
            |  Created: ${ami.creationDate}
            |  Owner: ${ami.ownerId}
            |  Visibility: $visibility
                """.trimMargin(),
            ),
        )
        if (ami.snapshotIds.isNotEmpty()) {
            eventBus.emit(Event.Message("  Snapshots: ${ami.snapshotIds.joinToString(", ")}"))
        }
    }
}
