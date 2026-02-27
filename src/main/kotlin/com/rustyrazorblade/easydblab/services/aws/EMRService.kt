package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import com.rustyrazorblade.easydblab.providers.aws.ClusterId
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterConfig
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterResult
import com.rustyrazorblade.easydblab.providers.aws.EMRConfiguration
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterStates
import com.rustyrazorblade.easydblab.providers.aws.EMRClusterStatus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.SubnetId
import com.rustyrazorblade.easydblab.providers.aws.VpcId
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.Application
import software.amazon.awssdk.services.emr.model.BootstrapActionConfig
import software.amazon.awssdk.services.emr.model.ClusterState
import software.amazon.awssdk.services.emr.model.ClusterSummary
import software.amazon.awssdk.services.emr.model.Configuration
import software.amazon.awssdk.services.emr.model.DescribeClusterRequest
import software.amazon.awssdk.services.emr.model.InstanceGroupConfig
import software.amazon.awssdk.services.emr.model.InstanceGroupType
import software.amazon.awssdk.services.emr.model.InstanceRoleType
import software.amazon.awssdk.services.emr.model.JobFlowInstancesConfig
import software.amazon.awssdk.services.emr.model.ListClustersRequest
import software.amazon.awssdk.services.emr.model.ListInstancesRequest
import software.amazon.awssdk.services.emr.model.RunJobFlowRequest
import software.amazon.awssdk.services.emr.model.ScriptBootstrapActionConfig
import software.amazon.awssdk.services.emr.model.Tag
import software.amazon.awssdk.services.emr.model.TerminateJobFlowsRequest

/** Recursively converts an EMRConfiguration to the AWS SDK Configuration type. */
private fun EMRConfiguration.toAwsConfiguration(): Configuration {
    val builder =
        Configuration
            .builder()
            .classification(classification)
            .properties(properties)
    if (configurations.isNotEmpty()) {
        builder.configurations(configurations.map { it.toAwsConfiguration() })
    }
    return builder.build()
}

/**
 * Service for managing the full lifecycle of EMR clusters.
 *
 * This service handles creation, status monitoring, discovery, and termination
 * of EMR clusters for Spark job execution.
 */
