package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.EMRClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.OpenSearchClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.CreatedInstance
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.services.aws.DomainState
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceSpec
import com.rustyrazorblade.easydblab.services.aws.OpenSearchDomainConfig
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Infrastructure parameters for instance creation.
 *
 * The AMI is not part of this context — it is resolved per architecture and carried on each
 * [InstanceSpec], so mixed-architecture clusters attach the correct image per group.
 *
 * @property keyName SSH key pair name
 * @property securityGroupId Security group ID
 * @property subnetIds Available subnet IDs
 * @property tags Tags to apply to instances
 * @property clusterName Name of the cluster
 */
data class InfrastructureContext(
    val keyName: String,
    val securityGroupId: String,
    val subnetIds: List<String>,
    val tags: Map<String, String>,
    val clusterName: String,
)

/**
 * The launch of [InstanceSpec]'s missing instances: the ordinals in [InstanceSpec.missingIndexes].
 * `up`'s pre-launch dry run asks AWS about exactly this request.
 */
fun InstanceSpec.creationConfig(infraContext: InfrastructureContext): InstanceCreationConfig =
    InstanceCreationConfig(
        serverType = serverType,
        indexes = missingIndexes,
        instanceType = instanceType,
        amiId = amiId,
        keyName = infraContext.keyName,
        securityGroupId = infraContext.securityGroupId,
        subnetIds = infraContext.subnetIds,
        iamInstanceProfile = Constants.AWS.Roles.EC2_INSTANCE_ROLE,
        ebsConfig = ebsConfig,
        tags = infraContext.tags,
        clusterName = infraContext.clusterName,
    )

/** The launches of every server type that has instances missing, as provisioning will make them. */
fun InstanceProvisioningConfig.launches(): List<InstanceCreationConfig> =
    specs.filter { it.neededCount > 0 }.map { it.creationConfig(toInfrastructureContext()) }

/** The network, key and tags every launch of [InstanceProvisioningConfig] shares. */
fun InstanceProvisioningConfig.toInfrastructureContext() =
    InfrastructureContext(
        keyName = userConfig.keyName,
        securityGroupId = securityGroupId,
        subnetIds = subnetIds,
        tags = tags,
        clusterName = clusterName,
    )

/**
 * Result of cluster provisioning operation.
 *
 * @property hosts Created hosts by server type
 * @property errors Errors encountered during provisioning, keyed by resource name
 * @property emrCluster Created EMR cluster state, if enabled
 * @property openSearchDomain Created OpenSearch domain state, if enabled
 */
data class ProvisioningResult(
    val hosts: Map<ServerType, List<ClusterHost>>,
    val errors: Map<String, Exception>,
    val emrCluster: EMRClusterState? = null,
    val openSearchDomain: OpenSearchClusterState? = null,
)

/**
 * Configuration for provisioning instances.
 *
 * @property specs Instance specifications for each server type, each carrying its own AMI
 * @property securityGroupId Security group for instances
 * @property subnetIds Available subnet IDs
 * @property tags Tags to apply to instances
 * @property clusterName Name of the cluster
 * @property userConfig User configuration with key name
 */
data class InstanceProvisioningConfig(
    val specs: List<InstanceSpec>,
    val securityGroupId: String,
    val subnetIds: List<String>,
    val tags: Map<String, String>,
    val clusterName: String,
    val userConfig: User,
)

/**
 * Configuration for optional services (EMR, OpenSearch).
 *
 * @property initConfig Full initialization configuration
 * @property subnetId Subnet ID for services
 * @property securityGroupId Security group ID (for OpenSearch)
 * @property tags Tags to apply to resources
 * @property clusterState Current cluster state (for S3 path)
 */
data class OptionalServicesConfig(
    val initConfig: InitConfig,
    val subnetId: String,
    val securityGroupId: String,
    val tags: Map<String, String>,
    val clusterState: ClusterState,
)

/**
 * Callbacks for provisioning lifecycle events.
 *
 * @property onHostsCreated Called when hosts are created for a server type
 * @property onEmrCreated Called when an EMR cluster is created
 * @property onOpenSearchCreated Called when an OpenSearch domain is created
 */
