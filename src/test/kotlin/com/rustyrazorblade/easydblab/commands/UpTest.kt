package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStart
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.exceptions.RemoteCommandFailedException
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.output.NodeStream
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredInstance
import com.rustyrazorblade.easydblab.providers.aws.InsufficientInstanceCapacityException
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmForwardNotReadyException
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SocksProxyState
import com.rustyrazorblade.easydblab.services.InstanceProvisioningConfig
import com.rustyrazorblade.easydblab.services.K3sServer
import com.rustyrazorblade.easydblab.services.LocalTailscaleState
import com.rustyrazorblade.easydblab.services.ProvisioningCallbacks
import com.rustyrazorblade.easydblab.services.ProvisioningResult
import com.rustyrazorblade.easydblab.services.SsmTool
import com.rustyrazorblade.easydblab.services.SsmToolFault
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleAuthKey
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.bringup.ControlNodeFailedException
import org.apache.sshd.common.SshException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.IamException
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [Up], the command that provisions and configures the complete cluster.
 *
 * These tests exercise the fail-fast invariant established by the `up-fail-fast` change: if
 * `up` reports success, every provisioning step actually succeeded. Every failure site is
 * driven through a full [Up.execute] call against a fully-wired "happy path" fixture, with a
 * single collaborator overridden per test to induce the failure under test — proving the
 * behavior actually aborts `up`, not merely that a mock was called.
 */
class UpTest : UpTestFixture() {
    // =========================================================================
    // Baseline: the happy-path fixture itself must succeed end to end
    // =========================================================================

