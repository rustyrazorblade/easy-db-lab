package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.Application
import software.amazon.awssdk.services.emr.model.ClusterState
import software.amazon.awssdk.services.emr.model.DescribeClusterRequest
import software.amazon.awssdk.services.emr.model.InstanceGroupConfig
import software.amazon.awssdk.services.emr.model.InstanceRoleType
import software.amazon.awssdk.services.emr.model.JobFlowInstancesConfig
import software.amazon.awssdk.services.emr.model.RunJobFlowRequest
import software.amazon.awssdk.services.emr.model.Tag
import software.amazon.awssdk.services.emr.model.TerminateJobFlowsRequest

/**
 * Service for creating and managing EMR clusters.
 *
 * This service handles the lifecycle of EMR clusters for Spark job execution,
 * including creation, status monitoring, and termination.
 */
class EMRService(
    private val emrClient: EmrClient,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Default timeout for waiting on cluster to reach WAITING state (30 minutes) */
        const val DEFAULT_READY_TIMEOUT_MS = 30 * 60 * 1000L

        /** Polling interval for checking cluster state */
        const val POLL_INTERVAL_MS = 15_000L

        // Use shared EMR cluster states from EMRClusterStates
        private val READY_STATES = EMRClusterStates.READY_STATES.map { ClusterState.fromValue(it) }.toSet()
        private val TERMINAL_STATES = EMRClusterStates.TERMINAL_STATES.map { ClusterState.fromValue(it) }.toSet()
        private val STARTING_STATES = EMRClusterStates.STARTING_STATES.map { ClusterState.fromValue(it) }.toSet()
    }

    /**
     * Creates an EMR cluster for Spark job execution.
     *
     * @param config EMR cluster configuration
     * @return Result containing the cluster ID and details
     */
    fun createCluster(config: EMRClusterConfig): EMRClusterResult {
        log.info { "Creating EMR cluster: ${config.clusterName}" }
        outputHandler.handleMessage("Creating EMR cluster: ${config.clusterName}...")

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

        val request = requestBuilder.build()

        val response = RetryUtil.withAwsRetry("run-job-flow") { emrClient.runJobFlow(request) }
        val clusterId = response.jobFlowId()

        log.info { "EMR cluster creation initiated: $clusterId" }
        outputHandler.handleMessage("EMR cluster initiated: $clusterId")

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
        outputHandler.handleMessage("Waiting for EMR cluster to start (this may take 10-15 minutes)...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getClusterStatus(clusterId)

            val clusterState = ClusterState.fromValue(status.state)

            when {
                clusterState in READY_STATES -> {
                    log.info { "EMR cluster $clusterId is ready (state: ${status.state})" }
                    outputHandler.handleMessage("EMR cluster is ready")

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
        outputHandler.handleMessage("Terminating EMR cluster: $clusterId...")

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
        outputHandler.handleMessage("Waiting for EMR cluster to terminate...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getClusterStatus(clusterId)
            val clusterState = ClusterState.fromValue(status.state)

            if (clusterState in TERMINAL_STATES) {
                log.info { "EMR cluster $clusterId terminated (state: ${status.state})" }
                outputHandler.handleMessage("EMR cluster terminated")
                return
            }

            log.debug { "EMR cluster $clusterId state: ${status.state}" }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        error("Timeout waiting for EMR cluster $clusterId to terminate after ${timeoutMs}ms")
    }
}
