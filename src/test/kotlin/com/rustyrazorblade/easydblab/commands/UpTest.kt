package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.ClusterConfigurationService
import com.rustyrazorblade.easydblab.services.ClusterProvisioningService
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K3sSetupResult
import com.rustyrazorblade.easydblab.services.K8sService
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Tests for [Up], the command that provisions and configures the complete cluster.
 *
 * These tests exercise the fail-fast invariant established by the `up-fail-fast` change: if
 * `up` reports success, every provisioning step actually succeeded. Every failure site is
 * driven through a full [Up.execute] call against a fully-wired "happy path" fixture, with a
 * single collaborator overridden per test to induce the failure under test — proving the
 * behavior actually aborts `up`, not merely that a mock was called.
 */
class UpTest : BaseKoinTest() {
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockS3BucketService: AwsS3BucketService
    private lateinit var mockVpcService: VpcService
    private lateinit var mockAwsInfrastructureService: AwsInfrastructureService
    private lateinit var mockEc2InstanceService: EC2InstanceService
    private lateinit var mockAmiResolver: AMIResolver
    private lateinit var mockClusterProvisioningService: ClusterProvisioningService
    private lateinit var mockClusterConfigurationService: ClusterConfigurationService
    private lateinit var mockK3sClusterService: K3sClusterService
    private lateinit var mockK8sService: K8sService
    private lateinit var mockCommandExecutor: CommandExecutor
    private lateinit var outputHandler: BufferedOutputHandler

    /** exit code returned by the fake CommandExecutor for a nested command, keyed by simple class name */
    private val nestedCommandExitCodes = mutableMapOf<String, Int>()

    /** simple class names of every nested command routed through the fake CommandExecutor, in order */
    private val invokedCommandNames = mutableListOf<String>()

    /** when non-null, remoteOps.executeRemotely throws this for the given host alias */
    private var sshFailureAlias: String? = null
    private var sshFailureException: Exception? = null
    private val sshCheckedAliases = mutableListOf<String>()

    private val testControlHost =
        ClusterHost(
            publicIp = "54.1.1.1",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )
    private val testDbHost =
        ClusterHost(publicIp = "54.1.1.2", privateIp = "10.0.0.2", alias = "db0", availabilityZone = "us-west-2a", instanceId = "i-db0")
    private val testAppHost =
        ClusterHost(publicIp = "54.1.1.3", privateIp = "10.0.0.3", alias = "app0", availabilityZone = "us-west-2a", instanceId = "i-app0")

    override fun additionalTestModules(): List<Module> =
        listOf(
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
                single<CiliumService> { mock<CiliumService>() }
                single<K8sService> { mock<K8sService>().also { mockK8sService = it } }
                single<RegistryService> { mock<RegistryService>() }
                single<SocksProxyService> { mock<SocksProxyService>() }
                single<CommandExecutor> { mock<CommandExecutor>().also { mockCommandExecutor = it } }

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
        mockK8sService = getKoin().get()
        mockCommandExecutor = getKoin().get()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        nestedCommandExitCodes.clear()
        invokedCommandNames.clear()
        sshFailureAlias = null
        sshFailureException = null
        sshCheckedAliases.clear()

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

        whenever(mockK8sService.labelNode(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.ensureLocalStorageClass(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.ensureLocalStorageWfcClass(any())).thenReturn(Result.success(Unit))

        whenever(mockCommandExecutor.execute<PicoCommand>(any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val factory = invocation.arguments[0] as () -> PicoCommand
            val command = factory()
            invokedCommandNames.add(command::class.simpleName ?: "unknown")
            nestedCommandExitCodes[command::class.simpleName] ?: 0
        }
    }

    private fun happyHosts(): Map<ServerType, List<ClusterHost>> =
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
    private fun happyState(
        controlInstances: Int = 1,
        cassandraInstances: Int = 1,
        stressInstances: Int = 1,
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
                    cidr = "10.0.0.0/16",
                    name = "test-cluster",
                ),
        )

    private fun tailscaleUser(): User =
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

    // =========================================================================
    // Baseline: the happy-path fixture itself must succeed end to end
    // =========================================================================

    @Test
    fun `up provisions successfully when every step succeeds`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockClusterProvisioningService).provisionAll(any(), any(), any(), any())
        verify(mockK8sService).labelNode(eq(testControlHost), eq("control0"), any())
        verify(mockK8sService).labelNode(eq(testControlHost), eq("db0"), any())
        verify(mockK8sService).labelNode(eq(testControlHost), eq("app0"), any())
        verify(mockK8sService).ensureLocalStorageClass(eq(testControlHost))
        verify(mockK8sService).ensureLocalStorageWfcClass(eq(testControlHost))
    }

