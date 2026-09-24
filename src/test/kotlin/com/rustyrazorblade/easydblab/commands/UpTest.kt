package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.tailscale.TailscaleStart
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.services.K3sSetupResult
import com.rustyrazorblade.easydblab.services.LocalTailscaleState
import com.rustyrazorblade.easydblab.services.ProvisioningResult
import com.rustyrazorblade.easydblab.services.TailscaleApiException
import com.rustyrazorblade.easydblab.services.TailscaleAuthKey
import com.rustyrazorblade.easydblab.services.TailscaleService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
    fun `up aborts when the observability stack deployment fails`() {
        // Bring-up drives ObservabilityStackService.deploy directly rather than nesting the
        // GrafanaUpdateConfig command, so a deploy failure must surface as an aborted `up`.
        whenever(mockObservabilityStackService.deploy(any(), anyOrNull()))
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
        // Storage classes are set up before the observability stack, so aborting here means the
        // stack deploy is never reached.
        verify(mockObservabilityStackService, never()).deploy(any(), anyOrNull())
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
        verify(mockK3sClusterService).setupCluster(any())
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
    fun `ssh readiness still covers the control host under a scale-out --hosts filter`() {
        // A scale-out run like `up --hosts db0` must not narrow the control readiness check to the
        // db filter — control0 never matches "db0", so filtering it would verify zero hosts and
        // reopen the control-node readiness gap (issue #741). The db filter applies only to the
        // db/app check.
        val up = newUp().also { it.hosts.hostList = "db0" }

        assertThatCode { up.execute() }.doesNotThrowAnyException()

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

    @Test
    fun `up skips both Tailscale checks entirely when the cluster does not use Tailscale`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState().copy(tailscaleActive = false))
        // Both checks would fail outright if they ran; a non-Tailscale cluster must not consult
        // them at all, and must not pay for a local process invocation or a socket connect.
        localTailscaleState = LocalTailscaleState.NotInstalled
        tailnetReachable = false

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        assertThat(localTailscaleQueries).isZero()
        assertThat(probedTargets).isEmpty()
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
        verify(mockK3sClusterService).setupCluster(any())
    }

    @Test
    fun `up aborts before K3s when this machine never reaches the control node over the tailnet`() {
        tailnetReachable = false

        assertThatThrownBy { newUp().execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot reach it at 10.0.0.1:22 over the tailnet")
            .hasMessageContaining("after ${Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS} attempts")
            .hasMessageContaining("approve the subnet route 10.0.0.0/16")

        // Every attempt was spent before giving up; one failed connect is not a missing route.
        assertThat(probedTargets).hasSize(Constants.Tailscale.REACHABILITY_MAX_ATTEMPTS)
        verify(mockK3sClusterService, never()).setupCluster(any())
    }
}