data class ProvisioningCallbacks(
    val onHostsCreated: (ServerType, List<ClusterHost>) -> Unit,
    val onEmrCreated: (EMRClusterState) -> Unit,
    val onOpenSearchCreated: (OpenSearchClusterState) -> Unit,
)

/**
 * Thread synchronization context for parallel provisioning.
 *
 * @property stateLock Lock object for state synchronization
 * @property threadErrors Concurrent map for collecting thread errors
 * @property callbacks Provisioning lifecycle callbacks
 */
private data class ThreadContext(
    val stateLock: Any,
    val threadErrors: ConcurrentHashMap<String, Exception>,
    val callbacks: ProvisioningCallbacks,
    @Volatile var emrCluster: EMRClusterState? = null,
    @Volatile var openSearchDomain: OpenSearchClusterState? = null,
)

/**
 * Service for provisioning cluster infrastructure in parallel.
 *
 * This service handles the parallel creation of EC2 instances, EMR clusters,
 * and OpenSearch domains with proper error collection and state synchronization.
 */
interface ClusterProvisioningService {
    /**
     * Provisions all cluster instances in parallel.
     *
     * @param config Instance provisioning configuration
     * @param existingHosts Currently existing hosts by server type
     * @param onHostsCreated Callback when hosts are created (for state updates)
     * @return Result with created hosts and any errors
     */
    fun provisionInstances(
        config: InstanceProvisioningConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
        onHostsCreated: (ServerType, List<ClusterHost>) -> Unit,
    ): ProvisioningResult

    /**
     * Provisions all cluster infrastructure including optional services.
     *
     * @param instanceConfig Instance provisioning configuration
     * @param servicesConfig Optional services configuration (EMR, OpenSearch)
     * @param existingHosts Currently existing hosts by server type
     * @param callbacks Callbacks for provisioning lifecycle events
     * @return Result with created resources and any errors
     */
    fun provisionAll(
        instanceConfig: InstanceProvisioningConfig,
        servicesConfig: OptionalServicesConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
        callbacks: ProvisioningCallbacks,
    ): ProvisioningResult
}

/**
 * [existing] with [updates] applied by alias: a host already present is replaced by its update,
 * and a new one is added. A host is reported once at launch and again with its final IPs, so
 * merging by alias keeps one entry per instance. The result is in ordinal order
 * ([ServerType.ordinalOf]), so a host that fills a gap a partial launch left (`db1` between
 * `db0` and `db2`) takes its place instead of going last.
 */
internal fun mergeByAlias(
    serverType: ServerType,
    existing: List<ClusterHost>?,
    updates: List<ClusterHost>,
): List<ClusterHost> {
    val byAlias = updates.associateBy { it.alias }
    val replaced = existing.orEmpty().map { byAlias[it.alias] ?: it }
    val known = replaced.map { it.alias }.toSet()
    return (replaced + updates.filter { it.alias !in known })
        .sortedBy { serverType.ordinalOf(it.alias) ?: Int.MAX_VALUE }
}

/**
 * Default implementation of ClusterProvisioningService.
 */