    // =========================================================================
    // Group 4: cluster shape invariants
    // =========================================================================

    @Test
    fun `up fails before any EC2 instance is launched when configuration produces no control node`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(controlInstances = 0))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node is required")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
        verify(mockEc2InstanceService, never()).findInstancesByClusterId(any())
    }

    @Test
    fun `up fails before any EC2 instance is launched when no S3 bucket is configured`() {
        whenever(mockS3BucketService.ensureAccountBucket(any())).thenReturn("")

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("S3 bucket is required")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @Test
    fun `up provisions successfully with zero db nodes and emits no error output`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 0))
        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenReturn(
            ProvisioningResult(
                hosts = mapOf(ServerType.Control to listOf(testControlHost), ServerType.Stress to listOf(testAppHost)),
                errors = emptyMap(),
            ),
        )

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(outputHandler.errors).isEmpty()
        verify(mockK8sService, never()).labelNode(any(), eq("db0"), any())
        verify(mockK8sService).labelNode(eq(testControlHost), eq("app0"), any())
    }

    @Test
    fun `up does not resolve an AMI for the application architecture when there are zero app nodes`() {
        // A mixed-arch spec whose only arm64 group is the application group, sized to zero. `up`
        // must not demand (nor resolve) an arm64 image it will never launch.
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                tailscaleActive = true,
                initConfig =
                    InitConfig(
                        cassandraInstances = 1,
                        stressInstances = 0,
                        controlInstances = 1,
                        dbArch = "AMD64",
                        appArch = "ARM64",
                        controlArch = "AMD64",
                        cidr = "10.0.0.0/16",
                        name = "test-cluster",
                    ),
            ),
        )
        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenReturn(
            ProvisioningResult(
                hosts = mapOf(ServerType.Control to listOf(testControlHost), ServerType.Cassandra to listOf(testDbHost)),
                errors = emptyMap(),
            ),
        )

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockAmiResolver, never()).resolveAmiId(any(), eq(Arch.ARM64.type))
        verify(mockAmiResolver).resolveAmiId(any(), eq(Arch.AMD64.type))
    }

    // =========================================================================
    // Group 5: `up` fails fast at every swallow site
    // =========================================================================

    @Test
    fun `up aborts when the nested WriteConfig command fails`() {
        nestedCommandExitCodes["WriteConfig"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("WriteConfig")

        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    @Test
    fun `up aborts when the nested SetupInstance command fails`() {
        nestedCommandExitCodes["SetupInstance"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SetupInstance")

        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    @Test
    fun `up aborts when the nested ConfigureAxonOps command fails`() {
        val userWithAxonOps =
            tailscaleUser().copy(axonOpsOrg = "test-org", axonOpsKey = "test-key", tailscaleClientId = "", tailscaleClientSecret = "")
        overrideUser(userWithAxonOps)
        nestedCommandExitCodes["ConfigureAxonOps"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ConfigureAxonOps")
    }

    @Test
    fun `up aborts when the nested GrafanaUpdateConfig command fails`() {
        nestedCommandExitCodes["GrafanaUpdateConfig"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("GrafanaUpdateConfig")
    }

    @Test
    fun `up aborts when writeAllConfigurationFiles fails`() {
        whenever(mockClusterConfigurationService.writeAllConfigurationFiles(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("disk full")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("disk full")

        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    @Test
    fun `up aborts when k3s cluster setup reports failure`() {
        whenever(mockK3sClusterService.setupCluster(any())).thenReturn(
            K3sSetupResult(serverStarted = false, errors = mapOf("K3s server start" to Exception("connection refused"))),
        )

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("K3s cluster setup failed")

        verify(mockK8sService, never()).labelNode(any(), any(), any())
    }

    @Test
    fun `up aborts when ensureLocalStorageClass fails`() {
        whenever(mockK8sService.ensureLocalStorageClass(any())).thenReturn(Result.failure(RuntimeException("apply failed")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("apply failed")

        verify(mockK8sService, never()).ensureLocalStorageWfcClass(any())
        assertThat(invokedCommandNames).doesNotContain("GrafanaUpdateConfig")
    }

    @Test
    fun `up aborts when ensureLocalStorageWfcClass fails`() {
        whenever(mockK8sService.ensureLocalStorageWfcClass(any())).thenReturn(Result.failure(RuntimeException("apply failed")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("apply failed")
    }

    @Test
    fun `up aborts when labeling the control node fails`() {
        whenever(mockK8sService.labelNode(eq(testControlHost), eq("control0"), any()))
            .thenReturn(Result.failure(RuntimeException("k8s api unreachable")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("k8s api unreachable")

        verify(mockK8sService, times(1)).labelNode(any(), any(), any())
        assertThat(outputHandler.messages).doesNotContain("Node labeling complete")
    }

    @Test
    fun `up aborts when labeling a db node fails and never emits NodeLabelingComplete`() {
        whenever(mockK8sService.labelNode(eq(testControlHost), eq("db0"), any()))
            .thenReturn(Result.failure(RuntimeException("db label failed")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("db label failed")

        assertThat(outputHandler.messages.count { it == "Node labeling complete" }).isZero()
    }

    @Test
    fun `up aborts when labeling an app node fails after db labeling already succeeded`() {
        whenever(mockK8sService.labelNode(eq(testControlHost), eq("app0"), any()))
            .thenReturn(Result.failure(RuntimeException("app label failed")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("app label failed")

        // db's completion event fired before the app loop started and failed; app's never did.
        assertThat(outputHandler.messages.count { it == "Node labeling complete" }).isEqualTo(1)
    }

    @Test
    fun `up aborts before provisioning when reapplying the S3 policy fails`() {
        whenever(mockS3BucketService.attachS3Policy(any())).thenThrow(RuntimeException("access denied"))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("access denied")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @Test
    fun `up aborts when Tailscale fails to start and preserves the manual-start instruction`() {
        overrideUser(tailscaleUser())
        nestedCommandExitCodes["TailscaleStart"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easy-db-lab tailscale start")

        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    @Test
    fun `no-setup exits cleanly without touching K3s or nested setup commands`() {
        val command = newUp()
        command.noSetup = true

        assertThatCode { command.execute() }.doesNotThrowAnyException()

        assertThat(
            outputHandler.messages,
        ).contains("Skipping node setup.  You will need to run easy-db-lab setup-instance to complete setup")
        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    // =========================================================================
    // Group 6: control node SSH readiness
    // =========================================================================

    @Test
    fun `ssh readiness wait covers the control host, not only db hosts`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(sshCheckedAliases).contains("control0", "db0")
    }

    @Test
    fun `up aborts when the control node never accepts ssh`() {
        sshFailureAlias = "control0"
        // A non-retryable exception type: RetryUtil's SSH retry config only retries
        // SshException/IOException, so this fails on the first attempt instead of exhausting
        // the real 30-attempt / 10s-interval retry window, which would make this test take
        // minutes. The behavior under test — that a control-node SSH failure aborts `up` — is
        // exercised either way; only the wait time differs.
        sshFailureException = IllegalStateException("connection refused")

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("connection refused")

        verify(mockK3sClusterService, never()).setupCluster(any())
    }

    /**
     * Constructs an [Up] with a zero SSH startup delay so tests do not sit through the production
     * [Up.SSH_STARTUP_DELAY] pause. The delay's only effect is wall-clock timing, so removing it
     * does not change any behavior under test.
     */
    private fun newUp(): Up = Up(sshStartupDelay = Duration.ZERO)

    private fun overrideUser(user: User) {
        whenever(mockClusterStateManager.load()).thenReturn(happyState())
        getKoin().loadModules(listOf(module { single<User> { user } }), allowOverride = true)
    }
}
