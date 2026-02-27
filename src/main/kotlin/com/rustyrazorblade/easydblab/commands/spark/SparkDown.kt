package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.services.aws.EMRService
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Terminate the Spark EMR cluster on the current environment.
 *
 * This command tears down just the EMR cluster without affecting the rest of the
 * infrastructure (VPC, EC2 instances, etc.), enabling fast iteration on Spark/EMR
 * bootstrap or OTel changes: `spark down` → fix code → rebuild → `spark init`.
 *
 * Prerequisites:
 * - An EMR cluster must exist in the cluster state
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "down",
    description = ["Terminate the Spark EMR cluster"],
)
class SparkDown : PicoBaseCommand() {
    private val emrService: EMRService by inject()

    override fun execute() {
        val state = clusterStateManager.load()

        val emrCluster =
            state.emrCluster
                ?: error("No EMR cluster found in state. Nothing to terminate.")

        val clusterId = emrCluster.clusterId

        emrService.terminateCluster(clusterId)
        emrService.waitForClusterTerminated(clusterId)

        state.updateEmrCluster(null)
        clusterStateManager.save(state)
    }
}
