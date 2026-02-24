package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Get the S3 bucket name for the current cluster.
 *
 * This command outputs the S3 bucket configured for the cluster,
 * used for logs, bulk data, and other storage.
 *
 * Examples:
 *   easy-db-lab aws s3-bucket  # Returns bucket name (e.g., easy-db-lab-mycluster-abc123)
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "s3-bucket",
    description = ["Get S3 bucket name for the current cluster"],
)
class S3Bucket :
    PicoCommand,
    KoinComponent {
    private val eventBus: EventBus by inject()
    private val clusterStateManager: ClusterStateManager by inject()
    private val clusterState by lazy { clusterStateManager.load() }

    override fun execute() {
        val bucket = clusterState.s3Bucket
        if (bucket.isNullOrBlank()) {
            error("No S3 bucket configured. Run 'easy-db-lab up' first.")
        }
        eventBus.emit(Event.Command.S3BucketName(bucket))
    }
}
