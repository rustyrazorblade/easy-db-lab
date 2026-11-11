package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.CreatedInstance
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.providers.aws.InstanceDetails
import com.rustyrazorblade.easydblab.providers.aws.InstanceId
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import com.rustyrazorblade.easydblab.providers.aws.withEc2InstanceRetry
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceStatusRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Capabilities of an EC2 instance type derived from a single `DescribeInstanceTypes` query.
 *
 * Consolidates the two facts the tool needs about an instance type — whether it has local instance
 * store, and which CPU architectures it supports — so both are obtained from one API call.
 *
 * @property hasInstanceStore true if the instance type has local (NVMe) instance store volumes
 * @property supportedArchitectures EC2 `SupportedArchitectures` strings (e.g. `["x86_64"]`)
 */
data class InstanceTypeCapabilities(
    val hasInstanceStore: Boolean,
    val supportedArchitectures: List<String>,
)

/**
 * Service for creating and managing EC2 instances.
 *
 * This service handles the creation of EC2 instances for Cassandra, Stress, and Control nodes.
 * It provides round-robin subnet distribution across availability zones and supports
 * optional EBS volume configuration.
 */
class EC2InstanceService(
    private val ec2Client: Ec2Client,
    private val eventBus: EventBus,
    private val region: String,
    private val pollInterval: Duration = Duration.ofMillis(POLL_INTERVAL_MS),
    private val throttleBackoff: Duration = Duration.ofMillis(Constants.Scale.THROTTLE_BACKOFF_BASE_MS),
) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** Default timeout for waiting on instances to reach running state (10 minutes) */
        const val DEFAULT_RUNNING_TIMEOUT_MS = 10 * 60 * 1000L

        /** Default timeout for waiting on instance status checks (10 minutes) */
        const val DEFAULT_STATUS_CHECK_TIMEOUT_MINUTES = 10

        /** Polling interval for checking instance state */
        const val POLL_INTERVAL_MS = 5000L

        /**
         * Page size for `DescribeInstanceTypes`. The API applies the instance-type filter per page,
         * so a larger page reduces the number of round-trips needed to reach the matching type.
         */
        const val INSTANCE_TYPES_PAGE_SIZE = 100

        /** How many instance IDs a timeout names before it stops listing them. */
        private const val MAX_NAMED_IDS = 20

        /** States an instance never leaves for running: EC2 is ending it, or has ended it. */
        private val ENDED_STATES =
            setOf(
                InstanceStateName.SHUTTING_DOWN,
                InstanceStateName.TERMINATED,
                InstanceStateName.STOPPING,
                InstanceStateName.STOPPED,
            ).map { it.toString() }.toSet()
    }

    /**
     * Creates EC2 instances for a cluster based on the provided configuration.
     *
     * Each ordinal in [InstanceCreationConfig.indexes] is named `<serverType><ordinal>` and goes to
     * subnet `ordinal % subnets`, so a re-run fills exactly the ordinals an earlier `up` left
     * missing, each in its own zone. The instances of one subnet are launched together, in
     * RunInstances calls of at most [Constants.Scale.RUN_INSTANCES_CHUNK], instead of one call per
     * node. A call launches an [OrdinalRun]: the instance with launch index `k` takes ordinal
     * `first + k * stride`, and the call tags its instances with `first` and `stride`, so
     * discovery knows each instance's alias from launch on. A gap in a subnet's ordinals starts a
     * new call. Each instance also gets its own `Name` tag right after launch.
     *
     * [onLaunched] is called for each call's instances as soon as that call returns them, before
     * they are named and before any later call is made, so an `up` that fails part-way, naming
     * included, has recorded every instance it launched.
     *
     * @param config Instance creation configuration
     * @param onLaunched receives every batch of instances the moment they are launched
     * @return List of created instances with their details, in index order
     */
    fun createInstances(
        config: InstanceCreationConfig,
        onLaunched: (List<CreatedInstance>) -> Unit = {},
    ): List<CreatedInstance> {
        log.info {
            "Creating ${config.count} ${config.serverType} instances of type ${config.instanceType}"
        }
        eventBus.emit(Event.Ec2.InstancesCreating(config.count, config.serverType.serverType))

        val instances =
            RunInstancesRequests.launchPlan(config).flatMap { batch ->
                launchBatch(config, batch).also { launched ->
                    onLaunched(launched)
                    nameInstances(launched)
                }
            }

        eventBus.emit(Event.Ec2.InstancesCreated(instances.size, config.serverType.serverType))

        return instances.sortedBy { it.alias.removePrefix(config.serverType.serverType).toIntOrNull() ?: 0 }
    }

    /**
     * Launches one [batch] with a single RunInstances call that asks for exactly its size. A call
     * that EC2 throttles is retried with the same client token, so the retry cannot launch a
     * second set.
     */
    private fun launchBatch(
        config: InstanceCreationConfig,
        batch: LaunchBatch,
    ): List<CreatedInstance> {
        val request = RunInstancesRequests.build(config, batch, UUID.randomUUID().toString())
        val response = withThrottleRetry("run-instances-${batch.aliases.first()}") { ec2Client.runInstances(request) }

        return response
            .instances()
            .sortedBy { it.amiLaunchIndex() ?: 0 }
            .zip(batch.aliases) { instance, alias ->
                log.info { "Created instance ${instance.instanceId()} ($alias) in subnet ${batch.subnetId}" }
                CreatedInstance(
                    instanceId = instance.instanceId(),
                    publicIp = instance.publicIpAddress() ?: "",
                    privateIp = instance.privateIpAddress() ?: "",
                    alias = alias,
                    availabilityZone = instance.placement().availabilityZone(),
                    serverType = config.serverType,
                )
            }
    }

    /**
     * Gives each instance its own `Name` tag (its alias). A batched RunInstances carries one set
     * of tags for all its instances, so the name is set after launch, one CreateTags call per
     * instance, on the throttle retry. An instance EC2 does not see yet is retried the same way.
     */
    private fun nameInstances(instances: List<CreatedInstance>) {
        instances.forEach { instance ->
            val request =
                CreateTagsRequest
                    .builder()
                    .resources(instance.instanceId)
                    .tags(RunInstancesRequests.tag("Name", instance.alias))
                    .build()
            withThrottleRetry("name-${instance.alias}") { ec2Client.createTags(request) }
        }
    }

    private fun <T> withThrottleRetry(
        operationName: String,
        operation: () -> T,
    ): T {
        val retry = Retry.of(operationName, RetryUtil.createEc2ThrottleRetryConfig<T>(throttleBackoff))
        return Retry.decorateSupplier(retry, operation).get()
    }

    /**
     * Waits for instances to reach the running state and have public IPs assigned.
     *
     * Every requested instance must answer: one missing from a response is still waited for,
     * never taken as done. Right after RunInstances EC2 may not know an ID yet
     * (`InvalidInstanceID.NotFound`); that is waited out within [timeoutMs], not counted against a
     * short retry budget. Each poll asks only about the instances not yet running, in requests of
     * at most [Constants.Scale.DESCRIBE_INSTANCES_BATCH] IDs.
     *
     * An instance EC2 is ending or has ended (shutting-down, terminated, stopping, stopped) never
     * runs, so the first poll that sees one fails the wait at once instead of polling to the
     * deadline. A re-run of `up` replaces it: discovery skips terminated instances, so its ordinal
     * is launched again.
     *
     * @param instanceIds List of instance IDs to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return the details of every instance, in the order of [instanceIds]
     * @throws IllegalStateException naming each instance EC2 ended, with its alias and state, or
     *   naming the instances that were not running in time
     */
    fun waitForInstancesRunning(
        instanceIds: List<InstanceId>,
        timeoutMs: Long = DEFAULT_RUNNING_TIMEOUT_MS,
    ): List<InstanceDetails> {
        if (instanceIds.isEmpty()) {
            return emptyList()
        }

        log.info { "Waiting for ${instanceIds.size} instances to reach running state..." }
        eventBus.emit(Event.Ec2.InstancesStartWaiting)

        val ready = mutableMapOf<InstanceId, InstanceDetails>()
        val ended = mutableListOf<InstanceDetails>()
        // An ended instance stops the poll through `done`, not by throwing: the poll retries any exception.
        val pending =
            pollUntil(
                operationName = "instances-running",
                maxAttempts = Int.MAX_VALUE,
                interval = pollInterval,
                deadline = Instant.now().plusMillis(timeoutMs),
                done = { it.isEmpty() || ended.isNotEmpty() },
            ) {
                val waiting = instanceIds.filterNot { it in ready }
                waiting.chunked(Constants.Scale.DESCRIBE_INSTANCES_BATCH).forEach { batch ->
                    describeTolerantly(batch).forEach { instance ->
                        when {
                            instance.state in ENDED_STATES -> ended += instance
                            instance.state == InstanceStateName.RUNNING.toString() && !instance.publicIp.isNullOrEmpty() ->
                                ready[instance.instanceId] = instance
                        }
                    }
                }
                instanceIds.filterNot { it in ready }
            }

        check(ended.isEmpty()) {
            "${ended.size} instances will never run; EC2 ended them: " +
                ended.take(MAX_NAMED_IDS).joinToString(", ") { "${it.instanceId} (${it.name ?: "unnamed"}): ${it.state}" } +
                ". A re-run of up replaces them."
        }
        check(pending.isEmpty()) {
            "Timeout waiting for instances to reach running state after ${timeoutMs}ms; " +
                "${pending.size} not running with a public IP: ${pending.take(MAX_NAMED_IDS).joinToString(", ")}"
        }

        log.info { "All instances running with public IPs" }
        eventBus.emit(Event.Ec2.InstancesRunning)
        return instanceIds.map { ready.getValue(it) }
    }

    /**
     * Describes [ids], treating IDs EC2 does not know yet as not running rather than as an error:
     * when the request fails with `InvalidInstanceID.NotFound`, nothing in it is reported this
     * poll, and the next poll asks again.
     */
    private fun describeTolerantly(ids: List<InstanceId>): List<InstanceDetails> =
        try {
            describeBatch(ids)
        } catch (e: AwsServiceException) {
            if (e.awsErrorDetails()?.errorCode() != Constants.AWS.EC2_INSTANCE_NOT_FOUND) throw e
            log.debug { "Some of ${ids.size} instances are not visible yet; will ask again" }
            emptyList()
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
        eventBus.emit(Event.Ec2.StatusCheckWaiting)

        val waiter =
            Ec2Waiter
                .builder()
                .client(ec2Client)
                .overrideConfiguration { config ->
                    config.waitTimeout(Duration.ofMinutes(timeoutMinutes.toLong()))
                }.build()

        // DescribeInstanceStatus names at most 100 instance IDs per request.
        waiter.use { w ->
            instanceIds.chunked(Constants.Scale.DESCRIBE_INSTANCE_STATUS_BATCH).forEach { batch ->
                w.waitUntilInstanceStatusOk(
                    DescribeInstanceStatusRequest
                        .builder()
                        .instanceIds(batch)
                        .build(),
                )
            }
        }

        log.info { "Instance status checks passed for all ${instanceIds.size} instances" }
        eventBus.emit(Event.Ec2.StatusCheckPassed)
    }

    /**
     * Describes instances by their IDs, in requests of at most
     * [Constants.Scale.DESCRIBE_INSTANCES_BATCH] IDs.
     *
     * @param instanceIds List of instance IDs to describe
     * @return List of instance details
     */
    fun describeInstances(instanceIds: List<InstanceId>): List<InstanceDetails> =
        instanceIds.chunked(Constants.Scale.DESCRIBE_INSTANCES_BATCH).flatMap { batch ->
            withEc2InstanceRetry("describe-instances") { describeBatch(batch) }
        }

    private fun describeBatch(instanceIds: List<InstanceId>): List<InstanceDetails> {
        val request =
            DescribeInstancesRequest
                .builder()
                .instanceIds(instanceIds)
                .build()

        return ec2Client.describeInstances(request).reservations().flatMap { reservation ->
            reservation.instances().map { instance ->
                InstanceDetails(
                    instanceId = instance.instanceId(),
                    state = instance.state().name().toString(),
                    publicIp = instance.publicIpAddress(),
                    privateIp = instance.privateIpAddress(),
                    availabilityZone = instance.placement().availabilityZone(),
                    instanceType = instance.instanceType()?.toString(),
                    name = instance.tags().firstOrNull { it.key() == "Name" }?.value(),
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
     * Queries the capabilities of an EC2 instance type in one `DescribeInstanceTypes` call.
     *
     * The instance type is passed as an `instance-type` filter carrying the raw string, never
     * routed through the SDK's fixed `InstanceType` enum. Instance-type support therefore does not
     * depend on the installed SDK version — any valid EC2 instance type is accepted, including a
     * type newer than the SDK (closes #783; previously such types failed with a `[null]` error).
     *
     * The call reports both whether the type has local instance store and its supported CPU
     * architectures, so both facts are obtained from the same query.
     *
     * `DescribeInstanceTypes` applies the instance-type filter **per page**, so a matching type can
     * sit behind an empty page that still carries a `nextToken`. This follows `nextToken` across
     * pages and only reports "not found" once every page is exhausted with no match — reading only
     * the first page would misreport a common type (e.g. `i4i.xlarge`) as unknown.
     *
     * @param instanceType EC2 instance type (e.g., "i3.xlarge", "c5.2xlarge")
     * @return the instance type's capabilities
     * @throws IllegalStateException if the type is unknown in the region (no match across all pages)
     */
    fun describeInstanceType(instanceType: String): InstanceTypeCapabilities {
        var nextToken: String? = null
        do {
            val request =
                DescribeInstanceTypesRequest
                    .builder()
                    .filters(
                        Filter
                            .builder()
                            .name("instance-type")
                            .values(instanceType)
                            .build(),
                    ).maxResults(INSTANCE_TYPES_PAGE_SIZE)
                    .nextToken(nextToken)
                    .build()

            val response =
                withAwsRetry("describe-instance-type-$instanceType") {
                    ec2Client.describeInstanceTypes(request)
                }

            val typeInfo = response.instanceTypes().firstOrNull()
            if (typeInfo != null) {
                return InstanceTypeCapabilities(
                    hasInstanceStore = typeInfo.instanceStorageSupported() == true,
                    supportedArchitectures =
                        typeInfo.processorInfo()?.supportedArchitecturesAsStrings().orEmpty(),
                )
            }
            nextToken = response.nextToken()
        } while (!nextToken.isNullOrEmpty())

        error("Instance type '$instanceType' not found in region $region")
    }

    /**
     * Finds all running and pending instances belonging to a cluster by ClusterId tag.
     *
     * Used to discover existing infrastructure before creating new instances, enabling idempotent
     * `up` operations and cluster expansion. A pending instance counts: it is one an interrupted
     * `up` launched, and launching another in its place would duplicate its alias. Every page of
     * the result is read; at 1,000 nodes the first page holds only part of the cluster.
     *
     * @param clusterId The ClusterId tag value (UUID)
     * @return Map of ServerType to list of discovered instances (running or pending)
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
                        .values(InstanceStateName.RUNNING.toString(), InstanceStateName.PENDING.toString())
                        .build(),
                ).build()

        val reservations =
            withAwsRetry("describe-instances-by-cluster") {
                ec2Client.describeInstancesPaginator(request).reservations().toList()
            }

        val discoveredInstances =
            reservations.flatMap { reservation ->
                reservation.instances().mapNotNull { instance ->
                    val tags = instance.tags().associate { it.key() to it.value() }

                    val serverTypeStr = tags["ServerType"]
                    // An instance whose Name tag never landed is named from its launch tags.
                    val alias =
                        tags["Name"]
                            ?: OrdinalRun.ordinalOf(tags, instance.amiLaunchIndex())?.let { "$serverTypeStr$it" }

                    if (serverTypeStr == null || alias == null) {
                        log.warn {
                            "Instance ${instance.instanceId()} missing required tags (ServerType=$serverTypeStr, alias=$alias)"
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

        log.info { "Discovered ${discoveredInstances.size} running or pending instances for cluster $clusterId" }
        result.forEach { (serverType, instances) ->
            log.debug { "  $serverType: ${instances.size} instances" }
        }

        return result
    }
}
