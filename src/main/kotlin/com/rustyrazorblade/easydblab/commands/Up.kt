package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.cassandra.WriteConfig
import com.rustyrazorblade.easydblab.commands.grafana.GrafanaUpdateConfig
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStart
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.VpcNetworkingConfig
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.ClusterProvisioningService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstanceProvisioningConfig
import com.rustyrazorblade.easydblab.services.K3sClusterConfig
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.OptionalServicesConfig
import com.rustyrazorblade.easydblab.services.ProvisioningCallbacks
import com.rustyrazorblade.easydblab.services.ProvisioningResult
import com.rustyrazorblade.easydblab.services.RegistryService
import com.rustyrazorblade.easydblab.services.aws.AMIResolver
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceSpecFactory
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.inject
import picocli.CommandLine
import java.io.File
import java.time.Duration

/**
 * Provisions and configures the complete cluster infrastructure.
 *
 * This command orchestrates parallel creation of:
 * - EC2 instances (Cassandra, Stress, Control nodes)
 * - EMR Spark cluster (if enabled)
 * - OpenSearch domain (if enabled)
 *
 * After provisioning, it configures K3s on all nodes and applies Kubernetes manifests.
 * State is persisted incrementally as each resource type completes.
 *
 * @see Init for cluster initialization (must be run first)
 * @see Down for tearing down infrastructure
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@TriggerBackup
@CommandLine.Command(
    name = "up",
    description = ["Starts instances"],
)
class Up : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val s3BucketService: AwsS3BucketService by inject()
    private val openSearchService: OpenSearchService by inject()
    private val vpcService: VpcService by inject()
    private val awsInfrastructureService: AwsInfrastructureService by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val hostOperationsService: HostOperationsService by inject()
    private val amiResolver: AMIResolver by inject()
    private val instanceSpecFactory: InstanceSpecFactory by inject()
    private val clusterProvisioningService: ClusterProvisioningService by inject()
    private val clusterConfigurationService: ClusterConfigurationService by inject()
    private val k3sClusterService: K3sClusterService by inject()
    private val ciliumService: CiliumService by inject()
    private val k8sService: K8sService by inject()
    private val registryService: RegistryService by inject()
    private val socksProxyService: SocksProxyService by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val externalIpService: ExternalIpService by inject()

    // Working copy loaded during execute() - modified and saved
    private lateinit var workingState: ClusterState

    companion object {
        private val log = KotlinLogging.logger {}
        private val SSH_STARTUP_DELAY = Duration.ofSeconds(5)
    }

    // Lock for synchronizing access to workingState from parallel threads
    private val stateLock = Any()

    @CommandLine.Option(names = ["--no-setup", "-n"])
    var noSetup = false

    @CommandLine.Mixin
    var hosts = HostsMixin()

    override fun execute() {
        workingState = clusterStateManager.load()
        val initConfig =
            workingState.initConfig
                ?: error("No init config found. Please run 'easy-db-lab init' first.")

        validateControlNodeConfigured(initConfig)

        configureAccountS3Bucket()
        validateS3BucketConfigured()
        reapplyS3Policy()
        provisionInfrastructure(initConfig)
        writeConfigurationFiles()
        runNestedCommand { WriteConfig() }
        waitForSshReady()
        downloadCassandraVersions()
        setupInstancesIfNeeded()
    }

    /**
     * Validates that the configuration will produce a control node, before any AWS resource is
     * provisioned. Every downstream step — K3s, node labeling, StorageClasses, observability —
     * depends on a control node existing. Discovering that it doesn't mid-provisioning, after
     * EC2 instances are already running, is the exact failure this check exists to prevent.
     */
    private fun validateControlNodeConfigured(initConfig: InitConfig) {
        if (initConfig.controlInstances < 1) {
            eventBus.emit(Event.Provision.ControlNodeRequired(initConfig.controlInstances))
            error(
                "A control node is required to provision a cluster " +
                    "(configured control instances: ${initConfig.controlInstances}).",
            )
        }
    }

    /**
     * Validates that an S3 bucket is actually configured after [configureAccountS3Bucket] runs.
     * That method is responsible for ensuring one exists; this is the assertion that it did, so
     * a bug there fails loudly here rather than silently skipping registry TLS configuration and
     * kubeconfig backup later.
     */
    private fun validateS3BucketConfigured() {
        if (workingState.s3Bucket.isNullOrBlank()) {
            eventBus.emit(Event.Provision.S3BucketRequired(workingState.name))
            error("An S3 bucket is required to provision a cluster.")
        }
    }

    /**
     * Runs a nested command through [commandExecutor] and aborts `up` if it fails.
     *
     * [CommandExecutor.execute] never lets a nested command's exception escape to its caller —
     * [com.rustyrazorblade.easydblab.services.DefaultCommandExecutor.executeWithLifecycle]
     * catches it, logs the cause chain, and converts it to a non-zero exit code. Callers must
     * therefore inspect the returned exit code themselves; this helper does that once so every
     * nested-command call site aborts provisioning identically instead of discarding the result.
     */
    private fun <T : PicoCommand> runNestedCommand(commandFactory: () -> T) {
        val command = commandFactory()
        val commandName = command::class.simpleName ?: "nested command"
        val exitCode = commandExecutor.execute { command }
        if (exitCode != 0) {
            error("$commandName failed during provisioning (exit code $exitCode).")
        }
    }

    /**
     * Re-applies the S3Access inline policy to ensure existing roles have the latest permissions.
     * This is idempotent - PutRolePolicy overwrites existing policies with the same name.
     * Uses a wildcard policy that grants access to all easy-db-lab-* buckets.
     */
    private fun reapplyS3Policy() {
        eventBus.emit(Event.Provision.IamUpdating)
        s3BucketService.attachS3Policy(Constants.AWS.Roles.EC2_INSTANCE_ROLE)
        log.debug { "S3 policy re-applied successfully" }
    }

    /**
     * Configures the account-level S3 bucket and per-cluster data bucket.
     * Uses the bucket name from the user profile (set during setup-profile).
     * Applies bucket policy for IAM role access.
     * Creates a per-cluster data bucket for ClickHouse data and CloudWatch metrics.
     */
    private fun configureAccountS3Bucket() {
        if (!workingState.s3Bucket.isNullOrBlank() && workingState.dataBucket.isNotBlank()) {
            log.info { "S3 buckets already configured: account=${workingState.s3Bucket}, data=${workingState.dataBucket}" }
            return
        }

        // Ensure-or-create the account bucket (migrates profiles that never had one saved)
        val accountBucket = s3BucketService.ensureAccountBucket(userConfig)

        // Configure account-level bucket
        eventBus.emit(Event.S3.BucketUsing(accountBucket))
        s3BucketService.putBucketPolicy(accountBucket)
        workingState.s3Bucket = accountBucket
        eventBus.emit(Event.S3.BucketConfigured(accountBucket, workingState.clusterPrefix()))

        // Configure per-cluster data bucket
        s3BucketService.configureDataBucket(
            bucketName = workingState.dataBucketName(),
            clusterId = workingState.clusterId,
            clusterName = workingState.name,
            metricsConfigId = workingState.metricsConfigId(),
        )

        workingState.dataBucket = workingState.dataBucketName()
        clusterStateManager.save(workingState)
    }

    /**
     * Creates a new VPC or validates an existing one.
     *
     * If no VPC ID is stored in state, creates a new VPC with appropriate tags.
     * If a VPC ID exists, validates it still exists in AWS.
     *
     * @param initConfig Configuration containing cluster name and tags
     * @return The VPC ID to use for infrastructure
     */
    private fun resolveCidr(cidr: String?): String {
        if (cidr != null) return cidr
        val existingCidrs = vpcService.listAllVpcCidrs()
        val selected = CidrBlock.selectAvailable(existingCidrs)
        eventBus.emit(Event.Setup.AutoSelectedCidr(selected.value))
        return selected.value
    }

    private fun createOrValidateVpc(
        initConfig: InitConfig,
        resolvedCidr: String,
    ): String {
        val existingVpcId = workingState.vpcId

        if (existingVpcId != null) {
            // Validate existing VPC still exists
            val vpcName = vpcService.getVpcName(existingVpcId)
            if (vpcName == null) {
                error(
                    "VPC $existingVpcId not found in AWS. It may have been deleted. " +
                        "Please run 'easy-db-lab clean' and 'easy-db-lab init' to recreate.",
                )
            }
            // Backfill bucket tag on VPCs created before this tag was added
            val bucket = workingState.s3Bucket
            if (bucket != null) {
                val vpcTags = vpcService.getVpcTags(existingVpcId)
                if (vpcTags[Constants.Vpc.BUCKET_TAG_KEY] == null) {
                    log.info { "Backfilling '${Constants.Vpc.BUCKET_TAG_KEY}' tag on VPC $existingVpcId" }
                    vpcService.addTagsToVpc(existingVpcId, mapOf(Constants.Vpc.BUCKET_TAG_KEY to bucket))
                }
            }

            log.info { "Using existing VPC: $existingVpcId ($vpcName)" }
            return existingVpcId
        }

        // Create new VPC
        eventBus.emit(Event.Provision.VpcCreating(initConfig.name))
        val vpcTags =
            buildMap {
                put(Constants.Vpc.TAG_KEY, Constants.Vpc.TAG_VALUE)
                put("ClusterId", workingState.clusterId)
                put(
                    Constants.Vpc.BUCKET_TAG_KEY,
                    requireNotNull(workingState.s3Bucket) { "S3 bucket must be configured before VPC creation" },
                )
                putAll(initConfig.tags)
            }

        val vpcId = vpcService.createVpc(initConfig.name, resolvedCidr, vpcTags)
        eventBus.emit(Event.Provision.VpcCreated(vpcId))

        // Save VPC ID to state
        workingState.vpcId = vpcId
        clusterStateManager.save(workingState)

        return vpcId
    }

    /**
     * Provisions all infrastructure resources in parallel.
     *
     * Creates EC2 instances, EMR clusters, and OpenSearch domains concurrently.
     * Each resource type updates cluster state atomically when complete.
     */
    private fun provisionInfrastructure(initConfig: InitConfig) {
        eventBus.emit(Event.Provision.InfrastructureStarting)

        val resolvedCidr = resolveCidr(initConfig.cidr)
        if (initConfig.cidr == null) {
            workingState.initConfig = initConfig.copy(cidr = resolvedCidr)
            clusterStateManager.save(workingState)
        }
        val vpcId = createOrValidateVpc(initConfig, resolvedCidr)
        val vpcInfra = setupVpcNetworking(initConfig, vpcId, resolvedCidr)
        val subnetIds = vpcInfra.subnetIds
        val securityGroupId = vpcInfra.securityGroupId
        val igwId = vpcInfra.internetGatewayId

        val amiId =
            amiResolver.resolveAmiId(initConfig.ami, initConfig.arch).getOrElse { error ->
                error(error.message ?: "Failed to resolve AMI")
            }

        val baseTags =
            mapOf(
                "easy_cass_lab" to "1",
                "ClusterId" to workingState.clusterId,
            ) + initConfig.tags

        val existingHosts = discoverExistingHosts()
        val instanceConfig = buildInstanceConfig(initConfig, amiId, securityGroupId, subnetIds, baseTags)
        val servicesConfig = buildServicesConfig(initConfig, subnetIds, securityGroupId, baseTags)

        if (initConfig.opensearchEnabled) {
            openSearchService.ensureServiceLinkedRole()
        }

        val result = executeProvisioning(instanceConfig, servicesConfig, existingHosts)
        reportProvisioningFailures(result)

        // Persist all discovered+provisioned hosts (existing hosts are in result.hosts but
        // onHostsCreated is only fired for newly created ones, so we sync the full set here).
        synchronized(stateLock) {
            workingState.updateHosts(result.hosts)
            clusterStateManager.save(workingState)
        }

        finalizeInfrastructureState(vpcId, subnetIds, securityGroupId, igwId)

        printProvisioningSuccessMessage()
        eventBus.emit(
            Event.Provision.ClusterStateUpdated(
                result.hosts.values
                    .flatten()
                    .size,
            ),
        )
    }

    private fun setupVpcNetworking(
        initConfig: InitConfig,
        vpcId: String,
        resolvedCidr: String,
    ) = awsInfrastructureService.setupVpcNetworking(
        VpcNetworkingConfig(
            vpcId = vpcId,
            clusterName = initConfig.name,
            clusterId = workingState.clusterId,
            region = initConfig.region,
            availabilityZones = initConfig.azs.ifEmpty { listOf("a", "b", "c") },
            isOpen = initConfig.open,
            tags = initConfig.tags,
            vpcCidr = resolvedCidr,
        ),
    ) { externalIpService.getExternalIpAddress() }

    private fun discoverExistingHosts(): Map<ServerType, List<ClusterHost>> {
        val existingInstances = ec2InstanceService.findInstancesByClusterId(workingState.clusterId)
        logExistingInstances(existingInstances)
        return existingInstances.mapValues { (_, instances) ->
            instances.map { it.toClusterHost() }
        }
    }

    private fun buildInstanceConfig(
        initConfig: InitConfig,
        amiId: String,
        securityGroupId: String,
        subnetIds: List<String>,
        baseTags: Map<String, String>,
    ): InstanceProvisioningConfig {
        val existingInstances = ec2InstanceService.findInstancesByClusterId(workingState.clusterId)
        val dbHasInstanceStore = ec2InstanceService.hasInstanceStore(initConfig.instanceType)
        val instanceSpecs = instanceSpecFactory.createInstanceSpecs(initConfig, existingInstances, dbHasInstanceStore)
        return InstanceProvisioningConfig(
            specs = instanceSpecs,
            amiId = amiId,
            securityGroupId = securityGroupId,
            subnetIds = subnetIds,
            tags = baseTags,
            clusterName = initConfig.name,
            userConfig = userConfig,
        )
    }

    private fun buildServicesConfig(
        initConfig: InitConfig,
        subnetIds: List<String>,
        securityGroupId: String,
        baseTags: Map<String, String>,
    ) = OptionalServicesConfig(
        initConfig = initConfig,
        subnetId = subnetIds.first(),
        securityGroupId = securityGroupId,
        tags = baseTags,
        clusterState = workingState,
    )

    private fun executeProvisioning(
        instanceConfig: InstanceProvisioningConfig,
        servicesConfig: OptionalServicesConfig,
        existingHosts: Map<ServerType, List<ClusterHost>>,
    ) = clusterProvisioningService.provisionAll(
        instanceConfig = instanceConfig,
        servicesConfig = servicesConfig,
        existingHosts = existingHosts,
        callbacks =
            ProvisioningCallbacks(
                onHostsCreated = { serverType, hosts ->
                    synchronized(stateLock) {
                        val allHosts = workingState.hosts.toMutableMap()
                        allHosts[serverType] = (allHosts[serverType] ?: emptyList()) + hosts
                        workingState.updateHosts(allHosts)
                        clusterStateManager.save(workingState)
                    }
                },
                onEmrCreated = { emrState ->
                    synchronized(stateLock) {
                        workingState.updateEmrCluster(emrState)
                        clusterStateManager.save(workingState)
                    }
                    eventBus.emit(Event.Provision.EmrReady(emrState.masterPublicDns ?: "unknown"))
                },
                onOpenSearchCreated = { osState ->
                    synchronized(stateLock) {
                        workingState.updateOpenSearchDomain(osState)
                        clusterStateManager.save(workingState)
                    }
                    eventBus.emit(Event.Provision.OpenSearchReady(osState.endpoint ?: "unknown"))
                    osState.dashboardsEndpoint?.let {
                        eventBus.emit(Event.Provision.OpenSearchDashboards("Dashboards: $it"))
                    }
                },
            ),
    )

    private fun reportProvisioningFailures(result: ProvisioningResult) {
        if (result.errors.isNotEmpty()) {
            eventBus.emit(Event.Provision.InfrastructureFailureHeader)
            result.errors.forEach { (resource, error) ->
                eventBus.emit(Event.Provision.InfrastructureFailure(resource, error.message ?: "Unknown error"))
            }
            error(
                "Failed to create ${result.errors.size} infrastructure resource(s): " +
                    result.errors.keys.joinToString(", "),
            )
        }
    }

    private fun finalizeInfrastructureState(
        vpcId: String,
        subnetIds: List<String>,
        securityGroupId: String,
        igwId: String?,
    ) {
        synchronized(stateLock) {
            workingState.updateInfrastructure(
                InfrastructureState(
                    vpcId = vpcId,
                    region = userConfig.region,
                    subnetIds = subnetIds,
                    securityGroupId = securityGroupId,
                    internetGatewayId = igwId,
                ),
            )
            workingState.markInfrastructureUp()
            clusterStateManager.save(workingState)
        }
    }

    private fun logExistingInstances(existingInstances: Map<ServerType, List<DiscoveredInstance>>) {
        if (existingInstances.values.flatten().isNotEmpty()) {
            val cassandra = existingInstances[ServerType.Cassandra]?.size ?: 0
            val stress = existingInstances[ServerType.Stress]?.size ?: 0
            val control = existingInstances[ServerType.Control]?.size ?: 0
            eventBus.emit(Event.Provision.InstancesDiscovered(cassandra, stress, control))
        }
    }

    private fun printProvisioningSuccessMessage() {
        eventBus.emit(Event.Provision.ProvisioningComplete)
        eventBus.emit(Event.Provision.WritingSshConfig)
        eventBus.emit(Event.Provision.SourceEnvInstruction)
        eventBus.emit(Event.Provision.CassandraPatchInstruction)
    }

    /** Writes SSH config, environment files, and AxonOps configuration to the working directory. */
    private fun writeConfigurationFiles() {
        clusterConfigurationService
            .writeAllConfigurationFiles(
                context.workingDirectory.toPath(),
                workingState,
                userConfig,
            ).getOrThrow()
    }

    /**
     * Waits for SSH to become available on the control node and Cassandra hosts.
     *
     * The control node is included because the SOCKS tunnel and K3s setup both dial it next;
     * confirming it is reachable here, rather than discovering it isn't mid-tunnel-setup, is
     * what [downloadCassandraVersions] alone never verified.
     */
    private fun waitForSshReady() {
        eventBus.emit(Event.Provision.SshWaiting)
        Thread.sleep(SSH_STARTUP_DELAY.toMillis())

        val retryConfig = RetryUtil.createSshConnectionRetryConfig()
        val retry =
            Retry.of("ssh-connection", retryConfig).also {
                it.eventPublisher.onRetry { event ->
                    eventBus.emit(Event.Provision.SshRetrying(event.numberOfRetryAttempts))
                }
            }

        Retry
            .decorateRunnable(retry) {
                checkSshReady(ServerType.Control)
                checkSshReady(ServerType.Cassandra)
            }.run()
    }

    private fun checkSshReady(serverType: ServerType) {
        hostOperationsService.withHosts(
            workingState.hosts,
            serverType,
            hosts.hostList,
        ) { clusterHost ->
            remoteOps.executeRemotely(clusterHost.toHost(), "echo 1").text
        }
    }

    /** Downloads Cassandra version metadata from db hosts. Meaningless for the control node. */
    private fun downloadCassandraVersions() {
        hostOperationsService.withHosts(
            workingState.hosts,
            ServerType.Cassandra,
            hosts.hostList,
        ) { clusterHost ->
            val host = clusterHost.toHost()
            val versionsFile = File(context.workingDirectory, "cassandra_versions.yaml")
            if (!versionsFile.exists()) {
                remoteOps.download(
                    host,
                    "/etc/cassandra_versions.yaml",
                    versionsFile.toPath(),
                )
            }
        }
    }

    /** Runs instance setup, K3s configuration, and optional AxonOps setup unless --no-setup. */
    private fun setupInstancesIfNeeded() {
        if (noSetup) {
            eventBus.emit(Event.Provision.SkippingNodeSetup)
        } else {
            runNestedCommand { SetupInstance() }
            startTailscaleIfConfigured()
            startProxyIfNeeded()
            startK3sOnAllNodes()

            if (userConfig.axonOpsKey.isNotBlank() && userConfig.axonOpsOrg.isNotBlank()) {
                eventBus.emit(Event.Provision.AxonOpsSetup(userConfig.axonOpsOrg))
                runNestedCommand { ConfigureAxonOps() }
            }
        }
    }

    /**
     * Starts the SOCKS5 proxy to the control node when Tailscale is not active.
     *
     * Proxy and Tailscale are mutually exclusive networking paths to the private cluster.
     * Both are started at the same point so that subsequent K3s setup, kubectl, and
     * fabric8 operations can reach the cluster API without Tailscale.
     */
    private fun startProxyIfNeeded() {
        if (workingState.isTailscaleEnabled()) return
        val controlHost = workingState.hosts[ServerType.Control]?.firstOrNull() ?: return
        log.info { "Starting SOCKS5 proxy to ${controlHost.alias} for K8s API access" }
        socksProxyService.ensureRunning(controlHost)
    }

    /**
     * Starts Tailscale VPN on the control node if credentials are configured.
     *
     * This enables secure remote access to the cluster through Tailscale's VPN. A requested
     * networking path that fails to come up is a failed provision: this aborts `up` rather than
     * warning and continuing, but preserves the manual-setup instruction in the thrown message
     * so the user still knows how to recover.
     */
    private fun startTailscaleIfConfigured() {
        if (!workingState.isTailscaleEnabled()) return
        if (userConfig.tailscaleClientId.isNotBlank() && userConfig.tailscaleClientSecret.isNotBlank()) {
            eventBus.emit(Event.Provision.TailscaleStarting)
            val exitCode = commandExecutor.execute { TailscaleStart() }
            if (exitCode != 0) {
                error(
                    "Failed to start Tailscale VPN (exit code $exitCode). " +
                        "You can manually start it later with: easy-db-lab tailscale start",
                )
            }
        }
    }

    /** Installs Cilium as the K3s CNI on the control node. */
    private fun installCilium() {
        val controlHosts = workingState.hosts[ServerType.Control] ?: emptyList()
        ciliumService.install(controlHosts.first().toHost()).getOrThrow()
    }

    /** Starts K3s server on control node and joins Cassandra/Stress nodes as agents. */
    private fun startK3sOnAllNodes() {
        val controlHosts = workingState.hosts[ServerType.Control] ?: emptyList()

        // Configure registry TLS before K3s starts so registries.yaml is in place
        configureRegistryTls()

        val ciliumEnabled = workingState.initConfig?.ciliumEnabled ?: false
        val config =
            K3sClusterConfig(
                controlHost = controlHosts.first(),
                workerHosts =
                    mapOf(
                        ServerType.Cassandra to (workingState.hosts[ServerType.Cassandra] ?: emptyList()),
                        ServerType.Stress to (workingState.hosts[ServerType.Stress] ?: emptyList()),
                    ),
                kubeconfigPath = File(context.workingDirectory, "kubeconfig").toPath(),
                hostFilter = hosts.hostList,
                clusterState = workingState,
                useCustomCni = ciliumEnabled,
                onServerReady = if (ciliumEnabled) ({ installCilium() }) else null,
            )

        val result = k3sClusterService.setupCluster(config)

        if (!result.isSuccessful) {
            result.errors.forEach { (operation, error) ->
                log.error(error) { "K3s setup failed: $operation" }
            }
            error("K3s cluster setup failed: " + result.errors.keys.joinToString(", "))
        }

        // Label db and app nodes with ordinals for StatefulSet pod-to-node pinning
        labelNodesWithOrdinals(controlHosts.first())

        // Ensure StorageClasses exist for Local PVs
        k8sService.ensureLocalStorageClass(controlHosts.first()).getOrThrow()
        k8sService.ensureLocalStorageWfcClass(controlHosts.first()).getOrThrow()

        runNestedCommand { GrafanaUpdateConfig() }
    }

    /**
     * Labels db and app nodes with ordinal values for StatefulSet pod-to-node pinning,
     * and labels the control node with type=control for OTel k8sattributes processor.
     *
     * This enables workloads (ClickHouse, Presto, etc.) to guarantee that pod X runs on node X
     * by using Local PersistentVolumes with node affinity.
     */
    private fun labelNodesWithOrdinals(controlHost: ClusterHost) {
        // Label control node with type=control (db and app nodes get this via K3s agent config)
        k8sService.labelNode(controlHost, controlHost.alias, mapOf("type" to "control")).getOrThrow()

        val dbHosts = workingState.hosts[ServerType.Cassandra] ?: emptyList()
        val appHosts = workingState.hosts[ServerType.Stress] ?: emptyList()

        // Zero db nodes is a legal configuration (e.g. Trino + OpenSearch) — this is a skip
        // because there is nothing to do, not a failure, so it must never warn.
        if (dbHosts.isEmpty()) {
            log.debug { "No db nodes found, skipping db node labeling" }
        } else {
            eventBus.emit(Event.Provision.NodeLabeling(dbHosts.size))
            dbHosts.forEachIndexed { index, host ->
                k8sService
                    .labelNode(controlHost, host.alias, mapOf(Constants.NODE_ORDINAL_LABEL to index.toString()))
                    .getOrThrow()
            }
            eventBus.emit(Event.Provision.NodeLabelingComplete)
        }

        if (appHosts.isNotEmpty()) {
            eventBus.emit(Event.Provision.NodeLabeling(count = appHosts.size, nodeType = "app"))
            appHosts.forEachIndexed { index, host ->
                k8sService
                    .labelNode(controlHost, host.alias, mapOf(Constants.NODE_ORDINAL_LABEL to index.toString()))
                    .getOrThrow()
            }
            eventBus.emit(Event.Provision.NodeLabelingComplete)
        }
    }

    /**
     * Configures TLS for the container registry.
     *
     * Generates a self-signed certificate on the control node and configures both containerd
     * and K3s registries.yaml on all nodes to trust the registry. This must happen before K3s
     * starts so that K3s picks up the HTTPS registry configuration.
     */
    private fun configureRegistryTls() {
        val controlHosts = workingState.hosts[ServerType.Control] ?: emptyList()
        val s3Bucket = checkNotNull(workingState.s3Bucket) { "S3 bucket must be configured before registry TLS setup" }

        val controlHost = controlHosts.first().toHost()
        val registryHost = controlHost.private

        // Generate cert on control node and upload to S3
        registryService.generateAndUploadCert(controlHost, s3Bucket)

        // Configure containerd on ALL nodes to trust the registry
        val allHosts =
            (workingState.hosts[ServerType.Control] ?: emptyList()) +
                (workingState.hosts[ServerType.Cassandra] ?: emptyList()) +
                (workingState.hosts[ServerType.Stress] ?: emptyList())

        allHosts.forEach { clusterHost ->
            registryService.configureTlsOnNode(clusterHost.toHost(), registryHost, s3Bucket)
        }

        eventBus.emit(Event.Registry.TlsConfigured)
    }
}
