package com.rustyrazorblade.easydblab.commands.spark

import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.EMRProvisioningService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Provision a Spark EMR cluster on an existing environment.
 *
 * This command creates an EMR cluster for Spark workloads on an environment that
 * was previously created with `init` and `up`. It allows adding Spark capabilities
 * to a running cluster without needing to recreate the environment.
 *
 * Prerequisites:
 * - `easy-db-lab init` must have been run (state.json must exist with initConfig)
 * - `easy-db-lab up` must have been run (infrastructure must be provisioned)
 * - No EMR cluster should already exist on this environment
 *
 * Usage:
 * - `spark init` - Create EMR cluster with default settings
 * - `spark init --master.instance.type m5.2xlarge` - Custom master instance type
 * - `spark init --worker.instance.count 5` - Custom worker count
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "init",
    description = ["Provision Spark EMR cluster on existing environment"],
)
class SparkInit : PicoBaseCommand() {
    companion object {
        private const val DEFAULT_SPARK_WORKER_COUNT = 3
    }

    private val emrProvisioningService: EMRProvisioningService by inject()
    private val user: User by inject()

    @Option(
        names = ["--master.instance.type"],
        description = ["Master instance type"],
    )
    var masterInstanceType: String = "m5.xlarge"

    @Option(
        names = ["--worker.instance.type"],
        description = ["Worker instance type"],
    )
    var workerInstanceType: String = "m5.xlarge"

    @Option(
        names = ["--worker.instance.count"],
        description = ["Worker instance count"],
    )
    var workerCount: Int = DEFAULT_SPARK_WORKER_COUNT

    override fun execute() {
        val state = clusterStateManager.load()

        val infrastructure =
            state.infrastructure
                ?: error("Infrastructure not provisioned. Run 'easy-db-lab up' first.")

        requireNotNull(state.initConfig) {
            "Init configuration not found. Run 'easy-db-lab init' first."
        }

        require(state.emrCluster == null) {
            "EMR cluster already exists (${state.emrCluster?.clusterId}). " +
                "Terminate it first before creating a new one."
        }

        val securityGroupId =
            requireNotNull(infrastructure.securityGroupId) {
                "Security group not found in infrastructure state."
            }

        val tags =
            mapOf(
                "easy_cass_lab" to "1",
                "ClusterId" to state.clusterId,
            )

        val emrCluster =
            emrProvisioningService.provisionEmrCluster(
                clusterName = state.initConfig!!.name,
                masterInstanceType = masterInstanceType,
                workerInstanceType = workerInstanceType,
                workerCount = workerCount,
                subnetId = infrastructure.subnetIds.first(),
                securityGroupId = securityGroupId,
                keyName = user.keyName,
                clusterState = state,
                tags = tags,
            )

        state.updateEmrCluster(emrCluster)
        state.initConfig =
            state.initConfig!!.copy(
                sparkEnabled = true,
                sparkMasterInstanceType = masterInstanceType,
                sparkWorkerInstanceType = workerInstanceType,
                sparkWorkerCount = workerCount,
            )
        clusterStateManager.save(state)

        eventBus.emit(Event.Message("Spark EMR cluster provisioned: ${emrCluster.clusterId}"))
        emrCluster.masterPublicDns?.let {
            eventBus.emit(Event.Message("Master DNS: $it"))
        }
    }
}
