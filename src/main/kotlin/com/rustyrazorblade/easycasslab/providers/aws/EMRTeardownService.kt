package com.rustyrazorblade.easycasslab.providers.aws

import com.rustyrazorblade.easycasslab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.ClusterState
import software.amazon.awssdk.services.emr.model.ClusterSummary
import software.amazon.awssdk.services.emr.model.DescribeClusterRequest
import software.amazon.awssdk.services.emr.model.ListClustersRequest
import software.amazon.awssdk.services.emr.model.TerminateJobFlowsRequest

/**
 * Service for managing EMR cluster teardown operations.
 *
 * This service discovers EMR clusters by their subnet association and handles
 * their termination as part of VPC infrastructure cleanup.
 */
class EMRTeardownService(
    private val emrClient: EmrClient,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** EMR cluster states that indicate the cluster is active and can be terminated */
        private val ACTIVE_CLUSTER_STATES =
            listOf(
                ClusterState.STARTING,
                ClusterState.BOOTSTRAPPING,
                ClusterState.RUNNING,
                ClusterState.WAITING,
            )

        /** EMR cluster states that indicate the cluster is terminated */
        private val TERMINATED_STATES =
            listOf(
                ClusterState.TERMINATED,
                ClusterState.TERMINATED_WITH_ERRORS,
            )

        /** Default timeout for waiting on cluster termination (15 minutes) */
        const val DEFAULT_TERMINATION_TIMEOUT_MS = 15 * 60 * 1000L

        /** Polling interval for checking cluster state */
        const val POLL_INTERVAL_MS = 10_000L
    }

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

        // List all active clusters
        val listRequest =
            ListClustersRequest
                .builder()
                .clusterStates(ACTIVE_CLUSTER_STATES)
                .build()

        val clusters =
            withRetry("list-emr-clusters") {
                emrClient.listClusters(listRequest).clusters()
            }

        if (clusters.isEmpty()) {
            log.info { "No active EMR clusters found" }
            return emptyList()
        }

        // Filter to clusters that are in one of the VPC's subnets
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

        val listRequest =
            ListClustersRequest
                .builder()
                .clusterStates(ACTIVE_CLUSTER_STATES)
                .build()

        val clusters =
            withRetry("list-emr-clusters") {
                emrClient.listClusters(listRequest).clusters()
            }

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
        outputHandler.handleMessage("Terminating ${clusterIds.size} EMR clusters...")

        val terminateRequest =
            TerminateJobFlowsRequest
                .builder()
                .jobFlowIds(clusterIds)
                .build()

        withRetry("terminate-emr-clusters") {
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
        outputHandler.handleMessage("Waiting for EMR clusters to terminate...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val allTerminated =
                clusterIds.all { clusterId ->
                    val state = getClusterState(clusterId)
                    state in TERMINATED_STATES
                }

            if (allTerminated) {
                log.info { "All EMR clusters terminated successfully" }
                outputHandler.handleMessage("All EMR clusters terminated")
                return
            }

            val pending =
                clusterIds.count { clusterId ->
                    val state = getClusterState(clusterId)
                    state !in TERMINATED_STATES
                }
            log.debug { "Still waiting for $pending EMR clusters to terminate..." }

            Thread.sleep(POLL_INTERVAL_MS)
        }

        error("Timeout waiting for EMR clusters to terminate after ${timeoutMs}ms")
    }

    /**
     * Gets the current state of an EMR cluster.
     *
     * @param clusterId The cluster ID
     * @return The cluster state
     */
    private fun getClusterState(clusterId: ClusterId): ClusterState {
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val cluster =
            withRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        return cluster.status().state()
    }

    /**
     * Gets the subnet ID for an EMR cluster.
     *
     * @param clusterId The cluster ID
     * @return The subnet ID if configured, null otherwise
     */
    private fun getClusterSubnetId(clusterId: ClusterId): SubnetId? {
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(clusterId)
                .build()

        val cluster =
            withRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        // EMR clusters have their subnet in Ec2InstanceAttributes
        return cluster.ec2InstanceAttributes()?.ec2SubnetId()
    }

    /**
     * Checks if a cluster has the specified tag.
     *
     * @param cluster The cluster summary
     * @param tagKey The tag key to check
     * @param tagValue The tag value to match
     * @return True if the cluster has the matching tag
     */
    private fun hasTag(
        cluster: ClusterSummary,
        tagKey: String,
        tagValue: String,
    ): Boolean {
        // ClusterSummary doesn't include tags, need to describe the cluster
        val describeRequest =
            DescribeClusterRequest
                .builder()
                .clusterId(cluster.id())
                .build()

        val clusterDetails =
            withRetry("describe-emr-cluster") {
                emrClient.describeCluster(describeRequest).cluster()
            }

        return clusterDetails.tags().any { tag ->
            tag.key() == tagKey && tag.value() == tagValue
        }
    }

    /**
     * Executes an EMR operation with retry logic.
     */
    private fun <T> withRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retryConfig = RetryUtil.createAwsRetryConfig<T>()
        val retry = Retry.of(operationName, retryConfig)
        return Retry.decorateSupplier(retry, operation).get()
    }
}
