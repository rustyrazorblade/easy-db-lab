package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.services.aws.EMRService

/**
 * Service for provisioning EMR clusters for Spark workloads.
 *
 * Encapsulates the logic for creating an EMR cluster from cluster configuration,
 * used by both the `up` command (via ClusterProvisioningService) and the standalone
 * `spark init` command.
 */
interface EMRProvisioningService {
    /**
     * Provisions an EMR cluster using cluster configuration parameters.
     *
     * @param clusterName Base name for the EMR cluster (will have "-spark" appended)
     * @param masterInstanceType Instance type for the master node
     * @param workerInstanceType Instance type for core/worker nodes
     * @param workerCount Number of core/worker nodes
     * @param subnetId Subnet ID where the cluster will be launched
     * @param securityGroupId Security group for cluster access
     * @param keyName SSH key pair name for cluster instances
     * @param clusterState Current cluster state (for S3 log path)
     * @param tags Tags to apply to the cluster
     * @return Created EMR cluster state
     */
    fun provisionEmrCluster(
        clusterName: String,
        masterInstanceType: String,
        workerInstanceType: String,
        workerCount: Int,
        subnetId: String,
        securityGroupId: String,
        keyName: String,
        clusterState: ClusterState,
        tags: Map<String, String>,
    ): EMRClusterState
}

/**
 * Default implementation of EMRProvisioningService.
 *
 * Creates an EMR cluster via [EMRService] and waits for it to reach a ready state.
 */
class DefaultEMRProvisioningService(
    private val emrService: EMRService,
    private val eventBus: EventBus,
) : EMRProvisioningService {
    override fun provisionEmrCluster(
        clusterName: String,
        masterInstanceType: String,
        workerInstanceType: String,
        workerCount: Int,
        subnetId: String,
        securityGroupId: String,
        keyName: String,
        clusterState: ClusterState,
        tags: Map<String, String>,
    ): EMRClusterState {
        eventBus.emit(Event.Emr.SparkClusterCreating)

        val emrConfig =
            EMRClusterConfig(
                clusterName = "$clusterName-spark",
                logUri = clusterState.s3Path().emrLogs().toString(),
                subnetId = subnetId,
                ec2KeyName = keyName,
                masterInstanceType = masterInstanceType,
                coreInstanceType = workerInstanceType,
                coreInstanceCount = workerCount,
                additionalSecurityGroups = listOf(securityGroupId),
                tags = tags,
            )

        val result = emrService.createCluster(emrConfig)
        val readyResult = emrService.waitForClusterReady(result.clusterId)

        return EMRClusterState(
            clusterId = readyResult.clusterId,
            clusterName = readyResult.clusterName,
            masterPublicDns = readyResult.masterPublicDns,
            state = readyResult.state,
        )
    }
}
