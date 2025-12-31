package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.HttpTokensState
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.InstanceType
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import software.amazon.awssdk.services.ec2.model.VolumeType
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter
import java.time.Duration

/**
 * Service for creating and managing EC2 instances.
 *
 * This service handles the creation of EC2 instances for Cassandra, Stress, and Control nodes.
 * It provides round-robin subnet distribution across availability zones and supports
 * optional EBS volume configuration.
 */
class EC2InstanceService(
    private val ec2Client: Ec2Client,
    private val outputHandler: OutputHandler,
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Default timeout for waiting on instances to reach running state (10 minutes) */
        const val DEFAULT_RUNNING_TIMEOUT_MS = 10 * 60 * 1000L

        /** Default timeout for waiting on instance status checks (10 minutes) */
        const val DEFAULT_STATUS_CHECK_TIMEOUT_MINUTES = 10

        /** Polling interval for checking instance state */
        const val POLL_INTERVAL_MS = 5000L
    }

    /**
     * Creates EC2 instances for a cluster based on the provided configuration.
     *
     * Instances are distributed across subnets in a round-robin fashion to ensure
     * even distribution across availability zones.
     *
     * @param config Instance creation configuration
     * @return List of created instances with their details
     */
    fun createInstances(config: InstanceCreationConfig): List<CreatedInstance> {
        log.info {
            "Creating ${config.count} ${config.serverType} instances of type ${config.instanceType}"
        }
        outputHandler.handleMessage(
            "Creating ${config.count} ${config.serverType.serverType} instance(s)...",
        )

        val instances = mutableListOf<CreatedInstance>()

        for (i in 0 until config.count) {
            val instanceIndex = config.startIndex + i
            // Round-robin subnet distribution across AZs (using instanceIndex for proper continuation)
            val subnetId = config.subnetIds[instanceIndex % config.subnetIds.size]
            val alias = "${config.serverType.serverType}$instanceIndex"

            val instance = createSingleInstance(config, subnetId, alias, instanceIndex)
            instances.add(instance)

            log.info { "Created instance ${instance.instanceId} ($alias) in subnet $subnetId" }
        }

        outputHandler.handleMessage(
            "Created ${instances.size} ${config.serverType.serverType} instance(s)",
        )

        return instances
    }

    /**
     * Creates a single EC2 instance.
     */
    private fun createSingleInstance(
        config: InstanceCreationConfig,
        subnetId: SubnetId,
        alias: String,
        index: Int,
    ): CreatedInstance {
        val tagSpec = buildTagSpecification(config, alias)
        val blockDeviceMappings = buildBlockDeviceMappings(config.ebsConfig)

        val requestBuilder =
            RunInstancesRequest
                .builder()
                .imageId(config.amiId)
                .instanceType(InstanceType.fromValue(config.instanceType))
                .minCount(1)
                .maxCount(1)
                .keyName(config.keyName)
                .securityGroupIds(config.securityGroupId)
                .subnetId(subnetId)
                .iamInstanceProfile(
                    IamInstanceProfileSpecification
                        .builder()
                        .name(config.iamInstanceProfile)
                        .build(),
                ).tagSpecifications(tagSpec)

        if (blockDeviceMappings.isNotEmpty()) {
            requestBuilder.blockDeviceMappings(blockDeviceMappings)
        }

        // Control nodes need hop limit of 2 so containers (K3s pods) can access IMDS
        if (config.serverType == ServerType.Control) {
            requestBuilder.metadataOptions(
                InstanceMetadataOptionsRequest
                    .builder()
                    .httpTokens(HttpTokensState.REQUIRED)
                    .httpPutResponseHopLimit(2)
                    .httpEndpoint("enabled")
                    .build(),
            )
        }

        val request = requestBuilder.build()

        val response = RetryUtil.withAwsRetry("run-instance-$alias") { ec2Client.runInstances(request) }
        val instance = response.instances().first()

        return CreatedInstance(
            instanceId = instance.instanceId(),
            publicIp = instance.publicIpAddress() ?: "",
            privateIp = instance.privateIpAddress() ?: "",
            alias = alias,
            availabilityZone = instance.placement().availabilityZone(),
            serverType = config.serverType,
        )
    }

    /**
     * Builds tag specification for the instance.
     */
    private fun buildTagSpecification(
        config: InstanceCreationConfig,
        alias: String,
    ): TagSpecification {
        val allTags =
            config.tags +
                mapOf(
                    "Name" to alias,
                    "Cluster" to config.clusterName,
                    "ServerType" to config.serverType.serverType,
                )

        val tags =
            allTags.map { (key, value) ->
                Tag
                    .builder()
                    .key(key)
                    .value(value)
                    .build()
            }

        return TagSpecification
            .builder()
            .resourceType(ResourceType.INSTANCE)
            .tags(tags)
            .build()
    }

    /**
     * Builds block device mappings for EBS volumes.
     */
    private fun buildBlockDeviceMappings(ebsConfig: EBSConfig?): List<BlockDeviceMapping> {
        if (ebsConfig == null) {
            return emptyList()
        }

        val ebsBuilder =
            EbsBlockDevice
                .builder()
                .volumeSize(ebsConfig.volumeSize)
                .volumeType(VolumeType.fromValue(ebsConfig.volumeType))
                .deleteOnTermination(true)

        // Add IOPS for gp3, io1, io2
        if (ebsConfig.iops != null && ebsConfig.volumeType in listOf("gp3", "io1", "io2")) {
            ebsBuilder.iops(ebsConfig.iops)
        }

        // Add throughput for gp3
        if (ebsConfig.throughput != null && ebsConfig.volumeType == "gp3") {
            ebsBuilder.throughput(ebsConfig.throughput)
        }

        return listOf(
            BlockDeviceMapping
                .builder()
                .deviceName(ebsConfig.deviceName)
                .ebs(ebsBuilder.build())
                .build(),
        )
    }

    /**
     * Waits for instances to reach the running state and have public IPs assigned.
     *
     * @param instanceIds List of instance IDs to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return Updated list of instances with their public IPs
     */
    fun waitForInstancesRunning(
        instanceIds: List<InstanceId>,
        timeoutMs: Long = DEFAULT_RUNNING_TIMEOUT_MS,
    ): List<InstanceDetails> {
        if (instanceIds.isEmpty()) {
            return emptyList()
        }

        log.info { "Waiting for ${instanceIds.size} instances to reach running state..." }
        outputHandler.handleMessage("Waiting for instances to start...")

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val details = describeInstances(instanceIds)

            val allRunningWithPublicIp =
                details.all { instance ->
                    instance.state == InstanceStateName.RUNNING.toString() &&
                        !instance.publicIp.isNullOrEmpty()
                }

            if (allRunningWithPublicIp) {
                log.info { "All instances running with public IPs" }
                outputHandler.handleMessage("All instances running")
                return details
            }

            val running = details.count { it.state == InstanceStateName.RUNNING.toString() }
            val withPublicIp = details.count { !it.publicIp.isNullOrEmpty() }
            log.debug {
                "Waiting: $running/${details.size} running, $withPublicIp/${details.size} with public IP"
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }

        error("Timeout waiting for instances to reach running state after ${timeoutMs}ms")
    }

    /**
     * Waits for instance status checks to pass (both system and instance reachability).
     *
     * This ensures the OS has fully booted and networking is configured before attempting SSH.
     * The EC2 "running" state only indicates the instance has launched, not that the OS is ready.
     * Instance status checks verify:
     * - System status: AWS infrastructure (network, host, power) is healthy
     * - Instance status: OS has booted, networking is configured, instance is reachable
     *
     * @param instanceIds List of instance IDs to wait for
     * @param timeoutMinutes Maximum time to wait in minutes (default 10)
     */
    fun waitForInstanceStatusOk(
        instanceIds: List<InstanceId>,
        timeoutMinutes: Int = DEFAULT_STATUS_CHECK_TIMEOUT_MINUTES,
    ) {
        if (instanceIds.isEmpty()) {
            return
        }

        log.info { "Waiting for instance status checks on ${instanceIds.size} instances..." }
        outputHandler.handleMessage("Waiting for instance status checks to pass...")

        val waiter =
            Ec2Waiter
                .builder()
                .client(ec2Client)
                .overrideConfiguration { config ->
                    config.waitTimeout(Duration.ofMinutes(timeoutMinutes.toLong()))
                }.build()

        val request =
            software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusRequest
                .builder()
                .instanceIds(instanceIds)
                .build()

        waiter.use { w ->
            w.waitUntilInstanceStatusOk(request)
        }

        log.info { "Instance status checks passed for all ${instanceIds.size} instances" }
        outputHandler.handleMessage("Instance status checks passed")
    }

    /**
     * Describes instances by their IDs.
     *
     * @param instanceIds List of instance IDs to describe
     * @return List of instance details
     */
    fun describeInstances(instanceIds: List<InstanceId>): List<InstanceDetails> {
        if (instanceIds.isEmpty()) {
            return emptyList()
        }

        val request =
            DescribeInstancesRequest
                .builder()
                .instanceIds(instanceIds)
                .build()

        val reservations =
            RetryUtil.withEc2InstanceRetry("describe-instances") {
                ec2Client.describeInstances(request).reservations()
            }

        return reservations.flatMap { reservation ->
            reservation.instances().map { instance ->
                InstanceDetails(
                    instanceId = instance.instanceId(),
                    state = instance.state().name().toString(),
                    publicIp = instance.publicIpAddress(),
                    privateIp = instance.privateIpAddress(),
                    availabilityZone = instance.placement().availabilityZone(),
                )
            }
        }
    }

    /**
     * Updates created instances with their final public IPs after they reach running state.
     *
     * @param instances List of created instances to update
     * @return Updated list of instances with public IPs
     */
    fun updateInstanceIps(instances: List<CreatedInstance>): List<CreatedInstance> {
        if (instances.isEmpty()) {
            return emptyList()
        }

        val details = describeInstances(instances.map { it.instanceId })
        val detailsMap = details.associateBy { it.instanceId }

        return instances.map { instance ->
            val detail = detailsMap[instance.instanceId]
            if (detail != null) {
                instance.copy(
                    publicIp = detail.publicIp ?: instance.publicIp,
                    privateIp = detail.privateIp ?: instance.privateIp,
                )
            } else {
                instance
            }
        }
    }

    /**
     * Finds all running instances belonging to a cluster by ClusterId tag.
     *
     * Used to discover existing infrastructure before creating new instances,
     * enabling idempotent `up` operations and future cluster expansion.
     *
     * @param clusterId The ClusterId tag value (UUID)
     * @return Map of ServerType to list of discovered instances (running only)
     */
    fun findInstancesByClusterId(clusterId: String): Map<ServerType, List<DiscoveredInstance>> {
        log.info { "Discovering instances for cluster: $clusterId" }

        val request =
            DescribeInstancesRequest
                .builder()
                .filters(
                    Filter
                        .builder()
                        .name("tag:ClusterId")
                        .values(clusterId)
                        .build(),
                    Filter
                        .builder()
                        .name("instance-state-name")
                        .values(InstanceStateName.RUNNING.toString())
                        .build(),
                ).build()

        val reservations =
            RetryUtil.withAwsRetry("describe-instances-by-cluster") {
                ec2Client.describeInstances(request).reservations()
            }

        val discoveredInstances =
            reservations.flatMap { reservation ->
                reservation.instances().mapNotNull { instance ->
                    val tags = instance.tags().associate { it.key() to it.value() }

                    val serverTypeStr = tags["ServerType"]
                    val alias = tags["Name"]

                    if (serverTypeStr == null || alias == null) {
                        log.warn {
                            "Instance ${instance.instanceId()} missing required tags (ServerType=$serverTypeStr, Name=$alias)"
                        }
                        return@mapNotNull null
                    }

                    val serverType =
                        ServerType.entries.find { it.serverType == serverTypeStr }
                            ?: run {
                                log.warn { "Unknown ServerType: $serverTypeStr for instance ${instance.instanceId()}" }
                                return@mapNotNull null
                            }

                    DiscoveredInstance(
                        instanceId = instance.instanceId(),
                        publicIp = instance.publicIpAddress(),
                        privateIp = instance.privateIpAddress(),
                        alias = alias,
                        availabilityZone = instance.placement().availabilityZone(),
                        serverType = serverType,
                        state = instance.state().name().toString(),
                    )
                }
            }

        val result = discoveredInstances.groupBy { it.serverType }

        log.info { "Discovered ${discoveredInstances.size} running instances for cluster $clusterId" }
        result.forEach { (serverType, instances) ->
            log.debug { "  $serverType: ${instances.size} instances" }
        }

        return result
    }
}