class DefaultClusterProvisioningService(
    private val ec2InstanceService: EC2InstanceService,
    private val emrProvisioningService: EMRProvisioningService,
    private val openSearchService: OpenSearchService,
    private val aws: AWS,
    private val user: User,
    private val eventBus: EventBus,
) : ClusterProvisioningService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun provisionInstances(
        config: InstanceProvisioningConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
        onHostsCreated: (ServerType, List<ClusterHost>) -> Unit,
    ): ProvisioningResult {
        val allHosts = existingHosts.toMutableMap()
        val threadErrors = ConcurrentHashMap<String, Exception>()
        val stateLock = Any()

        // Log messages for existing instances that don't need creation
        config.specs
            .filter { it.neededCount <= 0 && it.configuredCount > 0 && it.existingCount > 0 }
            .forEach { spec ->
                eventBus.emit(Event.Ec2.ExistingInstancesFound(spec.serverType.name, spec.existingCount))
            }

        // Build and run instance creation threads
        val threads =
            config.specs
                .filter { it.neededCount > 0 }
                .map { spec ->
                    thread(start = true, name = "create-${spec.serverType.name}") {
                        try {
                            val infraContext = config.toInfrastructureContext()
                            createInstancesForType(spec, infraContext) { hosts ->
                                synchronized(stateLock) {
                                    allHosts[spec.serverType] = mergeByAlias(spec.serverType, allHosts[spec.serverType], hosts)
                                    onHostsCreated(spec.serverType, hosts)
                                }
                            }
                        } catch (e: Exception) {
                            log.error(e) { "Failed to create ${spec.serverType.name} instances" }
                            threadErrors["${spec.serverType.name} instances"] = e
                        }
                    }
                }

        // Wait for all threads to complete
        threads.forEach { it.join() }

        return ProvisioningResult(
            hosts = allHosts.toMap(),
            errors = threadErrors.toMap(),
        )
    }

    override fun provisionAll(
        instanceConfig: InstanceProvisioningConfig,
        servicesConfig: OptionalServicesConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
        callbacks: ProvisioningCallbacks,
    ): ProvisioningResult {
        val allHosts = existingHosts.toMutableMap()
        val ctx =
            ThreadContext(
                stateLock = Any(),
                threadErrors = ConcurrentHashMap(),
                callbacks = callbacks,
            )
        logExistingInstances(instanceConfig)

        val instancesReady = CountDownLatch(1)
        val serviceThreads = mutableListOf<Thread>()

        val instanceThreads =
            launchInstanceCreationThreads(instanceConfig, allHosts, ctx)

        serviceThreads.add(
            thread(start = true, name = "instance-coordinator") {
                instanceThreads.forEach { it.join() }
                instancesReady.countDown()
            },
        )

        launchEmrThread(servicesConfig, instanceConfig, instancesReady, ctx)
            ?.let { serviceThreads.add(it) }

        launchOpenSearchThread(servicesConfig, ctx)?.let { thread ->
            serviceThreads.add(thread)
        }

        serviceThreads.forEach { it.join() }

        return ProvisioningResult(
            hosts = allHosts.toMap(),
            errors = ctx.threadErrors.toMap(),
            emrCluster = ctx.emrCluster,
            openSearchDomain = ctx.openSearchDomain,
        )
    }

    private fun logExistingInstances(instanceConfig: InstanceProvisioningConfig) {
        instanceConfig.specs
            .filter { it.neededCount <= 0 && it.configuredCount > 0 && it.existingCount > 0 }
            .forEach { spec ->
                eventBus.emit(Event.Ec2.ExistingInstancesFound(spec.serverType.name, spec.existingCount))
            }
    }

    private fun launchInstanceCreationThreads(
        instanceConfig: InstanceProvisioningConfig,
        allHosts: MutableMap<ServerType, List<ClusterHost>>,
        ctx: ThreadContext,
    ): List<Thread> =
        instanceConfig.specs
            .filter { it.neededCount > 0 }
            .map { spec ->
                thread(start = true, name = "create-${spec.serverType.name}") {
                    try {
                        val infraContext = instanceConfig.toInfrastructureContext()
                        createInstancesForType(spec, infraContext) { hosts ->
                            synchronized(ctx.stateLock) {
                                allHosts[spec.serverType] = mergeByAlias(spec.serverType, allHosts[spec.serverType], hosts)
                                ctx.callbacks.onHostsCreated(spec.serverType, hosts)
                            }
                        }
                    } catch (e: Exception) {
                        log.error(e) { "Failed to create ${spec.serverType.name} instances" }
                        ctx.threadErrors["${spec.serverType.name} instances"] = e
                    }
                }
            }

    private fun launchEmrThread(
        servicesConfig: OptionalServicesConfig,
        instanceConfig: InstanceProvisioningConfig,
        instancesReady: CountDownLatch,
        ctx: ThreadContext,
    ): Thread? {
        if (!servicesConfig.initConfig.sparkEnabled) return null
        if (servicesConfig.clusterState.emrCluster != null) {
            eventBus.emit(Event.Emr.ClusterAlreadyExists)
            return null
        }
        return thread(start = true, name = "create-EMR") {
            try {
                instancesReady.await()
                val cluster =
                    emrProvisioningService.provisionEmrCluster(
                        EmrClusterProvisioningConfig(
                            clusterName = servicesConfig.initConfig.name,
                            masterInstanceType = servicesConfig.initConfig.sparkMasterInstanceType,
                            workerInstanceType = servicesConfig.initConfig.sparkWorkerInstanceType,
                            workerCount = servicesConfig.initConfig.sparkWorkerCount,
                            releaseLabel = servicesConfig.initConfig.sparkReleaseLabel,
                            subnetId = servicesConfig.subnetId,
                            securityGroupId = servicesConfig.securityGroupId,
                            keyName = instanceConfig.userConfig.keyName,
                            clusterState = servicesConfig.clusterState,
                            tags = servicesConfig.tags,
                        ),
                    )
                synchronized(ctx.stateLock) {
                    ctx.emrCluster = cluster
                    ctx.callbacks.onEmrCreated(cluster)
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to create EMR cluster" }
                ctx.threadErrors["EMR cluster"] = e
            }
        }
    }

    private fun launchOpenSearchThread(
        servicesConfig: OptionalServicesConfig,
        ctx: ThreadContext,
    ): Thread? {
        if (!servicesConfig.initConfig.opensearchEnabled) return null
        return thread(start = true, name = "create-OpenSearch") {
            try {
                val domain =
                    createOpenSearchDomain(
                        initConfig = servicesConfig.initConfig,
                        subnetId = servicesConfig.subnetId,
                        securityGroupId = servicesConfig.securityGroupId,
                        tags = servicesConfig.tags,
                    )
                synchronized(ctx.stateLock) {
                    ctx.openSearchDomain = domain
                    ctx.callbacks.onOpenSearchCreated(domain)
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to create OpenSearch domain" }
                ctx.threadErrors["OpenSearch domain"] = e
            }
        }
    }

    /**
     * Launches [spec]'s missing instances and waits until they are running with their status
     * checks passed.
     *
     * [report] receives each RunInstances batch's hosts the moment the batch is launched, before
     * any wait, so a failure later in the launch, in the running wait, in the status check or in
     * the IP refresh still leaves every launched instance recorded. It receives the same hosts
     * again, by alias, once their public IPs are known.
     */
    private fun createInstancesForType(
        spec: InstanceSpec,
        infraContext: InfrastructureContext,
        report: (List<ClusterHost>) -> Unit,
    ) {
        val config = spec.creationConfig(infraContext)

        val createdInstances = ec2InstanceService.createInstances(config) { launched -> report(launched.map(::toClusterHost)) }

        val instanceIds = createdInstances.map { it.instanceId }
        ec2InstanceService.waitForInstancesRunning(instanceIds)
        ec2InstanceService.waitForInstanceStatusOk(instanceIds)

        report(ec2InstanceService.updateInstanceIps(createdInstances).map(::toClusterHost))
    }

    private fun toClusterHost(instance: CreatedInstance): ClusterHost =
        ClusterHost(
            publicIp = instance.publicIp,
            privateIp = instance.privateIp,
            alias = instance.alias,
            availabilityZone = instance.availabilityZone,
            instanceId = instance.instanceId,
        )

    private fun createOpenSearchDomain(
        initConfig: InitConfig,
        subnetId: String,
        securityGroupId: String,
        tags: Map<String, String>,
    ): OpenSearchClusterState {
        eventBus.emit(Event.Provision.OpenSearchCreating)

        val domainName = "${initConfig.name}-os".take(Constants.OpenSearch.DOMAIN_NAME_MAX_LENGTH)

        val config =
            OpenSearchDomainConfig(
                domainName = domainName,
                instanceType = initConfig.opensearchInstanceType,
                instanceCount = initConfig.opensearchInstanceCount,
                ebsVolumeSize = initConfig.opensearchEbsSize,
                engineVersion = "OpenSearch_${initConfig.opensearchVersion}",
                subnetId = subnetId,
                securityGroupIds = listOf(securityGroupId),
                accountId = aws.getAccountId(),
                region = user.region,
                tags = tags,
            )

        openSearchService.createDomain(config)
        val readyResult = openSearchService.waitForDomainActive(domainName)

        return OpenSearchClusterState(
            domainName = readyResult.domainName,
            domainId = readyResult.domainId,
            endpoint = readyResult.endpoint,
            dashboardsEndpoint = readyResult.dashboardsEndpoint,
            state =
                when (readyResult.state) {
                    DomainState.ACTIVE -> "Active"
                    DomainState.PROCESSING -> "Processing"
                    DomainState.DELETED -> "Deleted"
                },
        )
    }
}
