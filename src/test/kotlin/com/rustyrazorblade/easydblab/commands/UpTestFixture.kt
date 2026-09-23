package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.network.TcpReachabilityProbe
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.CiliumNodeImageCheck
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.ClusterProvisioningService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K3sSetupResult
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.LocalTailscaleClient
import com.rustyrazorblade.easydblab.services.LocalTailscaleState
import com.rustyrazorblade.easydblab.services.ObservabilityStackService
import com.rustyrazorblade.easydblab.services.ProvisioningResult
import com.rustyrazorblade.easydblab.services.RegistryService
import com.rustyrazorblade.easydblab.services.aws.AMIResolver
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.services.aws.DefaultInstanceSpecFactory
import com.rustyrazorblade.easydblab.services.aws.EC2InstanceService
import com.rustyrazorblade.easydblab.services.aws.InstanceSpecFactory
import com.rustyrazorblade.easydblab.services.aws.InstanceTypeCapabilities
import com.rustyrazorblade.easydblab.services.aws.OpenSearchService
import com.rustyrazorblade.easydblab.ssh.Response
import org.junit.jupiter.api.BeforeEach
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Shared fixture for the [Up] tests: the fully-wired "happy path" collaborators, the
 * [ClusterState] factories, and the [Up] constructor with the production pauses zeroed.
 *
 * [UpTest] drives the provisioning invariants and every fail-fast site through it; [UpCiliumTest]
 * drives the Cilium CNI branch (install on the server-ready hook, the Tailscale masquerade chain,
 * the deferred Grafana annotations). One fixture, two classes, so neither has to re-wire the
 * dozen mocks and a failure in one branch reads in its own file.
 */
abstract class UpTestFixture : BaseKoinTest() {
    protected lateinit var mockClusterStateManager: ClusterStateManager
    protected lateinit var mockS3BucketService: AwsS3BucketService
    protected lateinit var mockVpcService: VpcService
    protected lateinit var mockAwsInfrastructureService: AwsInfrastructureService
    protected lateinit var mockEc2InstanceService: EC2InstanceService
    protected lateinit var mockAmiResolver: AMIResolver
    protected lateinit var mockClusterProvisioningService: ClusterProvisioningService
    protected lateinit var mockClusterConfigurationService: ClusterConfigurationService
    protected lateinit var mockK3sClusterService: K3sClusterService
    protected lateinit var mockCiliumService: CiliumService
    protected lateinit var mockGrafanaDashboardService: GrafanaDashboardService
    protected lateinit var mockK8sService: K8sService
    protected lateinit var mockCommandExecutor: CommandExecutor
    protected lateinit var mockObservabilityStackService: ObservabilityStackService
    protected lateinit var outputHandler: BufferedOutputHandler

    /** exit code returned by the fake CommandExecutor for a nested command, keyed by simple class name */
    protected val nestedCommandExitCodes = mutableMapOf<String, Int>()

    /** simple class names of every nested command routed through the fake CommandExecutor, in order */
    protected val invokedCommandNames = mutableListOf<String>()

    /** state the fake LocalTailscaleClient reports, and how many times `up` asked for it */
    protected var localTailscaleState: LocalTailscaleState = LocalTailscaleState.Connected
    protected var localTailscaleQueries = 0

    /**
     * answer the fake TcpReachabilityProbe gives once [tailnetProbesBeforeReachable] earlier probes
     * have answered false, and every "host:port" it was asked about
     */
    protected var tailnetReachable = true
    protected var tailnetProbesBeforeReachable = 0
    protected val probedTargets = mutableListOf<String>()

    /** when non-null, remoteOps.executeRemotely throws this for the given host alias */
    protected var sshFailureAlias: String? = null
    protected var sshFailureException: Exception? = null
    protected val sshCheckedAliases = mutableListOf<String>()

    /** Cilium node-fix paths the fake SSH reports missing, by host alias, and every alias asked */
    protected val missingCiliumFixes = mutableMapOf<String, List<String>>()
    protected val ciliumFixCheckedAliases = mutableListOf<String>()

    protected val testControlHost =
        ClusterHost(
            publicIp = "54.1.1.1",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )
    protected val testDbHost =
        ClusterHost(publicIp = "54.1.1.2", privateIp = "10.0.0.2", alias = "db0", availabilityZone = "us-west-2a", instanceId = "i-db0")
    protected val testAppHost =
        ClusterHost(publicIp = "54.1.1.3", privateIp = "10.0.0.3", alias = "app0", availabilityZone = "us-west-2a", instanceId = "i-app0")

