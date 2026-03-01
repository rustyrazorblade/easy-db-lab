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
 * @property specs Instance specifications for each server type
 * @property amiId AMI ID to use for instances
 * @property securityGroupId Security group for instances
 * @property subnetIds Available subnet IDs
 * @property tags Tags to apply to instances
 * @property clusterName Name of the cluster
 * @property userConfig User configuration with key name
 */
data class InstanceProvisioningConfig(
    val specs: List<InstanceSpec>,
    val amiId: String,
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
     * @param onHostsCreated Callback when hosts are created (for state updates)
     * @param onEmrCreated Callback when EMR cluster is created
     * @param onOpenSearchCreated Callback when OpenSearch domain is created
     * @return Result with created resources and any errors
     */
    fun provisionAll(
        instanceConfig: InstanceProvisioningConfig,
        servicesConfig: OptionalServicesConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
        onHostsCreated: (ServerType, List<ClusterHost>) -> Unit,
        onEmrCreated: (EMRClusterState) -> Unit,
        onOpenSearchCreated: (OpenSearchClusterState) -> Unit,
    ): ProvisioningResult
}

/**
 * Default implementation of ClusterProvisioningService.
 */
class DefaultClusterProvisioningService(
    private val ec2InstanceService: EC2InstanceService,
    private val emrProvisioningService: EMRProvisioningService,
    private val openSearchService: OpenSearchService,
    private val aws: com.rustyrazorblade.easydblab.providers.aws.AWS,
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
                            val hosts =
                                createInstancesForType(
                                    spec = spec,
                                    amiId = config.amiId,
                                    keyName = config.userConfig.keyName,
                                    securityGroupId = config.securityGroupId,
                                    subnetIds = config.subnetIds,
                                    tags = config.tags,
                                    clusterName = config.clusterName,
                                )
                            synchronized(stateLock) {
                                allHosts[spec.serverType] = (allHosts[spec.serverType] ?: emptyList()) + hosts
                                onHostsCreated(spec.serverType, hosts)
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
        onHostsCreated: (ServerType, List<ClusterHost>) -> Unit,
        onEmrCreated: (EMRClusterState) -> Unit,
        onOpenSearchCreated: (OpenSearchClusterState) -> Unit,
    ): ProvisioningResult {
        val allHosts = existingHosts.toMutableMap()
        val threadErrors = ConcurrentHashMap<String, Exception>()
        val stateLock = Any()
        var emrCluster: EMRClusterState? = null
        var openSearchDomain: OpenSearchClusterState? = null

        // Latch so EMR thread waits for EC2 instances (needs control node IP)
        val instancesReady = CountDownLatch(1)

        val serviceThreads = mutableListOf<Thread>()

        // Log messages for existing instances
        instanceConfig.specs
            .filter { it.neededCount <= 0 && it.configuredCount > 0 && it.existingCount > 0 }
            .forEach { spec ->
                eventBus.emit(Event.Ec2.ExistingInstancesFound(spec.serverType.name, spec.existingCount))
            }

        // Instance creation threads
        val instanceThreads =
            instanceConfig.specs
                .filter { it.neededCount > 0 }
                .map { spec ->
                    thread(start = true, name = "create-${spec.serverType.name}") {
                        try {
                            val hosts =
                                createInstancesForType(
                                    spec = spec,
                                    amiId = instanceConfig.amiId,
                                    keyName = instanceConfig.userConfig.keyName,
                                    securityGroupId = instanceConfig.securityGroupId,
                                    subnetIds = instanceConfig.subnetIds,
                                    tags = instanceConfig.tags,
                                    clusterName = instanceConfig.clusterName,
                                )
                            synchronized(stateLock) {
                                allHosts[spec.serverType] = (allHosts[spec.serverType] ?: emptyList()) + hosts
                                onHostsCreated(spec.serverType, hosts)
                            }
                        } catch (e: Exception) {
                            log.error(e) { "Failed to create ${spec.serverType.name} instances" }
                            threadErrors["${spec.serverType.name} instances"] = e
                        }
                    }
                }

        // Coordinator thread: waits for all EC2 instances, then signals EMR
        serviceThreads.add(
            thread(start = true, name = "instance-coordinator") {
                instanceThreads.forEach { it.join() }
                instancesReady.countDown()
            },
        )

        // EMR thread — waits for instances before proceeding
        if (servicesConfig.initConfig.sparkEnabled) {
            if (servicesConfig.clusterState.emrCluster != null) {
                eventBus.emit(Event.Emr.ClusterAlreadyExists)
            } else {
                serviceThreads.add(
                    thread(start = true, name = "create-EMR") {
                        try {
                            instancesReady.await()
                            val cluster =
                                emrProvisioningService.provisionEmrCluster(
                                    clusterName = servicesConfig.initConfig.name,
                                    masterInstanceType = servicesConfig.initConfig.sparkMasterInstanceType,
                                    workerInstanceType = servicesConfig.initConfig.sparkWorkerInstanceType,
                                    workerCount = servicesConfig.initConfig.sparkWorkerCount,
                                    subnetId = servicesConfig.subnetId,
                                    securityGroupId = servicesConfig.securityGroupId,
                                    keyName = instanceConfig.userConfig.keyName,
                                    clusterState = servicesConfig.clusterState,
                                    tags = servicesConfig.tags,
                                )
                            synchronized(stateLock) {
                                emrCluster = cluster
                                onEmrCreated(cluster)
                            }
                        } catch (e: Exception) {
                            log.error(e) { "Failed to create EMR cluster" }
                            threadErrors["EMR cluster"] = e
                        }
                    },
                )
            }
        }

        // OpenSearch thread — runs independently (no dependency on control node)
        if (servicesConfig.initConfig.opensearchEnabled) {
            serviceThreads.add(
                thread(start = true, name = "create-OpenSearch") {
                    try {
                        val domain =
                            createOpenSearchDomain(
                                initConfig = servicesConfig.initConfig,
                                subnetId = servicesConfig.subnetId,
                                securityGroupId = servicesConfig.securityGroupId,
                                tags = servicesConfig.tags,
                            )
                        synchronized(stateLock) {
                            openSearchDomain = domain
                            onOpenSearchCreated(domain)
                        }
                    } catch (e: Exception) {
                        log.error(e) { "Failed to create OpenSearch domain" }
                        threadErrors["OpenSearch domain"] = e
                    }
                },
            )
        }

        // Wait for coordinator, EMR, and OpenSearch threads
        serviceThreads.forEach { it.join() }

        return ProvisioningResult(
            hosts = allHosts.toMap(),
            errors = threadErrors.toMap(),
            emrCluster = emrCluster,
            openSearchDomain = openSearchDomain,
        )
    }

    private fun createInstancesForType(
        spec: InstanceSpec,
        amiId: String,
        keyName: String,
        securityGroupId: String,
        subnetIds: List<String>,
        tags: Map<String, String>,
        clusterName: String,
    ): List<ClusterHost> {
        val config =
            InstanceCreationConfig(
                serverType = spec.serverType,
                count = spec.neededCount,
                instanceType = spec.instanceType,
                amiId = amiId,
                keyName = keyName,
                securityGroupId = securityGroupId,
                subnetIds = subnetIds,
                iamInstanceProfile = Constants.AWS.Roles.EC2_INSTANCE_ROLE,
                ebsConfig = spec.ebsConfig,
                tags = tags,
                clusterName = clusterName,
                startIndex = spec.existingCount,
            )

        val createdInstances = ec2InstanceService.createInstances(config)

        // Wait for instances to be running
        val instanceIds = createdInstances.map { it.instanceId }
        ec2InstanceService.waitForInstancesRunning(instanceIds)

        // Wait for instance status checks to pass
        ec2InstanceService.waitForInstanceStatusOk(instanceIds)

        // Update with final IPs
        val updatedInstances = ec2InstanceService.updateInstanceIps(createdInstances)

        return updatedInstances.map { instance ->
            ClusterHost(
                publicIp = instance.publicIp,
                privateIp = instance.privateIp,
                alias = instance.alias,
                availabilityZone = instance.availabilityZone,
                instanceId = instance.instanceId,
            )
        }
    }

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
