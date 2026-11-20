package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.annotations.TriggerBackup
import com.rustyrazorblade.easydblab.commands.cassandra.WriteConfig
import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStart
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeGroup
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.nodeGroups
import com.rustyrazorblade.easydblab.configuration.update
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.network.CidrBlock
import com.rustyrazorblade.easydblab.network.TcpReachabilityProbe
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.InsufficientInstanceCapacityException
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import com.rustyrazorblade.easydblab.providers.aws.VpcNetworkingConfig
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.ClusterProvisioningService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.InstanceProvisioningConfig
import com.rustyrazorblade.easydblab.services.K8sStorageOperations
import com.rustyrazorblade.easydblab.services.NodeSetupService
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import com.rustyrazorblade.easydblab.services.OptionalServicesConfig
import com.rustyrazorblade.easydblab.services.ProvisioningCallbacks
import com.rustyrazorblade.easydblab.services.ProvisioningPreflight
import com.rustyrazorblade.easydblab.services.ProvisioningResult
import com.rustyrazorblade.easydblab.services.aws.AMIResolver
import com.rustyrazorblade.easydblab.services.aws.AccountBucketSetup
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsKeyPairService
import com.rustyrazorblade.easydblab.services.aws.CapacityPlan
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceSpecFactory
import com.rustyrazorblade.easydblab.services.aws.LaunchCapacityPreflight
import com.rustyrazorblade.easydblab.services.aws.LaunchGroup
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import com.rustyrazorblade.easydblab.services.aws.missingOrdinals
import com.rustyrazorblade.easydblab.services.bringup.BringUpReporting
import com.rustyrazorblade.easydblab.services.bringup.BringUpResult
import com.rustyrazorblade.easydblab.services.bringup.BringUpSettings
import com.rustyrazorblade.easydblab.services.bringup.BringUpTiming
import com.rustyrazorblade.easydblab.services.bringup.ClusterStateWriter
import com.rustyrazorblade.easydblab.services.bringup.LaunchSink
import com.rustyrazorblade.easydblab.services.bringup.NodeBringUp
import com.rustyrazorblade.easydblab.services.bringup.NodeEnd
import com.rustyrazorblade.easydblab.services.bringup.aws.Ec2BringUpPorts
import com.rustyrazorblade.easydblab.services.bringup.aws.Ec2BringUpRequest
import com.rustyrazorblade.easydblab.services.documents.DocumentService
import com.rustyrazorblade.easydblab.services.launches
import com.rustyrazorblade.easydblab.services.replacementLaunches
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import org.koin.core.component.inject
import picocli.CommandLine
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * Provisions and configures the complete cluster.
 *
 * After the preflights, the account bucket and the VPC, the instances launch in batches, with
 * EMR and OpenSearch when enabled, and every node is brought up in its own pipeline as soon as its
 * instance exists ([NodeBringUp], design D1): running, status checks, SSH, setup, registry TLS
 * and the K3s join, with its own retries and replacement. The control node's pipeline writes the
 * workspace files, opens the operator's route ([UpOperatorRoute]), and starts the control plane.
 * Once every pipeline has ended, [UpPostSteps] runs the cluster-wide steps. A node that could not
 * be brought up keeps its instance and fails `up` once the rest has finished.
 *
 * @see Init for cluster initialization (must be run first)
 * @see Down for tearing down infrastructure
 *
 * @param tailnetRetryInterval How long to wait between probes of the control node over the
 *   tailnet while the subnet route comes up. Tests inject [Duration.ZERO].
 * @param random The source of the auto-selected VPC CIDR.
 * @param timing Every wait and policy of the bring-up; tests inject short ones.
 */
