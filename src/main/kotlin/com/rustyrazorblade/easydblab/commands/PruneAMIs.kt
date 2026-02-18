package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.providers.aws.AMIService
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

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    override fun execute() {
        outputHandler.handleMessage("Pruning AMIs matching pattern: $pattern")
        if (type != null) {
            outputHandler.handleMessage("Filtering by type: $type")
        }
        outputHandler.handleMessage("Keeping newest $keep AMIs per architecture/type combination")
        outputHandler.handleMessage("")

        // First, identify which AMIs would be deleted (dry-run)
        val preview =
            service.pruneAMIs(
                namePattern = pattern,
                keepCount = keep,
                dryRun = true,
                typeFilter = type,
            )

        // Show what will be kept
        if (preview.kept.isNotEmpty()) {
            outputHandler.handleMessage("Will keep ${preview.kept.size} AMIs:")
            for (ami in preview.kept) {
                outputHandler.handleMessage("  ✓ ${ami.id}: ${ami.name} (${ami.architecture}, ${ami.creationDate})")
            }
            outputHandler.handleMessage("")
        }

        // Check if there's anything to delete
        if (preview.deleted.isEmpty()) {
            outputHandler.handleMessage("No AMIs to delete")
            return
        }

        // If dry-run mode, just show what would be deleted and exit
        if (dryRun) {
            outputHandler.handleMessage("DRY RUN - Would delete ${preview.deleted.size} AMIs:")
            for (ami in preview.deleted) {
                val visibility = if (ami.isPublic) "public" else "private"
                outputHandler.handleMessage("  × ${ami.id}: ${ami.name} (${ami.architecture}, ${ami.creationDate})")
                outputHandler.handleMessage("    Owner: ${ami.ownerId}, Visibility: $visibility")
                if (ami.snapshotIds.isNotEmpty()) {
                    outputHandler.handleMessage("    Snapshots: ${ami.snapshotIds.joinToString(", ")}")
                }
            }
            return
        }

        // Delete AMIs with confirmation (already sorted oldest first by AMIService)
        outputHandler.handleMessage("Found ${preview.deleted.size} AMIs to delete")
        val amisToDelete = preview.deleted

        val actuallyDeleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (ami in amisToDelete) {
            val visibility = if (ami.isPublic) "public" else "private"
            outputHandler.handleMessage("")
            outputHandler.handleMessage("AMI: ${ami.id}")
            outputHandler.handleMessage("  Name: ${ami.name}")
            outputHandler.handleMessage("  Architecture: ${ami.architecture}")
            outputHandler.handleMessage("  Created: ${ami.creationDate}")
            outputHandler.handleMessage("  Owner: ${ami.ownerId}")
            outputHandler.handleMessage("  Visibility: $visibility")
            if (ami.snapshotIds.isNotEmpty()) {
                outputHandler.handleMessage("  Snapshots: ${ami.snapshotIds.joinToString(", ")}")
            }

            print("Delete this AMI? [y/N]: ")
            val response = readlnOrNull()?.trim()?.lowercase() ?: "n"
            val shouldDelete = response == "y" || response == "yes"

            if (shouldDelete) {
                try {
                    service.deregisterAMI(ami.id)
                    for (snapshotId in ami.snapshotIds) {
                        service.deleteSnapshot(snapshotId)
                    }
                    outputHandler.handleMessage("  ✓ Deleted")
                    actuallyDeleted.add(ami.id)
                } catch (e: Exception) {
                    outputHandler.handleMessage("  ✗ Error deleting: ${e.message}")
                }
            } else {
                outputHandler.handleMessage("  - Skipped")
                skipped.add(ami.id)
            }
        }

        // Summary
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Summary:")
        outputHandler.handleMessage("  Deleted: ${actuallyDeleted.size} AMIs")
        outputHandler.handleMessage("  Skipped: ${skipped.size} AMIs")
        outputHandler.handleMessage("  Kept: ${preview.kept.size} AMIs")
    }
}