@Suppress("TooManyFunctions")
class EMRService(
    private val emrClient: EmrClient,
    private val eventBus: EventBus,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Default timeout for waiting on cluster to reach WAITING state (30 minutes) */
        const val DEFAULT_READY_TIMEOUT_MS = 30 * 60 * 1000L

        /** Polling interval for checking cluster state */
        const val POLL_INTERVAL_MS = 15_000L

        /** Default timeout for waiting on cluster termination (15 minutes) */
        const val DEFAULT_TERMINATION_TIMEOUT_MS = 15 * 60 * 1000L

        /** Polling interval for checking cluster state during termination */
        const val TERMINATION_POLL_INTERVAL_MS = 10_000L

        // Use shared EMR cluster states from EMRClusterStates
        private val READY_STATES = EMRClusterStates.READY_STATES.map { ClusterState.fromValue(it) }.toSet()
        private val TERMINAL_STATES = EMRClusterStates.TERMINAL_STATES.map { ClusterState.fromValue(it) }.toSet()
        private val STARTING_STATES = EMRClusterStates.STARTING_STATES.map { ClusterState.fromValue(it) }.toSet()

        /** EMR cluster states that indicate the cluster is active and can be terminated */
        private val ACTIVE_CLUSTER_STATES =
            listOf(
                ClusterState.STARTING,
                ClusterState.BOOTSTRAPPING,
                ClusterState.RUNNING,
                ClusterState.WAITING,
            )
    }

    /**
     * Creates an EMR cluster for Spark job execution.
     *
     * @param config EMR cluster configuration
     * @return Result containing the cluster ID and details
     */
    fun createCluster(config: EMRClusterConfig): EMRClusterResult {
        log.info { "Creating EMR cluster: ${config.clusterName}" }
        eventBus.emit(Event.Emr.ClusterCreating(config.clusterName))

        val tags =
            config.tags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        val applications =
            config.applications.map { appName ->
                Application.builder().name(appName).build()
            }

        val masterInstanceGroup =
            InstanceGroupConfig
                .builder()
                .instanceRole(InstanceRoleType.MASTER)
                .instanceType(config.masterInstanceType)
                .instanceCount(1)
                .build()

        val coreInstanceGroup =
            InstanceGroupConfig
                .builder()
                .instanceRole(InstanceRoleType.CORE)
                .instanceType(config.coreInstanceType)
                .instanceCount(config.coreInstanceCount)
                .build()

        val instancesConfigBuilder =
            JobFlowInstancesConfig
                .builder()
                .ec2SubnetId(config.subnetId)
                .ec2KeyName(config.ec2KeyName)
                .instanceGroups(masterInstanceGroup, coreInstanceGroup)
                .keepJobFlowAliveWhenNoSteps(true)

        // Add additional security groups if specified (needed for EMR to access Cassandra)
        if (config.additionalSecurityGroups.isNotEmpty()) {
            instancesConfigBuilder
                .additionalMasterSecurityGroups(config.additionalSecurityGroups)
                .additionalSlaveSecurityGroups(config.additionalSecurityGroups)
            log.info { "Adding security groups to EMR: ${config.additionalSecurityGroups}" }
        }

        val instancesConfig = instancesConfigBuilder.build()

        val requestBuilder =
            RunJobFlowRequest
                .builder()
                .name(config.clusterName)
                .releaseLabel(config.releaseLabel)
                .applications(applications)
                .serviceRole(config.serviceRole)
                .jobFlowRole(config.jobFlowRole)
                .instances(instancesConfig)
                .tags(tags)

        if (config.logUri.isNotEmpty()) {
            requestBuilder.logUri(config.logUri)
        }

        if (config.bootstrapActions.isNotEmpty()) {
            val bootstrapConfigs =
                config.bootstrapActions.map { action ->
                    BootstrapActionConfig
                        .builder()
                        .name(action.name)
                        .scriptBootstrapAction(
                            ScriptBootstrapActionConfig
                                .builder()
                                .path(action.scriptS3Path)
                                .args(action.args)
                                .build(),
                        ).build()
                }
            requestBuilder.bootstrapActions(bootstrapConfigs)
            log.info { "Adding ${config.bootstrapActions.size} bootstrap actions to EMR cluster" }
        }

        if (config.configurations.isNotEmpty()) {
            val emrConfigurations = config.configurations.map { it.toAwsConfiguration() }
            requestBuilder.configurations(emrConfigurations)
            log.info { "Adding ${config.configurations.size} configurations to EMR cluster" }
        }

        val request = requestBuilder.build()

        val response = RetryUtil.withAwsRetry("run-job-flow") { emrClient.runJobFlow(request) }
        val clusterId = response.jobFlowId()

        log.info { "EMR cluster creation initiated: $clusterId" }
        eventBus.emit(Event.Emr.ClusterInitiated(clusterId))

        return EMRClusterResult(
            clusterId = clusterId,
            clusterName = config.clusterName,
            masterPublicDns = null,
            state = ClusterState.STARTING.toString(),
        )
    }

    /**
     * Waits for the EMR cluster to reach a ready state (RUNNING or WAITING).
     *
     * @param clusterId The cluster ID to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return Updated cluster result with master DNS
     */
    fun waitForClusterReady(
        clusterId: ClusterId,
        timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    ): EMRClusterResult {
        log.info { "Waiting for EMR cluster $clusterId to be ready..." }
        eventBus.emit(Event.Emr.ClusterWaitingNote)

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getClusterStatus(clusterId)

            val clusterState = ClusterState.fromValue(status.state)

            when {
                clusterState in READY_STATES -> {
                    log.info { "EMR cluster $clusterId is ready (state: ${status.state})" }
                    eventBus.emit(Event.Emr.ClusterReady)

                    // Get the full details including master DNS
                    val details = getClusterDetails(clusterId)
                    return details
                }
                clusterState in TERMINAL_STATES -> {
                    val message =
                        "EMR cluster $clusterId failed: ${status.state}" +
                            (status.stateChangeReason?.let { " - $it" } ?: "")
                    log.error { message }
                    error(message)
                }
                clusterState in STARTING_STATES -> {
                    log.debug { "EMR cluster $clusterId is starting (state: ${status.state})" }
                }
                else -> {
                    log.debug { "EMR cluster $clusterId state: ${status.state}" }
                }
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }

        error("Timeout waiting for EMR cluster $clusterId to be ready after ${timeoutMs}ms")
    }

    /**
     * Gets the current status of an EMR cluster.
     *
     * @param clusterId The cluster ID
     * @return Current cluster status
     */
    fun getClusterStatus(clusterId: ClusterId): EMRClusterStatus {
        val request =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val response = RetryUtil.withAwsRetry("describe-cluster") { emrClient.describeCluster(request) }
        val cluster = response.cluster()

        return EMRClusterStatus(
            clusterId = clusterId,
            state = cluster.status().state().toString(),
            stateChangeReason = cluster.status().stateChangeReason()?.message(),
        )
    }

    /**
     * Gets detailed information about an EMR cluster.
     *
     * @param clusterId The cluster ID
     * @return Cluster details including master DNS
     */
    fun getClusterDetails(clusterId: ClusterId): EMRClusterResult {
        val request =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val response = RetryUtil.withAwsRetry("describe-cluster") { emrClient.describeCluster(request) }
        val cluster = response.cluster()

        return EMRClusterResult(
            clusterId = clusterId,
            clusterName = cluster.name(),
            masterPublicDns = cluster.masterPublicDnsName(),
            state = cluster.status().state().toString(),
        )
    }

    /**
     * Terminates an EMR cluster.
     *
     * @param clusterId The cluster ID to terminate
     */
    fun terminateCluster(clusterId: ClusterId) {
        log.info { "Terminating EMR cluster: $clusterId" }
        eventBus.emit(Event.Emr.ClusterTerminating(clusterId))

        val request =
            TerminateJobFlowsRequest
                .builder()
                .jobFlowIds(clusterId)
                .build()

        RetryUtil.withAwsRetry("terminate-cluster") { emrClient.terminateJobFlows(request) }

        log.info { "EMR cluster termination initiated: $clusterId" }
    }

    /**
     * Waits for an EMR cluster to reach terminated state.
     *
     * @param clusterId The cluster ID to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    fun waitForClusterTerminated(
        clusterId: ClusterId,
        timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    ) {
        log.info { "Waiting for EMR cluster $clusterId to terminate..." }
        eventBus.emit(Event.Emr.ClusterTerminateWaiting)

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getClusterStatus(clusterId)
            val clusterState = ClusterState.fromValue(status.state)

            if (clusterState in TERMINAL_STATES) {
                log.info { "EMR cluster $clusterId terminated (state: ${status.state})" }
                eventBus.emit(Event.Emr.ClusterTerminated)
                return
            }

            log.debug { "EMR cluster $clusterId state: ${status.state}" }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        error("Timeout waiting for EMR cluster $clusterId to terminate after ${timeoutMs}ms")
    }

    // ==================== Discovery & Bulk Termination ====================

    /**
     * Finds all active EMR clusters that have instances in the specified VPC.
     *
     * EMR clusters don't have a direct VPC association, but their EC2 instances
     * are launched into specific subnets within a VPC. This method finds clusters
     * by checking if their subnet is within the target VPC.
     *
     * @param vpcId The VPC ID to search for clusters in
     * @param subnetIds The subnet IDs in the VPC (used to identify clusters)
     * @return List of EMR cluster IDs with instances in the VPC
     */
    fun findClustersInVpc(
        vpcId: VpcId,
        subnetIds: List<SubnetId>,
    ): List<ClusterId> {
        log.info { "Finding EMR clusters in VPC: $vpcId" }

        if (subnetIds.isEmpty()) {
            log.info { "No subnets in VPC, no EMR clusters to find" }
            return emptyList()
        }

        val clusters = listActiveClusters()

        if (clusters.isEmpty()) {
            log.info { "No active EMR clusters found" }
            return emptyList()
        }

        val matchingClusterIds = mutableListOf<ClusterId>()

        for (cluster in clusters) {
            val clusterSubnetId = getClusterSubnetId(cluster.id())
            if (clusterSubnetId != null && clusterSubnetId in subnetIds) {
                log.info { "Found EMR cluster ${cluster.id()} (${cluster.name()}) in VPC subnet $clusterSubnetId" }
                matchingClusterIds.add(cluster.id())
            }
        }

        log.info { "Found ${matchingClusterIds.size} EMR clusters in VPC: $vpcId" }
        return matchingClusterIds
    }

    /**
     * Finds all active EMR clusters with the specified tag.
     *
     * @param tagKey The tag key to search for
     * @param tagValue The tag value to match
     * @return List of EMR cluster IDs with the matching tag
     */
    fun findClustersByTag(
        tagKey: String,
        tagValue: String,
    ): List<ClusterId> {
        log.info { "Finding EMR clusters with tag $tagKey=$tagValue" }

        val clusters = listActiveClusters()

        val matchingClusterIds =
            clusters
                .filter { cluster ->
                    hasTag(cluster, tagKey, tagValue)
                }.map { it.id() }

        log.info { "Found ${matchingClusterIds.size} EMR clusters with tag $tagKey=$tagValue" }
        return matchingClusterIds
    }

    /**
     * Terminates the specified EMR clusters.
     *
     * @param clusterIds List of cluster IDs to terminate
     */
    fun terminateClusters(clusterIds: List<ClusterId>) {
        if (clusterIds.isEmpty()) {
            log.info { "No EMR clusters to terminate" }
            return
        }

        log.info { "Terminating ${clusterIds.size} EMR clusters: $clusterIds" }
        eventBus.emit(Event.Emr.ClustersTerminating(clusterIds.size))

        val terminateRequest =
            TerminateJobFlowsRequest
                .builder()
                .jobFlowIds(clusterIds)
                .build()

        RetryUtil.withAwsRetry("terminate-emr-clusters") {
            emrClient.terminateJobFlows(terminateRequest)
        }

        log.info { "Initiated termination for EMR clusters: $clusterIds" }
    }

    /**
     * Waits for EMR clusters to reach terminated state.
     *
     * @param clusterIds List of cluster IDs to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     */
    fun waitForClustersTerminated(
        clusterIds: List<ClusterId>,
        timeoutMs: Long = DEFAULT_TERMINATION_TIMEOUT_MS,
    ) {
        if (clusterIds.isEmpty()) {
            return
        }

        log.info { "Waiting for ${clusterIds.size} EMR clusters to terminate..." }
        eventBus.emit(Event.Emr.ClustersTerminateWaiting)

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val allTerminated =
                clusterIds.all { clusterId ->
                    val state = getClusterState(clusterId)
                    state in TERMINAL_STATES
                }

            if (allTerminated) {
                log.info { "All EMR clusters terminated successfully" }
                eventBus.emit(Event.Emr.ClustersTerminated)
                return
            }

            val pending =
                clusterIds.count { clusterId ->
                    val state = getClusterState(clusterId)
                    state !in TERMINAL_STATES
                }
            log.debug { "Still waiting for $pending EMR clusters to terminate..." }

            Thread.sleep(TERMINATION_POLL_INTERVAL_MS)
        }

        throw AwsTimeoutException("Timeout waiting for EMR clusters to terminate after ${timeoutMs}ms")
    }

    /**
     * Lists instance IDs for an EMR cluster, filtered by instance group type.
     *
     * @param clusterId The cluster ID to list instances for
     * @param instanceGroupType The instance group type to filter by (default: MASTER)
     * @return List of EC2 instance IDs matching the filter
     */
    fun listInstances(
        clusterId: ClusterId,
        instanceGroupType: InstanceGroupType = InstanceGroupType.MASTER,
    ): List<String> {
        val request =
            ListInstancesRequest
                .builder()
                .clusterId(clusterId)
                .instanceGroupTypes(instanceGroupType)
                .build()

        val response = RetryUtil.withAwsRetry("list-instances") { emrClient.listInstances(request) }

        return response.instances().mapNotNull { it.ec2InstanceId() }
    }

    // ==================== Private Helpers ====================

    private fun listActiveClusters(): List<ClusterSummary> {
        val listRequest =
            ListClustersRequest
                .builder()
                .clusterStates(ACTIVE_CLUSTER_STATES)
                .build()

        return RetryUtil.withAwsRetry("list-emr-clusters") {
            emrClient.listClusters(listRequest).clusters()
        }
    }

    private fun getClusterState(clusterId: ClusterId): ClusterState {
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val cluster =
            RetryUtil.withAwsRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        return cluster.status().state()
    }

    private fun getClusterSubnetId(clusterId: ClusterId): SubnetId? {
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val cluster =
            RetryUtil.withAwsRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        return cluster.ec2InstanceAttributes()?.ec2SubnetId()
    }

    private fun hasTag(
        cluster: ClusterSummary,
        tagKey: String,
        tagValue: String,
    ): Boolean {
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(cluster.id())
                .build()

        val clusterDetails =
            RetryUtil.withAwsRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        return clusterDetails.tags().any { tag ->
            tag.key() == tagKey && tag.value() == tagValue
        }
    }
}