@RequireProfileSetup
@RequiresProxy
@TriggerBackup
@CommandLine.Command(
    name = "up",
    description = ["Starts instances"],
)
class Up(
    private val tailnetRetryInterval: Duration = TAILNET_RETRY_INTERVAL,
    private val random: Random = Random.Default,
    private val timing: BringUpTiming = BringUpTiming(),
) : PicoBaseCommand() {
    private val userConfig: User by inject()
    private val accountBucketSetup: AccountBucketSetup by inject()
    private val keyPairService: AwsKeyPairService by inject()
    private val openSearchService: OpenSearchService by inject()
    private val vpcService: VpcService by inject()
    private val awsInfrastructureService: AwsInfrastructureService by inject()
    private val ec2InstanceService: EC2InstanceService by inject()
    private val amiResolver: AMIResolver by inject()
    private val instanceSpecFactory: InstanceSpecFactory by inject()
    private val clusterProvisioningService: ClusterProvisioningService by inject()
    private val clusterConfigurationService: ClusterConfigurationService by inject()
    private val ciliumInstallAnnotator: CiliumInstallAnnotator by inject()
    private val k8sStorage: K8sStorageOperations by inject()
    private val observabilityStackService: ObservabilityStackService by inject()
    private val nodeSetupService: NodeSetupService by inject()
    private val hostOperationsService: HostOperationsService by inject()
    private val socksProxyService: SocksProxyService by inject()
    private val proxyPreflight: ProxyPreflight by inject()
    private val commandExecutor: CommandExecutor by inject()
    private val externalIpService: ExternalIpService by inject()
    private val provisioningPreflight: ProvisioningPreflight by inject()
    private val launchCapacityPreflight: LaunchCapacityPreflight by inject()
    private val documentService: DocumentService by inject()
    private val tcpReachabilityProbe: TcpReachabilityProbe by inject()
    private val ec2BringUpPorts: Ec2BringUpPorts by inject()
    private val nodeOutput: NodeOutput by inject()

    // Working copy loaded during execute(). Once the launch starts, every change goes through [writer].
    private lateinit var workingState: ClusterState
    private lateinit var writer: ClusterStateWriter
    private lateinit var workspace: UpWorkspace

    companion object {
        private val log = KotlinLogging.logger {}
        private val TAILNET_RETRY_INTERVAL = Duration.ofMillis(Constants.Tailscale.REACHABILITY_RETRY_INTERVAL_MS)
        private const val FIRST_DATABASE_NODE = "db0"
    }

    @CommandLine.Option(
        names = ["--no-setup", "-n"],
        description = ["Provision the instances only: skip instance setup, K3s, and AxonOps configuration"],
    )
    var noSetup = false

    @CommandLine.Option(
        names = ["--quiet", "-q"],
        description = ["Hide the output of each node's scripts; Ready lines, progress and failures (with their last output) still print"],
    )
    var quiet = false

    @CommandLine.Mixin
    var hosts = HostsMixin()

    /** The tunnel the `@RequiresProxy` preflight obtained for this `up`, if it ran; see [SocksRoute]. */
    private var preflightTunnel: SocksProxyState? = null

    override fun execute() {
        // Read before any nested command runs: the executor clears the record before each command.
        preflightTunnel = proxyPreflight.tunnelForCommand()
        workingState = clusterStateManager.load()
        val initConfig =
            workingState.initConfig
                ?: error("No init config found. Please run 'easy-db-lab init' first.")

        provisioningPreflight.verify(workingState, initConfig)

        accountBucketSetup.prepare(workingState)
        // A key pair from before the easy_db_lab marker existed gains it here; the instances launch with it.
        keyPairService.ensureMarkerTags(userConfig)
        documentService.rebuildIndex(workingState)
        finish(bringUp(initConfig))
    }

    /**
     * The shared pre-steps (capacity, VPC, launch checks), then the launch and every node's
     * pipeline at once: each instance is handed to its pipeline as its batch returns.
     */
    private fun bringUp(initConfig: InitConfig): BringUpResult {
        eventBus.emit(Event.Provision.InfrastructureStarting)

        // One look at EC2: the hosts kept, the capacity checked and the count launched must agree.
        val existingInstances = discoverExistingInstances()
        launchCapacityPreflight.verifyCapacity(capacityPlan(initConfig, existingInstances))

        val (vpcId, resolvedCidr) = resolveVpc(initConfig)
        val vpcInfra = setupVpcNetworking(initConfig, vpcId, resolvedCidr)
        // The user tags first, so none of them can replace a marker tag or the cluster id.
        val baseTags = initConfig.tags + Constants.Vpc.MARKER_TAGS + (Constants.Vpc.CLUSTER_ID_TAG_KEY to workingState.clusterId)

        val existingHosts = existingInstances.mapValues { (_, instances) -> instances.map { it.toClusterHost() } }
        val instanceConfig = buildInstanceConfig(initConfig, vpcInfra.securityGroupId, vpcInfra.subnetIds, baseTags, existingInstances)
        val servicesConfig = buildServicesConfig(initConfig, vpcInfra.subnetIds, vpcInfra.securityGroupId, baseTags)
        // The subnets and the security group exist now: ask AWS whether it would run each launch.
        launchCapacityPreflight.verifyLaunchPermitted(instanceConfig.launches())
        if (initConfig.opensearchEnabled) openSearchService.ensureServiceLinkedRole()

        // The discovered instances are the cluster's hosts; the launch adds the missing ones.
        writer = ClusterStateWriter(workingState, clusterStateManager)
        writer.update { it.updateHosts(existingHosts) }
        // The tunnel and every cluster command read the infrastructure as up; the pipelines need both.
        finalizeInfrastructureState(vpcId, vpcInfra)
        val launched = CompletableFuture<Unit>()
        workspace =
            UpWorkspace(
                clusterConfigurationService,
                userConfig,
                writer,
                context.workingDirectory,
            ) { runNested(commandExecutor, WriteConfig()) }
        // A replacement is checked against the VPC this `up` resolved, not the one it started with.
        val replacementPlan = capacityPlan(workingState.initConfig ?: initConfig, existingInstances)
        val request =
            Ec2BringUpRequest(
                writer,
                operatorRoute(launched),
                context.workingDirectory,
                instanceConfig.replacementLaunches(),
                replacementPlan,
            )
        val nodeBringUp = NodeBringUp(ec2BringUpPorts.ports(request), bringUpSettings(initConfig), BringUpReporting(eventBus, nodeOutput))
        val runPipelines = {
            nodeBringUp.run { sink ->
                existingHosts.forEach { (serverType, hosts) -> sink.launched(serverType, hosts, LaunchSink.Origin.FOUND_RUNNING) }
                try {
                    reportProvisioningFailures(executeProvisioning(instanceConfig, servicesConfig, existingHosts, sink, launched))
                } finally {
                    launched.complete(Unit)
                }
            }
        }
        // --quiet hides the pipelines' script output only; the post-steps print as they always do.
        val result = if (quiet) nodeOutput.quietly(runPipelines) else runPipelines()
        printProvisioningSuccessMessage()
        eventBus.emit(Event.Provision.ClusterStateUpdated(writer.read { state -> state.hosts.values.sumOf { it.size } }))
        return result
    }

    private fun bringUpSettings(initConfig: InitConfig) =
        BringUpSettings(
            nodeCount = initConfig.cassandraInstances + initConfig.stressInstances,
            noSetup = noSetup,
            hostFilter = hosts.hostList,
            timing = timing,
        )

    /**
     * The operator's route into the cluster: the SOCKS tunnel unless the cluster uses Tailscale,
     * then Tailscale and its route check. Under `--no-setup` a Tailscale cluster gets neither, as
     * the VPN is started with node setup.
     */
    private fun operatorRoute(launched: CompletableFuture<Unit>): UpOperatorRoute {
        val connect: (ClusterHost) -> Unit =
            when {
                !workingState.isTailscaleEnabled() -> SocksRoute(socksProxyService, eventBus, preflightTunnel)::connect
                noSetup -> { _ -> }
                else ->
                    TailnetRoute(
                        userConfig,
                        eventBus,
                        tcpReachabilityProbe,
                        tailnetRetryInterval,
                        { commandExecutor.execute { TailscaleStart() } },
                        writer,
                        clusterStateManager,
                    )::connect
            }
        return UpOperatorRoute(workspace, launched, connect)
    }

    /**
     * The shared post-steps once every pipeline has ended (design D2): the workspace files with
     * the final hosts, then, unless `--no-setup`, the environment files again when db0 was
     * replaced, the cluster-wide steps and AxonOps. When a node failed, `up` names every failed
     * node, even when a post-step failed too, and exits non-zero.
     */
    private fun finish(result: BringUpResult) {
        val failure = result.failureEvent()
        try {
            workspace.write()
            if (noSetup) eventBus.emit(Event.Provision.SkippingNodeSetup) else configureCluster(result)
        } finally {
            failure?.let(eventBus::emit)
        }
        if (failure != null) throw CommandFailedException("up failed: ${failure.nodes.size} node(s) could not be brought up")
    }

    private fun configureCluster(result: BringUpResult) {
        eventBus.emit(Event.Registry.TlsConfigured)
        val (control, reachable) =
            writer.read { state ->
                val failed = result.failed.map { it.host.alias }.toSet()
                checkNotNull(state.getControlHost()) { "The cluster has no control node" } to
                    state.hosts.mapValues { (_, hosts) -> hosts.filterNot { it.alias in failed } }
            }
        if (result.wasReplaced(FIRST_DATABASE_NODE)) EnvironmentRepush(nodeSetupService, hostOperationsService).push(reachable)
        UpPostSteps(k8sStorage, observabilityStackService, ciliumInstallAnnotator, eventBus)
            .run(control, localTelemetry = workingState.initConfig?.telemetryRedirect == null)
        if (userConfig.isAxonOpsEnabled()) configureAxonOps(result)
    }

    /** AxonOps on the database nodes; when some failed, only on the ones that are Ready. */
    private fun configureAxonOps(result: BringUpResult) {
        val command = ConfigureAxonOps()
        if (result.failed.isNotEmpty()) {
            val ready = result.nodes.filter { (alias, end) -> end is NodeEnd.Ready && ServerType.Cassandra.ordinalOf(alias) != null }.keys
            if (ready.isEmpty()) return
            command.hosts.hostList = ready.sorted().joinToString(",")
        }
        eventBus.emit(Event.Provision.AxonOpsSetup(userConfig.axonOpsOrg))
        runNested(commandExecutor, command)
    }

    /**
     * Resolves the cluster's VPC and its CIDR.
     *
     * A CIDR given with `--cidr` is used as-is. Without one, a new VPC is created on a random
     * unused block (see [createVpcOnRandomCidr]). A VPC already recorded with no CIDR is an `up`
     * interrupted between creating the VPC and persisting its CIDR; the VPC's own CIDR is read
     * from AWS and persisted, so subnets and the CNI use the block the VPC actually has.
     *
     * @return the VPC ID and the CIDR it uses
     */
    private fun resolveVpc(initConfig: InitConfig): Pair<String, String> {
        val explicitCidr = initConfig.cidr
        val existingVpcId = workingState.vpcId
        return when {
            explicitCidr != null -> createOrValidateVpc(initConfig, explicitCidr) to explicitCidr
            existingVpcId == null -> createVpcOnRandomCidr(initConfig)
            else -> {
                val cidr = vpcService.getVpcCidr(existingVpcId) ?: vpcNotFound(existingVpcId)
                persistCidr(initConfig, cidr)
                createOrValidateVpc(initConfig, cidr) to cidr
            }
        }
    }

    private fun vpcNotFound(vpcId: String): Nothing =
        error(
            "VPC $vpcId not found in AWS. It may have been deleted. " +
                "Please run 'easy-db-lab clean' and 'easy-db-lab init' to recreate.",
        )

    /**
     * Creates the VPC on a random unused `10.X.0.0/16`. If creation fails, a new random block is
     * chosen — excluding every block already tried — up to
     * [Constants.Vpc.CIDR_AUTO_SELECT_MAX_ATTEMPTS] attempts. The CIDR is persisted only once a
     * VPC exists on it, so a failed attempt never becomes the cluster's CIDR.
     */
    private fun createVpcOnRandomCidr(initConfig: InitConfig): Pair<String, String> {
        val attempted = mutableListOf<String>()
        val retry = Retry.of("create-vpc-auto-cidr", RetryUtil.createVpcAutoCidrRetryConfig())
        val created =
            Retry
                .decorateSupplier(retry) {
                    val cidr = autoSelectCidr(excluded = attempted)
                    attempted += cidr
                    createOrValidateVpc(initConfig, cidr) to cidr
                }.get()
        persistCidr(initConfig, created.second)
        return created
    }

    private fun autoSelectCidr(excluded: List<String>): String {
        val selected = CidrBlock.selectAvailable(vpcService.listAllVpcCidrs() + excluded, random)
        eventBus.emit(Event.Setup.AutoSelectedCidr(selected.value))
        return selected.value
    }

    private fun persistCidr(
        initConfig: InitConfig,
        cidr: String,
    ) {
        clusterStateManager.update(workingState) { it.initConfig = it.initConfig?.copy(cidr = cidr) ?: initConfig.copy(cidr = cidr) }
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
    private fun createOrValidateVpc(
        initConfig: InitConfig,
        resolvedCidr: String,
    ): String {
        val existingVpcId = workingState.vpcId

        if (existingVpcId != null) {
            // Validate existing VPC still exists
            val vpcName = vpcService.getVpcName(existingVpcId) ?: vpcNotFound(existingVpcId)
            // Backfill bucket tag on VPCs created before this tag was added. A VPC supplied with
            // --vpc is the user's, so its tags are never edited.
            val bucket = workingState.s3Bucket.takeUnless { workingState.vpcSupplied }
            if (bucket != null) {
                val vpcTags = vpcService.getVpcTags(existingVpcId)
                if (vpcTags[Constants.Vpc.BUCKET_TAG_KEY] == null) {
                    log.info { "Backfilling '${Constants.Vpc.BUCKET_TAG_KEY}' tag on VPC $existingVpcId" }
                    vpcService.addTags(listOf(existingVpcId), mapOf(Constants.Vpc.BUCKET_TAG_KEY to bucket))
                }
            }

            log.info { "Using existing VPC: $existingVpcId ($vpcName)" }
            return existingVpcId
        }

        // Create new VPC
        eventBus.emit(Event.Provision.VpcCreating(initConfig.name))
        val vpcTags =
            // User tags go first, so a --tag naming a marker, the cluster id or the bucket cannot override them.
            buildMap {
                putAll(initConfig.tags)
                putAll(Constants.Vpc.MARKER_TAGS)
                put(Constants.Vpc.CLUSTER_ID_TAG_KEY, workingState.clusterId)
                put(
                    Constants.Vpc.BUCKET_TAG_KEY,
                    requireNotNull(workingState.s3Bucket) { "S3 bucket must be configured before VPC creation" },
                )
            }

        val vpcId = vpcService.createVpc(initConfig.name, resolvedCidr, vpcTags)
        eventBus.emit(Event.Provision.VpcCreated(vpcId))

        // Save VPC ID to state
        clusterStateManager.update(workingState) { it.vpcId = vpcId }

        return vpcId
    }

    /**
     * What the capacity checks need: each server type's instances still to launch, the ordinals
     * none of [existingInstances] holds (the same ones the launch fills), and where they land.
     * Without a VPC yet the subnets are sized from the CIDR `up` will use; an auto-selected
     * block is always a /16.
     */
    private fun capacityPlan(
        initConfig: InitConfig,
        existingInstances: Map<ServerType, List<DiscoveredInstance>>,
    ): CapacityPlan =
        CapacityPlan(
            groups =
                initConfig.nodeGroups().map { group ->
                    LaunchGroup(
                        group.serverType,
                        group.instanceType,
                        missingOrdinals(group.serverType, group.count, existingInstances[group.serverType].orEmpty()),
                    )
                },
            region = initConfig.region,
            zones = availabilityZones(initConfig),
            cni = initConfig.cni,
            existingVpcId = workingState.vpcId,
            vpcCidr = CidrBlock(initConfig.cidr ?: Constants.Vpc.DEFAULT_CIDR),
        )

    private fun availabilityZones(initConfig: InitConfig): List<String> = initConfig.azs.ifEmpty { listOf("a", "b", "c") }

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
            availabilityZones = availabilityZones(initConfig),
            isOpen = initConfig.open,
            tags = initConfig.tags,
            vpcCidr = resolvedCidr,
        ),
    ) { externalIpService.getExternalIpAddress() }

    private fun discoverExistingInstances(): Map<ServerType, List<DiscoveredInstance>> =
        ec2InstanceService.findInstancesByClusterId(workingState.clusterId).also(::logExistingInstances)

    private fun buildInstanceConfig(
        initConfig: InitConfig,
        securityGroupId: String,
        subnetIds: List<String>,
        baseTags: Map<String, String>,
        existingInstances: Map<ServerType, List<DiscoveredInstance>>,
    ): InstanceProvisioningConfig {
        // One DescribeInstanceTypes query reports both instance-store presence and supported
        // architectures; take instance-store from it rather than a second per-type API call.
        val dbCapabilities = ec2InstanceService.describeInstanceType(initConfig.instanceType)
        val launchingGroups = launchingNodeGroups(initConfig)
        val amiByArch = resolveAmisByArch(initConfig, launchingGroups)
        logResolvedGroupAmis(launchingGroups, amiByArch)
        val instanceSpecs =
            instanceSpecFactory.createInstanceSpecs(
                initConfig,
                existingInstances,
                dbCapabilities.hasInstanceStore,
                amiByArch,
            )
        return InstanceProvisioningConfig(
            specs = instanceSpecs,
            securityGroupId = securityGroupId,
            subnetIds = subnetIds,
            tags = baseTags,
            clusterName = initConfig.name,
            userConfig = userConfig,
        )
    }

    /**
     * The node groups that will actually launch instances, i.e. those with a configured count above
     * zero. A group with zero nodes (commonly the application group) contributes no architecture, so
     * `up` never resolves or requires an AMI for an image it will not use.
     */
    private fun launchingNodeGroups(initConfig: InitConfig): List<NodeGroup> = initConfig.nodeGroups().filter { it.count > 0 }

    /**
     * Resolves one AMI per distinct CPU architecture across the cluster's launching node groups.
     *
     * A mixed-architecture cluster (e.g. an arm64 database group and an x86_64 application group)
     * resolves each distinct architecture exactly once; the resulting map is attached per group so
     * every node boots from the image matching its own architecture. Architectures that belong only
     * to node groups with zero nodes are not resolved, so `up` does not demand an unused image.
     */
    private fun resolveAmisByArch(
        initConfig: InitConfig,
        launchingGroups: List<NodeGroup>,
    ): Map<Arch, String> =
        launchingGroups
            .map { it.arch }
            .distinct()
            .associateWith { arch ->
                amiResolver.resolveAmiId(initConfig.ami, arch.type).getOrElse { error ->
                    error(error.message ?: "Failed to resolve AMI for architecture $arch")
                }
            }

    /**
     * Logs the per-group architecture/AMI decision on the success path so a mixed-architecture
     * launch is diagnosable: each launching group's instance type, derived CPU architecture, and the
     * AMI selected for it.
     */
    private fun logResolvedGroupAmis(
        launchingGroups: List<NodeGroup>,
        amiByArch: Map<Arch, String>,
    ) {
        log.info {
            "Resolved per-group AMIs: " +
                launchingGroups.joinToString { group ->
                    "${group.serverType}[${group.instanceType}/${group.arch}]=${amiByArch[group.arch]}"
                }
        }
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
        sink: LaunchSink,
        launched: CompletableFuture<Unit>,
    ) = clusterProvisioningService.provisionAll(
        instanceConfig = instanceConfig,
        servicesConfig = servicesConfig,
        existingHosts = existingHosts,
        callbacks =
            ProvisioningCallbacks(
                // Called once per RunInstances batch as it launches: the hosts are saved first, so state
                // holds every launched instance whatever happens next, then each starts its pipeline.
                onHostsCreated = { serverType, hosts ->
                    writer.mergeHosts(serverType, hosts)
                    sink.launched(serverType, hosts, LaunchSink.Origin.LAUNCHED)
                },
                onEmrCreated = { emrState ->
                    writer.update { it.updateEmrCluster(emrState) }
                    eventBus.emit(Event.Provision.EmrReady(emrState.masterPublicDns ?: "unknown"))
                },
                onOpenSearchCreated = { osState ->
                    writer.update { it.updateOpenSearchDomain(osState) }
                    eventBus.emit(Event.Provision.OpenSearchReady(osState.endpoint ?: "unknown"))
                    osState.dashboardsEndpoint?.let {
                        eventBus.emit(Event.Provision.OpenSearchDashboards("Dashboards: $it"))
                    }
                },
                // Every host is known: the control pipeline may write the workspace files.
                onInstancesLaunched = { launched.complete(Unit) },
            ),
    )

    private fun reportProvisioningFailures(result: ProvisioningResult) {
        if (result.errors.isNotEmpty()) {
            eventBus.emit(Event.Provision.InfrastructureFailureHeader)
            result.errors.forEach { (resource, error) ->
                eventBus.emit(provisioningFailureEvent(resource, error))
            }
            error(
                "Failed to create ${result.errors.size} infrastructure resource(s): " +
                    result.errors.keys.joinToString(", "),
            )
        }
    }

    /** A capacity shortage gets its own event so it is not mistaken for an easy-db-lab failure. */
    private fun provisioningFailureEvent(
        resource: String,
        error: Exception,
    ): Event.Provision =
        when (error) {
            is InsufficientInstanceCapacityException ->
                Event.Provision.InstanceCapacityShortage(error.serverType, error.instanceType, error.availabilityZone)
            else -> Event.Provision.InfrastructureFailure(resource, error.message ?: "Unknown error")
        }

    /** Records the network `up` resolved and marks the infrastructure up. */
    private fun finalizeInfrastructureState(
        vpcId: String,
        vpcInfra: VpcInfrastructure,
    ) = writer.update {
        it.updateInfrastructure(
            InfrastructureState(
                vpcId = vpcId,
                region = userConfig.region,
                subnetIds = vpcInfra.subnetIds,
                securityGroupId = vpcInfra.securityGroupId,
                internetGatewayId = vpcInfra.internetGatewayId,
            ),
        )
        it.markInfrastructureUp()
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
}