    @Test
    fun `up provisions successfully when every step succeeds`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockClusterProvisioningService).provisionAll(any(), any(), any(), any())
        verify(mockK8sService).labelNode(eq("control0"), any())
        // Every node was launched in this `up`, so each removes what an earlier instance under its alias left, once.
        assertThat(plane.cleaned).containsExactlyInAnyOrder("db0", "app0")
    }

    /**
     * At 1,000 nodes one-at-a-time registry TLS is 1,000 SSH round trips. The control node
     * configures its own TLS before it releases the certificate; every other node then configures
     * its own in its pipeline. The latch opens only once db0 and app0 are inside their TLS step
     * together, so a serial loop times out on it.
     */
    @Test
    fun `up configures registry TLS on every node, the db and app nodes in parallel`() {
        val nodes = CountDownLatch(2)
        val configured = Collections.synchronizedList(mutableListOf<String>())
        whenever(mockRegistryService.configureTlsOnNode(any(), any(), any())).thenAnswer { invocation ->
            val alias = invocation.getArgument<Host>(0).alias
            if (alias != "control0") {
                nodes.countDown()
                check(nodes.await(OVERLAP_WAIT_SECONDS, TimeUnit.SECONDS)) { "registry TLS ran one node at a time" }
            }
            configured.add(alias)
            Unit
        }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(configured).containsExactlyInAnyOrder("control0", "db0", "app0")
        assertThat(configured.first()).isEqualTo("control0")
    }

    /** Every `up` re-applies the bucket policy's delete deny, even on a bucket it configured before. */
    @Test
    fun `up re-applies the bucket policy`() {
        newUp().execute()

        verify(mockS3BucketService, atLeastOnce()).putBucketPolicy("easy-db-lab-test-bucket")
    }

    /** The test's document index is rebuilt with the operator's credentials once the account bucket is configured. */
    @Test
    fun `up rebuilds the test's document index after the account bucket is configured`() {
        newUp().execute()

        val state = argumentCaptor<ClusterState>()
        val order = inOrder(mockS3BucketService, mockDocumentService)
        order.verify(mockS3BucketService, atLeastOnce()).putBucketPolicy("easy-db-lab-test-bucket")
        order.verify(mockDocumentService).rebuildIndex(state.capture())
        assertThat(state.firstValue.s3Bucket).isEqualTo("easy-db-lab-test-bucket")
    }

    // =========================================================================
    // Group 4: cluster shape invariants
    // =========================================================================

    /** No provisioner exists for a local workspace yet: `up` says so before it loads the profile or calls AWS. */
    @Test
    fun `up in a K3s in Docker workspace reports no provisioner and makes no AWS call`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState().apply { environment = Environment.K3sDocker("/data") })
        getKoin().loadModules(listOf(module { single<User> { error("up must not load the profile") } }), allowOverride = true)
        val events = captureEvents()

        assertThatThrownBy { newUp().execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(events).contains(Event.Environment.ProvisionerUnavailable("up", "K3s in Docker"))
        verifyNoInteractions(
            mockAwsInfrastructureService,
            mockEc2InstanceService,
            mockClusterProvisioningService,
            mockDocumentService,
        )
        verify(mockClusterStateManager, never()).save(any())
    }

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
    fun `up fails before any EC2 instance is launched when a redirect endpoint is malformed`() {
        val state = happyState()
        val redirect =
            TelemetryRedirect.fromBaseHost("10.9.9.9").copy(traces = "http://10.9.9.9:4320")
        whenever(mockClusterStateManager.load()).thenReturn(
            state.copy(initConfig = state.initConfig?.copy(telemetryRedirect = redirect)),
        )

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("traces")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
        verify(mockEc2InstanceService, never()).findInstancesByClusterId(any())
    }

    @Test
    fun `up provisions successfully when a redirect target is well-formed`() {
        val state = happyState()
        val redirect = TelemetryRedirect.fromBaseHost("10.9.9.9")
        whenever(mockClusterStateManager.load()).thenReturn(
            state.copy(initConfig = state.initConfig?.copy(telemetryRedirect = redirect)),
        )

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()
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
        launching(mapOf(ServerType.Control to listOf(testControlHost), ServerType.Stress to listOf(testAppHost)))

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(outputHandler.errors).isEmpty()
        assertThat(plane.registered).containsExactly("app0")
        // With no database node the app node's stress service is pointed at no host.
        assertThat(uploadsNamed("stress.env").single()).contains("CASSANDRA_EASY_STRESS_CASSANDRA_HOST=\n")
    }

    /**
     * The instances `up` keeps and the ones it launches must come from one look at EC2: two looks
     * can disagree (an instance leaves pending between them), and then the count to launch and
     * the hosts kept in state do not match. Here the second look would see db0 gone.
     */
    @Test
    fun `up discovers the existing instances once and launches only what is missing`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 3))
        val db0 =
            DiscoveredInstance(
                instanceId = "i-db0",
                publicIp = "54.1.1.2",
                privateIp = "10.0.0.2",
                alias = "db0",
                availabilityZone = "us-west-2a",
                serverType = ServerType.Cassandra,
                state = "running",
            )
        whenever(mockEc2InstanceService.findInstancesByClusterId(any()))
            .thenReturn(mapOf(ServerType.Cassandra to listOf(db0)))
            .thenReturn(emptyMap())

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockEc2InstanceService, times(1)).findInstancesByClusterId(any())
        val config = argumentCaptor<InstanceProvisioningConfig>()
        val existing = argumentCaptor<Map<ServerType, List<ClusterHost>>>()
        verify(mockClusterProvisioningService).provisionAll(config.capture(), any(), existing.capture(), any())
        assertThat(
            config.firstValue.specs
                .single { it.serverType == ServerType.Cassandra }
                .neededCount,
        ).isEqualTo(2)
        assertThat(existing.firstValue[ServerType.Cassandra]?.map { it.alias }).containsExactly("db0")
        // db0 was found running and may own its Node and node password; the launched nodes remove an old one's.
        assertThat(plane.cleaned).doesNotContain("db0").contains("app0")
    }

    /**
     * A re-run inside an earlier `up`'s pending window discovers db0 not ready: still pending, or
     * already running but with no public IP yet (null or blank). `up` keeps it and gives it what a
     * new instance gets: its own pipeline waits until it runs and passes its status checks, and
     * records the public IP it was given, never a blank address. No wait holds the launch back.
     */
    @ParameterizedTest(name = "state {0}, public IP [{1}]")
    @CsvSource(value = ["pending,NULL", "running,NULL", "running,''"], nullValues = ["NULL"])
    fun `up waits for a discovered instance that is not ready in its own pipeline and keeps it with its assigned public IP`(
        state: String,
        publicIp: String?,
    ) {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 2))
        val pendingDb0 =
            DiscoveredInstance(
                instanceId = "i-db0",
                publicIp = publicIp,
                privateIp = "10.0.0.2",
                alias = "db0",
                availabilityZone = "us-west-2a",
                serverType = ServerType.Cassandra,
                state = state,
            )
        val runningDb1 =
            pendingDb0.copy(instanceId = "i-db1", publicIp = "54.1.1.5", privateIp = "10.0.0.5", alias = "db1", state = "running")
        whenever(mockEc2InstanceService.findInstancesByClusterId(any()))
            .thenReturn(mapOf(ServerType.Cassandra to listOf(pendingDb0, runningDb1)))
        launching(mapOf(ServerType.Control to listOf(testControlHost), ServerType.Stress to listOf(testAppHost)))
        publicIps["i-db0"] = "54.7.7.7"
        publicIps["i-db1"] = "54.1.1.5"
        val saved = captureSaves()

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockEc2InstanceService, never()).waitForInstancesRunning(any(), any())
        assertThat(saved.last().hosts[ServerType.Cassandra]?.associate { it.alias to it.publicIp })
            .containsExactlyEntriesOf(mapOf("db0" to "54.7.7.7", "db1" to "54.1.1.5"))
        assertThat(plane.registered).contains("db0", "db1")
    }

    /**
     * A partial launch persisted db0, db3 and db6 (subnet a's batch) before subnet b's failed. The
     * re-run fills exactly the missing ordinals, and the capacity checks count the same ones, in
     * the zones they land in: only b and c, so a type zone a no longer offers does not stop it.
     */
    @Test
    fun `up fills the ordinals a partial launch left missing, and checks capacity for exactly those`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = 9))
        val existing =
            listOf(0, 3, 6).map {
                DiscoveredInstance(
                    instanceId = "i-db$it",
                    publicIp = "54.1.1.$it",
                    privateIp = "10.0.0.$it",
                    alias = "db$it",
                    availabilityZone = "us-west-2a",
                    serverType = ServerType.Cassandra,
                    state = "running",
                )
            }
        whenever(mockEc2InstanceService.findInstancesByClusterId(any())).thenReturn(mapOf(ServerType.Cassandra to existing))
        accountCapacity.offeredZones = mapOf("i7i.xlarge" to setOf("us-west-2b", "us-west-2c"))
        val events = captureEvents()

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        val config = argumentCaptor<InstanceProvisioningConfig>()
        verify(mockClusterProvisioningService).provisionAll(config.capture(), any(), any(), any())
        assertThat(
            config.firstValue.specs
                .single { it.serverType == ServerType.Cassandra }
                .missingIndexes,
        ).containsExactly(1, 2, 4, 5, 7, 8)
        val verified = events.filterIsInstance<Event.Provision.LaunchCapacityVerified>().single()
        // Six db nodes, plus the control and app node this fixture launches.
        assertThat(verified.newNodes).isEqualTo(8)
    }

    /**
     * Provisioning may report a batch twice: at launch with no public IP, then with its final IPs.
     * Discovery finds db0..db2. Each report must merge by alias into what state holds: the hosts
     * saved are db0..db4, in order, once each, and db3 and db4 end with their final IPs. The gap
     * case finds db0, db2 and db10 and launches db1, db9 and db11: the hosts fill in by alias,
     * never duplicated, in numeric order (db2 before db9 before db10), not text order. Only the
     * saves the launch makes are compared; each node's pipeline saves its own from its own thread.
     */
    @ParameterizedTest
    @ValueSource(strings = ["contiguous", "gap"])
    fun `hosts reported at launch and again with final IPs are saved once each, by alias`(case: String) {
        val held = if (case == "gap") listOf(0, 2, 10) else listOf(0, 1, 2)
        val launched = if (case == "gap") listOf(1, 9, 11) else listOf(3, 4)

        fun host(
            ordinal: Int,
            publicIp: String,
        ) = ClusterHost(publicIp, "10.0.0.$ordinal", "db$ordinal", "us-west-2a", "i-db$ordinal")
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cassandraInstances = (held + launched).max() + 1))
        whenever(mockEc2InstanceService.findInstancesByClusterId(any())).thenReturn(
            mapOf(
                ServerType.Cassandra to
                    held.map {
                        DiscoveredInstance(
                            "i-db$it",
                            "54.0.0.$it",
                            "10.0.0.$it",
                            "db$it",
                            "us-west-2a",
                            ServerType.Cassandra,
                            "running",
                        )
                    },
            ),
        )
        // The provider reports what the launch does, so a pipeline's own save never changes an address.
        held.forEach { publicIps["i-db$it"] = "54.0.0.$it" }
        launched.forEach { publicIps["i-db$it"] = "54.9.9.$it" }
        val launchThread = AtomicReference<Thread>()
        val saves = Collections.synchronizedList(mutableListOf<List<ClusterHost>>())
        whenever(mockClusterStateManager.save(any())).thenAnswer {
            if (Thread.currentThread() == launchThread.get()) saves += it.getArgument<ClusterState>(0).hosts[ServerType.Cassandra].orEmpty()
            Unit
        }
        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenAnswer { invocation ->
            launchThread.set(Thread.currentThread())
            val callbacks = invocation.getArgument<ProvisioningCallbacks>(3)
            callbacks.onHostsCreated(ServerType.Control, listOf(testControlHost))
            callbacks.onHostsCreated(ServerType.Cassandra, launched.map { host(it, "") })
            callbacks.onHostsCreated(ServerType.Cassandra, launched.map { host(it, "54.9.9.$it") })
            callbacks.onInstancesLaunched()
            ProvisioningResult(hosts = happyHosts(), errors = emptyMap())
        }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        val afterLaunch = saves.first { hosts -> hosts.any { it.alias == "db${launched.last()}" } }
        val afterFinalIps = saves[saves.indexOf(afterLaunch) + 1]
        val expectedOrder = (held + launched).sorted().map { "db$it" }
        assertThat(afterLaunch.map { it.alias }).containsExactlyElementsOf(expectedOrder)
        assertThat(afterFinalIps.map { it.alias }).containsExactlyElementsOf(expectedOrder)
        assertThat(afterLaunch.filter { it.alias in launched.map { o -> "db$o" } }.map { it.publicIp }).containsOnly("")
        assertThat(afterFinalIps.associate { it.alias to it.publicIp })
            .containsAllEntriesOf(launched.associate { "db$it" to "54.9.9.$it" })
            .containsAllEntriesOf(held.associate { "db$it" to "54.0.0.$it" })
    }

    @Test
    fun `up does not resolve an AMI for the application architecture when there are zero app nodes`() {
        // A mixed-arch spec whose only arm64 group is the application group, sized to zero. `up`
        // must not demand (nor resolve) an arm64 image it will never launch.
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                networkMode = NetworkMode.Tailscale,
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
        launching(mapOf(ServerType.Control to listOf(testControlHost), ServerType.Cassandra to listOf(testDbHost)))

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockAmiResolver, never()).resolveAmiId(any(), eq(Arch.ARM64.type))
        verify(mockAmiResolver).resolveAmiId(any(), eq(Arch.AMD64.type))
    }

    // =========================================================================
    // Group 5: `up` fails fast at every swallow site
    // =========================================================================

    /** The workspace files are written in the control node's workspace step, so their failure is the control node's. */
    @Test
    fun `up fails at once when the nested WriteConfig command fails, before K3s starts`() {
        nestedCommandExitCodes["WriteConfig"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("control0 failed at workspace")
            .hasMessageContaining("write-config exited with code 1")
            // The control node's own event reported it; the executor must not print it again.
            .isInstanceOf(CommandFailedException::class.java)

        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
        assertThat(fleet.calls).isEmpty()
    }

    // =========================================================================
    // Per-node failure handling: replacement, its limits, and the control node
    // =========================================================================

    /** A node whose setup fails on its first instance is replaced under the same alias, and `up` succeeds. */
    @Test
    fun `a db node whose setup fails is replaced under the same alias and up succeeds`() {
        remoteFault = { host, command -> setupFailure().takeIf { host.instanceId == "i-db0" && command == SETUP_COMMAND } }
        val saved = captureSaves()
        val events = captureEvents()

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(fleet.calls).containsExactly("terminate i-db0", "verify db0", "launch db0")
        val db0 = saved.last().hosts[ServerType.Cassandra].orEmpty()
        assertThat(db0.map { it.alias to it.instanceId }).containsExactly("db0" to "i-db0-1")
        val replacing = events.filterIsInstance<Event.Provision.NodeReplacing>().single()
        assertThat(replacing.alias).isEqualTo("db0")
        assertThat(replacing.step).isEqualTo("setup")
        assertThat(replacing.reason).contains("no data disk")
        // An earlier `up` may have left db0's Node and node password, so the replacement removes them;
        // the first instance never reached its join, so it removed nothing.
        assertThat(plane.cleaned.filter { it == "db0" }).hasSize(1)
        assertThat(events.filterIsInstance<Event.Provision.NodesFailed>()).isEmpty()
    }

    /**
     * db0 names the database host in every node's environment and in the app nodes' `stress.env`,
     * so a replaced db0 has those pushed again with its new private IP.
     */
    @Test
    fun `a replaced db0 has the app nodes' stress environment pushed again with its new address`() {
        remoteFault = { host, command -> setupFailure().takeIf { host.instanceId == "i-db0" && command == SETUP_COMMAND } }

        newUp().execute()

        val stressEnv = uploadsNamed("stress.env")
        assertThat(stressEnv).hasSize(2)
        assertThat(stressEnv.last()).contains("CASSANDRA_EASY_STRESS_CASSANDRA_HOST=10.0.1.1")
    }

    /**
     * A node whose setup fails on every instance uses its two replacements, then fails, keeping its
     * last instance. The other nodes and the cluster-wide steps still finish, then `up` exits
     * non-zero naming the node and its step.
     */
    @Test
    fun `a db node that runs out of replacements fails up after the others and the post-steps finish`() {
        remoteFault = { host, command -> setupFailure().takeIf { host.alias == "db0" && command == SETUP_COMMAND } }
        val events = captureEvents()

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("1 node(s)")

        assertThat(fleet.calls.filter { it.startsWith("launch") }).containsExactly("launch db0", "launch db0")
        val failed =
            events
                .filterIsInstance<Event.Provision.NodesFailed>()
                .single()
                .nodes
                .single()
        assertThat(failed.alias).isEqualTo("db0")
        assertThat(failed.step).isEqualTo("setup")
        assertThat(failed.instanceId).isEqualTo("i-db0-2")
        assertThat(failed.reason).contains("2 replacements")
        assertThat(fleet.calls).doesNotContain("terminate i-db0-2")
        assertThat(plane.registered).contains("app0")
        verify(mockObservabilityStackService).deploy()
    }

    /** A registered node that never becomes Ready leaves its Node object, which is deleted before its replacement joins. */
    @Test
    fun `a registered node that never becomes Ready is removed from K3s and replaced`() {
        plane.neverReady += testDbHost.privateIp

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        // The first instance cleaned before its join, and the replacement again before its own.
        assertThat(plane.cleaned.filter { it == "db0" }).hasSize(2)
        assertThat(plane.registered.filter { it == "db0" }).hasSize(2)
        assertThat(fleet.calls).containsSubsequence("terminate i-db0", "launch db0")
    }

    /** An instance the provider ends mid-setup is replaced at once, with no retry of setup. */
    @Test
    fun `a db instance that ends during setup is replaced with no retry`() {
        val setupRuns = AtomicInteger()
        remoteFault = { host, command ->
            if (host.instanceId == "i-db0" && command == SETUP_COMMAND) {
                setupRuns.incrementAndGet()
                tracker.end("i-db0")
                IllegalStateException("connection reset")
            } else {
                null
            }
        }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(setupRuns.get()).isEqualTo(1)
        assertThat(fleet.calls).containsExactly("terminate i-db0", "verify db0", "launch db0")
    }

    /** The control node is never replaced: a step that fails after its retries fails `up` at once, keeping every instance. */
    @Test
    fun `up fails at once and keeps every instance when the K3s server does not start`() {
        whenever(mockK3sClusterService.startServer(any(), any(), anyOrNull(), any()))
            .thenThrow(IllegalStateException("K3s server start failed: connection refused"))
        val saved = captureSaves()

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("control0 failed at control-plane")
            .hasMessageContaining("connection refused")

        verify(mockK3sClusterService, times(Constants.Scale.CONTROL_STEP_ATTEMPTS)).startServer(any(), any(), anyOrNull(), any())
        verify(mockK3sClusterService, never()).joinAgent(any(), any(), any(), any())
        verify(mockK8sService, never()).labelNode(any(), any())
        assertThat(fleet.calls).isEmpty()
        assertThat(
            saved
                .last()
                .hosts.values
                .flatten()
                .map { it.alias },
        ).containsExactlyInAnyOrder("control0", "db0", "app0")
    }

    @Test
    fun `up aborts when the nested ConfigureAxonOps command fails`() {
        val userWithAxonOps =
            tailscaleUser().copy(axonOpsOrg = "test-org", axonOpsKey = "test-key", tailscaleClientId = "", tailscaleClientSecret = "")
        overrideUser(userWithAxonOps)
        nestedCommandExitCodes["ConfigureAxonOps"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("configure-axonops exited with code 1")
    }

    @Test
    fun `up aborts when the observability stack deployment fails`() {
        // Bring-up drives ObservabilityStackService.deploy directly rather than nesting the
        // GrafanaUpdateConfig command, so a deploy failure must surface as an aborted `up`.
        whenever(mockObservabilityStackService.deploy())
            .thenReturn(Result.failure(RuntimeException("dashboard upload rejected")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Observability stack deployment failed")
            .hasMessageContaining("dashboard upload rejected")
    }

    @Test
    fun `up aborts when writeAllConfigurationFiles fails`() {
        whenever(mockClusterConfigurationService.writeAllConfigurationFiles(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("disk full")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("disk full")

        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
    }

    /** The context is recorded on up's own state, so no later save of that state can erase it. */
    @Test
    fun `up records the kubeconfig's context in the cluster state it saves`() {
        whenever(mockK3sClusterService.startServer(any(), any(), anyOrNull(), any()))
            .thenReturn(K3sServer(K3S_URL, "token", kubeContext = "k3s-ctx"))
        val saved = captureSaves()

        newUp().execute()

        assertThat(saved.last().kubeContext).isEqualTo("k3s-ctx")
    }

    @Test
    fun `up creates no cluster-wide StorageClass for kits, which get theirs at install`() {
        newUp().execute()

        verify(mockK8sService, never()).storageClassReclaimPolicy(any())
    }

    @Test
    fun `up refuses a recorded kit StorageClass that deletes before deploying the stack`() {
        val state = happyState()
        whenever(mockClusterStateManager.load())
            .thenReturn(state.copy(initConfig = state.initConfig?.copy(kitStorageClass = "gp2")))
        whenever(mockK8sService.storageClassReclaimPolicy("gp2")).thenReturn(Result.success("Delete"))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("StorageClass gp2 reclaims with Delete")
            .hasMessageContaining("init --kit-storage-class")

        verify(mockObservabilityStackService, never()).deploy()
    }

    @Test
    fun `up accepts a recorded kit StorageClass that retains`() {
        val state = happyState()
        whenever(mockClusterStateManager.load())
            .thenReturn(state.copy(initConfig = state.initConfig?.copy(kitStorageClass = "retain-x")))
        whenever(mockK8sService.storageClassReclaimPolicy("retain-x")).thenReturn(Result.success("Retain"))

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()
        verify(mockObservabilityStackService).deploy()
    }

    @Test
    fun `up aborts when labeling the control node fails`() {
        whenever(mockK8sService.labelNode(eq("control0"), any()))
            .thenReturn(Result.failure(RuntimeException("k8s api unreachable")))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("control0 failed at cni")
            .hasMessageContaining("k8s api unreachable")

        verify(mockK8sService, times(Constants.Scale.CONTROL_STEP_ATTEMPTS)).labelNode(eq("control0"), any())
        verify(mockK3sClusterService, never()).joinAgent(any(), any(), any(), any())
    }

    /**
     * db and app nodes are labeled by their K3s agents when they register, so every node's
     * pipeline ends with it registered and Ready before anything schedules on it: the
     * observability stack comes after every pipeline.
     */
    @Test
    fun `every node is registered before the observability stack deploys`() {
        val registeredAtDeploy = mutableListOf<String>()
        whenever(mockObservabilityStackService.deploy()).thenAnswer {
            registeredAtDeploy += plane.registered
            Result.success(Unit)
        }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(registeredAtDeploy).containsExactlyInAnyOrder("db0", "app0")
        verify(mockK8sService, never()).labelNode(eq("db0"), any())
    }

    /** A role made by an older version must carry the Session Manager policy before any node launches under it. */
    @Test
    fun `up puts the Session Manager policy on the instance role before provisioning`() {
        val iam = getKoin().get<IamClient>()

        newUp().execute()

        val put = argumentCaptor<PutRolePolicyRequest>()
        val order = inOrder(iam, mockClusterProvisioningService)
        order.verify(iam, atLeastOnce()).putRolePolicy(put.capture())
        order.verify(mockClusterProvisioningService).provisionAll(any(), any(), any(), any())
        assertThat(put.allValues.map { it.roleName() to it.policyName() })
            .contains(Constants.AWS.Roles.EC2_INSTANCE_ROLE to Constants.AWS.InlinePolicies.SESSION_MANAGER)
    }

    @Test
    fun `up aborts before provisioning when reapplying the instance role policies fails`() {
        // 403 because the IAM retry config does not retry permission errors, so the test fails fast.
        val denied =
            IamException
                .builder()
                .message("access denied")
                .statusCode(403)
                .build()
        whenever(getKoin().get<IamClient>().putRolePolicy(any<PutRolePolicyRequest>())).thenThrow(denied)

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IamException::class.java)
            .hasMessageContaining("access denied")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @Test
    fun `up aborts when Tailscale fails to start and preserves the manual-start instruction`() {
        overrideUser(tailscaleUser())
        nestedCommandExitCodes["TailscaleStart"] = 1

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("easy-db-lab tailscale start")

        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
    }

    /**
     * Runs the real [TailscaleStart] under `up`, against a tailnet that refuses to delete the
     * device an earlier start recorded. Tailscale itself came up, so `up` carries on; only the
     * stale device is left for the user to remove by hand.
     */
    @Test
    fun `up continues when Tailscale starts but the previously recorded device cannot be removed`() {
        overrideUser(tailscaleUser())
        val state = happyState().apply { tailscaleDeviceId = "nOldCNTRL" }
        whenever(mockClusterStateManager.load()).thenReturn(state)
        val tailscaleService = mock<TailscaleService>()
        whenever(tailscaleService.isConnected(any())).thenReturn(Result.success(false))
        whenever(tailscaleService.generateAuthKey(any(), any(), any()))
            .thenReturn(TailscaleAuthKey(key = "auth-key", id = "key-id"))
        whenever(tailscaleService.startTailscale(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(tailscaleService.getDeviceId(any())).thenReturn(Result.success("nNewCNTRL"))
        whenever(tailscaleService.getStatus(any())).thenReturn(Result.success("Connected"))
        whenever(tailscaleService.deleteDevice(any(), any(), any()))
            .thenThrow(TailscaleApiException("not allowed to delete device nOldCNTRL"))
        getKoin().loadModules(listOf(module { single<TailscaleService> { tailscaleService } }), allowOverride = true)
        whenever(mockCommandExecutor.execute<PicoCommand>(any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val command = (invocation.arguments[0] as () -> PicoCommand)()
            if (command is TailscaleStart) command.call() else 0
        }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(state.tailscaleDeviceId).isEqualTo("nNewCNTRL")
        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("nOldCNTRL")
        verify(mockK3sClusterService).startServer(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `no-setup exits cleanly without touching K3s or nested setup commands`() {
        val command = newUp()
        command.noSetup = true

        assertThatCode { command.execute() }.doesNotThrowAnyException()

        assertThat(
            outputHandler.messages,
        ).contains("Skipping node setup.  You will need to run easy-db-lab setup-instance to complete setup")
        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
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
    fun `ssh readiness still covers the control host under a scale-out --hosts filter`() {
        // A scale-out run like `up --hosts db0` must not narrow the control readiness check to the
        // db filter — control0 never matches "db0", so filtering it would verify zero hosts and
        // reopen the control-node readiness gap (issue #741). The db filter applies only to the
        // db/app check.
        val up = newUp().also { it.hosts.hostList = "db0" }

        assertThatCode { up.execute() }.doesNotThrowAnyException()

        assertThat(sshCheckedAliases).contains("control0", "db0")
    }

    /**
     * A fresh node's SSM agent registers some time after the instance boots, and until then every
     * port forward fails with `TargetNotConnected`. `up` must keep waiting through that, not abort
     * on the first failure, and it must say why it is still waiting.
     */
    @Test
    fun `up keeps waiting while the control node's ssm agent has not registered yet`() {
        sshFailureAlias = "control0"
        sshFailuresRemaining = 3
        sshFailureException =
            SsmForwardNotReadyException(
                "i-control0",
                "An error occurred (TargetNotConnected) when calling the StartSession operation",
                "SSM port forward to i-control0:22 exited (exit code 254) before it was ready.",
            )

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(sshCheckedAliases.count { it == "control0" }).isEqualTo(4)
        verify(mockK3sClusterService).startServer(any(), any(), anyOrNull(), any())
    }

    /**
     * Under the direct transport, MINA reports a node whose sshd is not up yet as SshException,
     * a checked exception. `up` must retry it rather than abort on the first refused connection.
     */
    @Test
    fun `up keeps waiting while a direct-transport node refuses ssh connections`() {
        sshFailureAlias = "control0"
        sshFailuresRemaining = 3
        sshFailureException = SshException("Failed (ConnectException) to execute: Connection refused")

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(sshCheckedAliases.count { it == "control0" }).isEqualTo(4)
        verify(mockK3sClusterService).startServer(any(), any(), anyOrNull(), any())
    }

    /** An IOException that is not a connection failure will not clear by waiting, so `up` stops at once. */
    @Test
    fun `up fails at once on an IOException that is not an ssh connection failure`() {
        sshFailureAlias = "control0"
        sshFailureException = IOException("No such file or directory")

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("control0 failed at ssh")
            .hasMessageContaining("No such file or directory")

        assertThat(sshCheckedAliases.count { it == "control0" }).isEqualTo(1)
        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `up aborts when the control node never accepts ssh`() {
        sshFailureAlias = "control0"
        // A non-retryable exception type, so this fails on the first attempt: the behavior under
        // test is that a control-node SSH failure aborts `up`, not the length of the retry window.
        sshFailureException = IllegalStateException("connection refused")

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(ControlNodeFailedException::class.java)
            .hasMessageContaining("connection refused")

        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
    }

    // =========================================================================
    // Group 7: Tailscale routing pre-flight
    //
    // A Tailscale cluster starts no SOCKS tunnel, so an operator whose own machine is off the
    // tailnet has no route at all. These assert on the operator-facing message, not just the
    // exception type: the message is the whole point of failing early.
    // =========================================================================

    @Test
    fun `up fails before any EC2 instance is launched when the local Tailscale client is logged out`() {
        localTailscaleState = LocalTailscaleState.Disconnected("Stopped")

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("local Tailscale client is not connected")
            .hasMessageContaining("state: Stopped")
            .hasMessageContaining("Run 'tailscale up' on this machine")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
        verify(mockEc2InstanceService, never()).findInstancesByClusterId(any())
    }

    @Test
    fun `up names the missing binary, not a logged-out client, when tailscale is not installed`() {
        localTailscaleState = LocalTailscaleState.NotInstalled

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("the 'tailscale' command was not found on this machine")
            .hasMessageContaining("https://tailscale.com/download")
            // The remedy for an absent binary is to install it, not to run `tailscale up`.
            .hasMessageNotContaining("local Tailscale client is not connected")

        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @ParameterizedTest
    @EnumSource(value = NetworkMode::class, names = ["Socks", "Direct"])
    fun `up skips both Tailscale checks entirely when the cluster does not use Tailscale`(mode: NetworkMode) {
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(networkMode = mode))
        // Both checks would fail outright if they ran; a non-Tailscale cluster must not consult
        // them at all, and must not pay for a local process invocation or a socket connect.
        localTailscaleState = LocalTailscaleState.NotInstalled
        tailnetReachable = false

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(localTailscaleQueries).isZero()
        assertThat(probedTargets).isEmpty()
    }

    @Test
    fun `up reports the SOCKS tunnel it started on a cluster that does not use Tailscale`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(networkMode = NetworkMode.Socks))
        val events = captureEvents()

        newUp().execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = false))
    }

    @Test
    fun `up --no-setup reports the SOCKS tunnel it starts or reuses, once`() {
        // Under --no-setup the tunnel may already come from the @RequiresProxy preflight, which reports nothing.
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(networkMode = NetworkMode.Socks))
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = false))
    }

    @Test
    fun `up --no-setup reports a tunnel the preflight started as started, and checks it again before K3s`() {
        tunnelFromPreflight(reused = false)
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = false))
        // Once for the preflight, once by up itself, which restarts a tunnel that died in between.
        verify(getKoin().get<SocksProxyService>(), times(2)).ensureRunning(any())
    }

    @Test
    fun `up reports a tunnel the preflight started as started, and checks it again before K3s`() {
        tunnelFromPreflight(reused = false)
        val events = captureEvents()

        newUp().execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = false))
        verify(getKoin().get<SocksProxyService>(), times(2)).ensureRunning(any())
    }

    @Test
    fun `up --no-setup reports a tunnel the preflight found running as already running`() {
        tunnelFromPreflight(reused = true)
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = true))
    }

    @Test
    fun `a tunnel the preflight reused that died and was restarted on the same port is reported as started`() {
        // The recheck started a new tunnel; that it landed on the same port does not make it the reused one.
        tunnelFromPreflight(reused = true, later = { host -> tunnel(host, port = TUNNEL_PORT, reused = false) })
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = false))
    }

    @Test
    fun `a tunnel up's own check replaces on another port is reported as the new one`() {
        tunnelFromPreflight(reused = false, later = { host -> tunnel(host, port = OTHER_TUNNEL_PORT, reused = false) })
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>())
            .containsExactly(Event.Proxy.TunnelReady(OTHER_TUNNEL_PORT, reused = false))
    }

    @Test
    fun `a preflight tunnel to another control node is not reported, and up asks for one to the current node`() {
        val oldControl = testControlHost.copy(privateIp = "10.0.9.9")
        tunnelFromPreflight(reused = false, later = { host ->
            tunnel(host, port = TUNNEL_PORT, reused = true)
        }, preflightControl = oldControl)
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        verify(getKoin().get<SocksProxyService>()).ensureRunning(testControlHost)
        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).containsExactly(Event.Proxy.TunnelReady(TUNNEL_PORT, reused = true))
    }

    private fun tunnel(
        host: ClusterHost,
        port: Int,
        reused: Boolean,
    ) = SocksProxyState(localPort = port, gatewayHost = host, startTime = Instant.EPOCH, reused = reused)

    /**
     * Plays the executor: the `@RequiresProxy` preflight obtains the tunnel to [preflightControl]
     * before `up` runs, [reused] as the service returns it; `up`'s own later ask gets [later], by
     * default that tunnel running in memory.
     */
    private fun tunnelFromPreflight(
        reused: Boolean,
        later: (ClusterHost) -> SocksProxyState = { host -> tunnel(host, port = TUNNEL_PORT, reused = true) },
        preflightControl: ClusterHost = testControlHost,
    ) {
        val socks = getKoin().get<SocksProxyService>()
        whenever(socks.ensureRunning(any()))
            .thenAnswer { invocation -> tunnel(invocation.getArgument(0), port = TUNNEL_PORT, reused = reused) }
            .thenAnswer { invocation -> later(invocation.getArgument(0)) }
        // The preflight starts a tunnel only for a cluster whose infrastructure is up.
        whenever(mockClusterStateManager.exists()).thenReturn(true)
        whenever(mockClusterStateManager.load()).thenReturn(
            happyState()
                .copy(networkMode = NetworkMode.Socks, hosts = happyHosts() + (ServerType.Control to listOf(preflightControl)))
                .apply { markInfrastructureUp() },
        )
        getKoin().get<ProxyPreflight>().ensureTunnel()
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(networkMode = NetworkMode.Socks))
    }

    @Test
    fun `the SSH readiness check does not echo what each host printed`() {
        // `echo 1` proves a host answers; echoing its output put a bare "1" per host on the console.
        newUp().execute()

        assertThat(sshCheckedAliases).isNotEmpty()
        assertThat(echoedCommands).doesNotContain("echo 1")
    }

    @ParameterizedTest
    @EnumSource(value = Exposure::class, names = ["Direct", "PortForward"])
    fun `up starts and reports no SOCKS tunnel on a cluster not reached by SOCKS`(exposure: Exposure) {
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(networkMode = NetworkMode.Socks, exposure = exposure))
        val events = captureEvents()

        newUp().execute()

        verify(getKoin().get<SocksProxyService>(), never()).ensureRunning(any())
        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).isEmpty()
    }

    @Test
    fun `up prints each node's script output tagged with the node`() {
        newUp().execute()

        val printed = getKoin().get<RecordingNodeOutput>().stdout.toList()
        assertThat(printed).allSatisfy { assertThat(it).matches("""\[(control0|db0|app0)] +ran .+""") }
        // Every db and app node ran its setup script, and each one's line is under its own tag.
        val setupBy = printed.filter { "setup_instance.sh" in it }.map { it.substringBefore("]").removePrefix("[") }
        assertThat(setupBy).containsAll(listOf("db0", "app0"))
    }

    @Test
    fun `up --quiet prints no node output, but the Ready lines still print, and output is shown again afterwards`() {
        val events = captureEvents()
        val recorded = getKoin().get<RecordingNodeOutput>()

        newUp().apply { quiet = true }.execute()

        assertThat(recorded.printed).isEmpty()
        assertThat(recorded.tail("db0")).isNotEmpty()
        assertThat(events.filterIsInstance<Event.Provision.NodeReady>().map { it.alias }).contains("db0", "app0")
        assertThat(outputHandler.messages.joinToString("\n")).contains("db0 (", "is Ready after")

        recorded.line("db0", NodeStream.Stdout, "after up")

        assertThat(recorded.stdout).containsExactly("[db0] after up")
    }

    /**
     * Quiet hides a node's script while it runs; when the node fails for good its last output is
     * still shown, under the failure, and quiet ends with the failed `up`.
     */
    @Test
    fun `up --quiet still shows a failed node's last output under its failure, and ends quiet when up fails`() {
        val events = captureEvents()
        val recorded = getKoin().get<RecordingNodeOutput>()
        remoteFault = { host, command ->
            if (host.alias == "db0" && command == SETUP_COMMAND) {
                recorded.line("db0", NodeStream.Stderr, "ERROR: no data disk", host.instanceId)
                setupFailure()
            } else {
                null
            }
        }

        assertThatThrownBy { newUp().apply { quiet = true }.execute() }.isInstanceOf(CommandFailedException::class.java)

        // Quiet covers the pipelines only: no setup or K3s line and none of db0's lines printed. The
        // environment re-push after db0's replacement is a post-step, so its lines print as usual.
        assertThat(recorded.printed).noneMatch { "setup_instance.sh" in it || "k3s" in it || it.startsWith("[db0]") }
        val failed = events.filterIsInstance<Event.Provision.NodeFailed>().single()
        assertThat(failed.alias).isEqualTo("db0")
        assertThat(failed.outputTail).contains("stderr: ERROR: no data disk")
        assertThat(outputHandler.errors.joinToString("\n") { it.first })
            .contains("last output of db0:\n", "    stderr: ERROR: no data disk")

        recorded.line("db0", NodeStream.Stdout, "after up")

        // Quiet has ended: the line prints, its tag padded to the longest alias printed so far.
        assertThat(recorded.stdout.last()).matches("""\[db0] +after up""")
    }

    @Test
    fun `up --no-setup reports no SOCKS tunnel on a Tailscale cluster`() {
        val events = captureEvents()

        newUp().apply { noSetup = true }.execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).isEmpty()
    }

    @Test
    fun `up reports no SOCKS tunnel on a Tailscale cluster`() {
        val events = captureEvents()

        newUp().execute()

        assertThat(events.filterIsInstance<Event.Proxy.TunnelReady>()).isEmpty()
    }

    @Test
    fun `a capacity shortage is reported as one, with the instance type and zone`() {
        val shortage =
            InsufficientInstanceCapacityException(
                serverType = "app",
                instanceType = "c6id.4xlarge",
                availabilityZone = "us-west-2a",
                subnetId = "subnet-1",
                cause = RuntimeException("AWS refusal"),
            )
        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenReturn(
            ProvisioningResult(hosts = happyHosts(), errors = mapOf("app instances" to shortage)),
        )
        val events = captureEvents()

        assertThatThrownBy { newUp().execute() }.isInstanceOf(IllegalStateException::class.java)

        assertThat(events.filterIsInstance<Event.Provision.InstanceCapacityShortage>())
            .containsExactly(Event.Provision.InstanceCapacityShortage("app", "c6id.4xlarge", "us-west-2a"))
        assertThat(events.filterIsInstance<Event.Provision.InfrastructureFailure>()).isEmpty()
    }

    @Test
    fun `any other provisioning error is still reported as an infrastructure failure`() {
        whenever(mockClusterProvisioningService.provisionAll(any(), any(), any(), any())).thenReturn(
            ProvisioningResult(hosts = happyHosts(), errors = mapOf("db instances" to IllegalStateException("boom"))),
        )
        val events = captureEvents()

        assertThatThrownBy { newUp().execute() }.isInstanceOf(IllegalStateException::class.java)

        assertThat(events.filterIsInstance<Event.Provision.InfrastructureFailure>())
            .containsExactly(Event.Provision.InfrastructureFailure("db instances", "boom"))
        assertThat(events.filterIsInstance<Event.Provision.InstanceCapacityShortage>()).isEmpty()
    }

    /** Every event `up` emits; node pipelines emit from their own threads, so the list is concurrent. */
    private fun captureEvents(): List<Event> {
        val emitted = CopyOnWriteArrayList<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
        return emitted
    }

    @Test
    fun `up probes the control node's private IP over ssh before starting K3s`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        // sshd, not the Kubernetes API: K3s has not started yet at this point in `up`.
        assertThat(probedTargets).containsExactly("10.0.0.1:22")
    }

    @Test
    fun `up waits for the tailnet route and continues once the control node answers`() {
        // The subnet route reaches this machine some seconds after `tailscale up`; the first
        // probes fail and a later one succeeds. `up` must keep probing and then carry on to K3s.
        tailnetProbesBeforeReachable = 2

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(probedTargets).hasSize(3)
        verify(mockK3sClusterService).startServer(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `up aborts before K3s when this machine never reaches the control node over the tailnet`() {
        tailnetReachable = false

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("cannot reach it at 10.0.0.1:22 over the tailnet")
            .hasMessageContaining("after ${Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS} attempts")
            .hasMessageContaining("approve the subnet route 10.0.0.0/16")

        // Every probe was spent before giving up, in each attempt at the control node's tunnel step:
        // one failed connect is not a missing route.
        assertThat(probedTargets)
            .hasSize(Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS * Constants.Scale.CONTROL_STEP_ATTEMPTS)
        verify(mockK3sClusterService, never()).startServer(any(), any(), anyOrNull(), any())
    }

    // =========================================================================
    // SSM transport pre-flight
    //
    // Under the ssm transport every SSH connection, the readiness wait included, is opened by the
    // AWS CLI and the Session Manager plugin, so a machine without them must stop before AWS is
    // touched rather than after a five-minute readiness timeout.
    // =========================================================================

    @Test
    fun `up fails before touching AWS when the ssm transport's plugin is missing`() {
        overrideUser(ssmUser())
        ssmToolFaults = listOf(SsmToolFault.NotFound(SsmTool.SessionManagerPlugin))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'session-manager-plugin'")
            .hasMessageNotContaining("'aws'")

        // SsmToolsMissing is an error event, so the install hint goes to the error stream.
        assertThat(outputHandler.errors.joinToString("\n") { it.first }).contains("brew install --cask session-manager-plugin")
        verify(mockS3BucketService, never()).ensureAccountBucket(any())
        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    /** An installed tool that fails needs its own error read, not an install hint that would send the operator the wrong way. */
    @Test
    fun `up shows a failing tool's own output and no install hint`() {
        overrideUser(ssmUser())
        ssmToolFaults = listOf(SsmToolFault.Failed(SsmTool.AwsCli, 1, "dyld: Library not loaded: libpython3.11.dylib"))

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'aws'")

        val errors = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errors).contains("'aws --version' exited 1: dyld: Library not loaded: libpython3.11.dylib")
        assertThat(errors).doesNotContain("brew install")
        verify(mockClusterProvisioningService, never()).provisionAll(any(), any(), any(), any())
    }

    @Test
    fun `up provisions over ssm when the local tools are present`() {
        overrideUser(ssmUser())

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(localSsmToolingQueries).isEqualTo(1)
    }

    @Test
    fun `up never checks for SSM tools under the direct transport`() {
        ssmToolFaults = SsmTool.entries.map { SsmToolFault.NotFound(it) }

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(localSsmToolingQueries).isZero()
    }

    private fun ssmUser(): User = getKoin().get<User>().copy(sshTransport = SshTransport.Ssm)

    /** A copy of the state at every save, in order. */
    private fun captureSaves(): List<ClusterState> {
        val saved = Collections.synchronizedList(mutableListOf<ClusterState>())
        whenever(mockClusterStateManager.save(any())).thenAnswer {
            val state = it.getArgument<ClusterState>(0)
            synchronized(saved) { saved += state.copy(hosts = state.hosts) }
            Unit
        }
        return saved
    }

    /** `setup_instance.sh` failing as a node with no data disk does. */
    private fun setupFailure() = RemoteCommandFailedException(SETUP_COMMAND, "", "ERROR: no data disk", "setup failed")

    private companion object {
        const val OVERLAP_WAIT_SECONDS = 5L

        /** The db node's setup, with the default database instance type from the fixture's state. */
        const val SETUP_COMMAND = "sudo EDL_INSTANCE_TYPE=i7i.xlarge bash setup_instance.sh"
    }
}
