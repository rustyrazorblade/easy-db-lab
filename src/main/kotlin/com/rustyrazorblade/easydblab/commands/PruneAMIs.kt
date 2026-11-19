package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.providers.aws.model.AMI
import com.rustyrazorblade.easydblab.providers.aws.requireAwsFailure
import com.rustyrazorblade.easydblab.runCatchingException
import com.rustyrazorblade.easydblab.services.aws.AMIService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Prune older private AMIs while keeping the newest N per architecture and type.
 */
@RequireProfileSetup
@RequiresCapability(NodeCapability.AWS_ACCOUNT)
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

    private fun deleteWithConfirmation(preview: AMIService.PruneResult) {
        eventBus.emit(Event.Ami.DeletionStarting(preview.deleted.size))

        val actuallyDeleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (ami in preview.deleted) {
            if (confirmAndDeleteAmi(ami)) {
                actuallyDeleted.add(ami.id)
            } else {
                skipped.add(ami.id)
            }
        }

        eventBus.emit(Event.Ami.PruningSummary(actuallyDeleted.size, skipped.size, preview.kept.size))
    }

    private fun confirmAndDeleteAmi(ami: AMI): Boolean {
        displayAMIDetails(ami)
        print("Delete this AMI? [y/N]: ")
        val response = readlnOrNull()?.trim()?.lowercase() ?: "n"
        val shouldDelete = response == "y" || response == "yes"
        if (!shouldDelete) {
            println("  - Skipped")
            return false
        }
        // One AMI that AWS will not delete is reported and skipped; the rest of the prune goes on.
        // Any other failure propagates.
        return runCatchingException {
            service.deregisterAMI(ami.id)
            for (snapshotId in ami.snapshotIds) {
                service.deleteSnapshot(snapshotId)
            }
            println("  ✓ Deleted")
        }.onFailure { e -> eventBus.emit(Event.Ami.DeleteFailed(e.requireAwsFailure().messageOrString())) }
            .isSuccess
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