    /**
     * The Grafana annotation path `up` uses for the Cilium install markers: a real
     * [CiliumInstallAnnotator] over a mocked [GrafanaDashboardService], so the tests assert what
     * was posted, not merely that something was.
     */
    protected fun ciliumAnnotationModule(): Module =
        module {
            single { mock<GrafanaDashboardService>().also { mockGrafanaDashboardService = it } }
            single { CiliumInstallAnnotator(get()) }
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            ciliumAnnotationModule(),
            module {
                single<ClusterStateManager> { mock<ClusterStateManager>().also { mockClusterStateManager = it } }
                single<AwsS3BucketService> { mock<AwsS3BucketService>().also { mockS3BucketService = it } }
                single<OpenSearchService> { mock<OpenSearchService>() }
                single<VpcService> { mock<VpcService>().also { mockVpcService = it } }
                single<AwsInfrastructureService> { mock<AwsInfrastructureService>().also { mockAwsInfrastructureService = it } }
                single<EC2InstanceService> { mock<EC2InstanceService>().also { mockEc2InstanceService = it } }
                single<HostOperationsService> { HostOperationsService(get()) }
                single<AMIResolver> { mock<AMIResolver>().also { mockAmiResolver = it } }
                single<InstanceSpecFactory> { DefaultInstanceSpecFactory() }
                single<ClusterProvisioningService> { mock<ClusterProvisioningService>().also { mockClusterProvisioningService = it } }
                single<ClusterConfigurationService> { mock<ClusterConfigurationService>().also { mockClusterConfigurationService = it } }
                single<K3sClusterService> { mock<K3sClusterService>().also { mockK3sClusterService = it } }
                single<CiliumService> { mock<CiliumService>().also { mockCiliumService = it } }
                single { CiliumNodeImageCheck(get()) }
                single<K8sService> { mock<K8sService>().also { mockK8sService = it } }
                single<RegistryService> { mock<RegistryService>() }
                single<SocksProxyService> { mock<SocksProxyService>() }
                single<CommandExecutor> { mock<CommandExecutor>().also { mockCommandExecutor = it } }
                single<ObservabilityStackService> {
                    mock<ObservabilityStackService>().also { mockObservabilityStackService = it }
                }

                single<LocalTailscaleClient> {
                    LocalTailscaleClient {
                        localTailscaleQueries++
                        localTailscaleState
                    }
                }
                single<TcpReachabilityProbe> {
                    TcpReachabilityProbe { host, port ->
                        probedTargets.add("$host:$port")
                        tailnetReachable && probedTargets.size > tailnetProbesBeforeReachable
                    }
                }

                factory<RemoteOperationsService> {
                    object : RemoteOperationsService {
                        override fun executeRemotely(
                            host: Host,
                            command: String,
                            output: Boolean,
                            secret: Boolean,
                        ): Response {
                            if (command == "echo 1") sshCheckedAliases.add(host.alias)
                            val failingAlias = sshFailureAlias
                            val failure = sshFailureException
                            if (failingAlias != null && failure != null && host.alias == failingAlias) {
                                throw failure
                            }
                            if (command.contains(Constants.Cilium.NODE_FIX_FILES.first())) {
                                ciliumFixCheckedAliases.add(host.alias)
                                return Response(missingCiliumFixes[host.alias].orEmpty().joinToString(separator = "") { "$it\n" })
                            }
                            return Response("")
                        }

                        override fun upload(
                            host: Host,
                            local: Path,
                            remote: String,
                        ) = Unit

                        override fun uploadDirectory(
                            host: Host,
                            localDir: File,
                            remoteDir: String,
                        ) = Unit

                        override fun uploadDirectory(
                            host: Host,
                            version: Version,
                        ) = Unit

                        override fun replaceDirectory(
                            host: Host,
                            localDir: File,
                            remoteDir: String,
                            owner: String,
                        ) = Unit

                        override fun download(
                            host: Host,
                            remote: String,
                            local: Path,
                        ) = Unit

                        override fun downloadDirectory(
                            host: Host,
                            remoteDir: String,
                            localDir: File,
                            includeFilters: List<String>,
                            excludeFilters: List<String>,
                        ) = Unit

                        override fun getRemoteVersion(
                            host: Host,
                            inputVersion: String,
                        ): Version = Version.fromString("5.0")
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockClusterStateManager = getKoin().get()
        mockS3BucketService = getKoin().get()
        mockVpcService = getKoin().get()
        mockAwsInfrastructureService = getKoin().get()
        mockEc2InstanceService = getKoin().get()
        mockAmiResolver = getKoin().get()
        mockClusterProvisioningService = getKoin().get()
        mockClusterConfigurationService = getKoin().get()
        mockK3sClusterService = getKoin().get()
        mockCiliumService = getKoin().get()
        mockGrafanaDashboardService = getKoin().get()
        mockK8sService = getKoin().get()
        mockCommandExecutor = getKoin().get()
        mockObservabilityStackService = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        nestedCommandExitCodes.clear()
        invokedCommandNames.clear()
        localTailscaleState = LocalTailscaleState.Connected
        localTailscaleQueries = 0
        tailnetReachable = true
        probedTargets.clear()
        sshFailureAlias = null
        sshFailureException = null
        sshCheckedAliases.clear()
        missingCiliumFixes.clear()
        ciliumFixCheckedAliases.clear()

        whenever(mockClusterStateManager.load()).thenReturn(happyState())

        whenever(mockS3BucketService.ensureAccountBucket(any())).thenReturn("easy-db-lab-test-bucket")

        whenever(mockVpcService.createVpc(any(), any(), any())).thenReturn("vpc-123")

        whenever(mockAwsInfrastructureService.setupVpcNetworking(any(), any())).thenReturn(
            VpcInfrastructure(
                vpcId = "vpc-123",
                subnetIds = listOf("subnet-1"),
                securityGroupId = "sg-1",
                internetGatewayId = "igw-1",
            ),
        )

        whenever(mockEc2InstanceService.findInstancesByClusterId(any())).thenReturn(emptyMap())
        whenever(mockEc2InstanceService.describeInstanceType(any()))
            .thenReturn(InstanceTypeCapabilities(hasInstanceStore = true, supportedArchitectures = listOf("x86_64")))

        whenever(mockAmiResolver.resolveAmiId(any(), any())).thenReturn(Result.success("ami-123"))

        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenReturn(
            ProvisioningResult(hosts = happyHosts(), errors = emptyMap()),
        )

        whenever(mockClusterConfigurationService.writeAllConfigurationFiles(any(), any(), any()))
            .thenReturn(Result.success(Unit))

        whenever(mockK3sClusterService.setupCluster(any())).thenReturn(K3sSetupResult(serverStarted = true))
        stubCiliumSuccess()

        whenever(mockK8sService.labelNode(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.ensureLocalStorageClass(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.ensureLocalStorageWfcClass(any())).thenReturn(Result.success(Unit))

        whenever(mockObservabilityStackService.deploy(any(), anyOrNull())).thenReturn(Result.success(Unit))

        whenever(mockCommandExecutor.execute<PicoCommand>(any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val factory = invocation.arguments[0] as () -> PicoCommand
            val command = factory()
            invokedCommandNames.add(command::class.simpleName ?: "unknown")
            nestedCommandExitCodes[command::class.simpleName] ?: 0
        }
    }

    /** Both Cilium steps succeed by default; a Cilium test overrides one to drive its failure. */
    private fun stubCiliumSuccess() {
        whenever(mockCiliumService.install(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockCiliumService.installTailscaleMasquerade(any())).thenReturn(Result.success(Unit))
    }

    protected fun happyHosts(): Map<ServerType, List<ClusterHost>> =
        mapOf(
            ServerType.Control to listOf(testControlHost),
            ServerType.Cassandra to listOf(testDbHost),
            ServerType.Stress to listOf(testAppHost),
        )

    /**
     * A ClusterState with everything `up` needs already present, and Tailscale marked active
     * so [Up.startProxyIfNeeded] and [Up.startTailscaleIfConfigured]'s inner body are both
     * skipped (the test User has blank Tailscale credentials) — keeping the happy-path fixture
     * from needing to model the SOCKS tunnel at all.
     */
    protected fun happyState(
        controlInstances: Int = 1,
        cassandraInstances: Int = 1,
        stressInstances: Int = 1,
        cni: CniMode = CniMode.Flannel,
        cidr: String? = "10.0.0.0/16",
    ): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            tailscaleActive = true,
            initConfig =
                InitConfig(
                    cassandraInstances = cassandraInstances,
                    stressInstances = stressInstances,
                    controlInstances = controlInstances,
                    cidr = cidr,
                    name = "test-cluster",
                    cni = cni,
                ),
        )

    protected fun tailscaleUser(): User =
        User(
            email = "test@example.com",
            region = "us-west-2",
            keyName = "test-key",
            awsProfile = "",
            awsAccessKey = "test-access-key",
            awsSecret = "test-secret",
            axonOpsOrg = "",
            axonOpsKey = "",
            tailscaleClientId = "tailscale-client-id",
            tailscaleClientSecret = "tailscale-client-secret",
        )

    /**
     * Constructs an [Up] with a zero SSH startup delay and a zero tailnet retry interval so tests
     * do not sit through the production pauses. Both only affect wall-clock timing, so removing
     * them does not change any behavior under test.
     */
    protected fun newUp(): Up = Up(sshStartupDelay = Duration.ZERO, tailnetRetryInterval = Duration.ZERO)

    protected fun overrideUser(user: User) {
        whenever(mockClusterStateManager.load()).thenReturn(happyState())
        getKoin().loadModules(listOf(module { single<User> { user } }), allowOverride = true)
    }
}
