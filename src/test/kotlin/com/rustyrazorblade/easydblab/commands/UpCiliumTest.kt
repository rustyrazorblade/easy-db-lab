package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.CiliumInstallAnnotator
import com.rustyrazorblade.easydblab.services.CiliumService
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationRequest
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationResponse
import com.rustyrazorblade.easydblab.services.K3sClusterConfig
import com.rustyrazorblade.easydblab.services.K3sClusterService
import com.rustyrazorblade.easydblab.services.K3sSetupResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for the Cilium branch of [Up]: the install on the K3s server-ready hook, the Tailscale
 * masquerade chain that follows it, and the install annotations posted once Grafana exists.
 * Shares the happy-path fixture with [UpTest] through [UpTestFixture].
 */
class UpCiliumTest : UpTestFixture() {
    // =========================================================================
    // Group 8: Cilium CNI installation on the server-ready hook
    // =========================================================================

    /**
     * Stubs [K3sClusterService.setupCluster] to invoke the config's `onServerReady` hook, as the
     * real implementation does once the K3s server is up. Without this, the mock would swallow the
     * callback and the Cilium install side effect would never fire — masking the branch under test.
     */
    private fun setupClusterInvokingServerReadyHook() {
        whenever(mockK3sClusterService.setupCluster(any())).thenAnswer { invocation ->
            val config = invocation.arguments[0] as K3sClusterConfig
            config.onServerReady?.invoke()
            K3sSetupResult(serverStarted = true)
        }
    }

    @Test
    fun `up installs Cilium with the control host and resolved VPC CIDR when cni is Cilium`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        whenever(mockCiliumService.install(any(), any())).thenReturn(Result.success(Unit))
        setupClusterInvokingServerReadyHook()

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        val hostCaptor = argumentCaptor<Host>()
        val cidrCaptor = argumentCaptor<String>()
        verify(mockCiliumService).install(hostCaptor.capture(), cidrCaptor.capture())
        assertThat(hostCaptor.firstValue.alias).isEqualTo("control0")
        assertThat(hostCaptor.firstValue.private).isEqualTo("10.0.0.1")
        assertThat(cidrCaptor.firstValue).isEqualTo("10.0.0.0/16")
    }

    @Test
    fun `up wires the K3s cluster for a custom CNI only when cni is Cilium`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        whenever(mockCiliumService.install(any(), any())).thenReturn(Result.success(Unit))
        setupClusterInvokingServerReadyHook()

        newUp().execute()

        val configCaptor = argumentCaptor<K3sClusterConfig>()
        verify(mockK3sClusterService).setupCluster(configCaptor.capture())
        assertThat(configCaptor.firstValue.useCustomCni).isTrue()
        assertThat(configCaptor.firstValue.onServerReady != null).isTrue()
    }

    @Test
    fun `up installs the Tailscale masquerade chain after Cilium when Tailscale is enabled`() {
        // happyState marks Tailscale active, so the control node is the subnet router.
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        setupClusterInvokingServerReadyHook()

        newUp().execute()

        val order = inOrder(mockCiliumService)
        order.verify(mockCiliumService).install(any(), any())
        val hostCaptor = argumentCaptor<Host>()
        order.verify(mockCiliumService).installTailscaleMasquerade(hostCaptor.capture())
        assertThat(hostCaptor.firstValue.alias).isEqualTo("control0")
    }

    @Test
    fun `up skips the Tailscale masquerade chain on a Cilium cluster without Tailscale`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium).copy(tailscaleActive = false))
        setupClusterInvokingServerReadyHook()

        newUp().execute()

        verify(mockCiliumService).install(any(), any())
        verify(mockCiliumService, never()).installTailscaleMasquerade(any())
    }

    @Test
    fun `up aborts when the Tailscale masquerade chain cannot be installed`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        whenever(mockCiliumService.installTailscaleMasquerade(any()))
            .thenReturn(Result.failure(RuntimeException("nft: command not found")))
        setupClusterInvokingServerReadyHook()

        assertThatThrownBy { newUp().execute() }.hasMessageContaining("nft")
    }

    @Test
    fun `up never installs Cilium and uses the default CNI when cni is Flannel`() {
        // happyState defaults to Flannel.
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockCiliumService, never()).install(any(), any())
        val configCaptor = argumentCaptor<K3sClusterConfig>()
        verify(mockK3sClusterService).setupCluster(configCaptor.capture())
        assertThat(configCaptor.firstValue.useCustomCni).isFalse()
        assertThat(configCaptor.firstValue.onServerReady == null).isTrue()
    }

    @Test
    fun `up auto-resolves an unset VPC CIDR and installs Cilium with the selected block`() {
        // When init left the CIDR unset, `up` auto-selects one before installing Cilium — so the
        // install must receive that resolved block, never a null. This is why installCilium's
        // requireNotNull guard cannot fire through a full run: the CIDR is always resolved first.
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium, cidr = null))
        whenever(mockVpcService.listAllVpcCidrs()).thenReturn(emptyList())
        whenever(mockCiliumService.install(any(), any())).thenReturn(Result.success(Unit))
        setupClusterInvokingServerReadyHook()

        newUp().execute()

        val cidrCaptor = argumentCaptor<String>()
        verify(mockCiliumService).install(any(), cidrCaptor.capture())
        assertThat(cidrCaptor.firstValue).isNotBlank()
    }

    // =========================================================================
    // Group 9: Cilium install annotations, posted once Grafana exists
    // =========================================================================

    /**
     * Stubs the mocked [CiliumService] to record on the real [CiliumInstallAnnotator], as
     * [com.rustyrazorblade.easydblab.services.DefaultCiliumService] does, so the test exercises the
     * hand-off from the server-ready hook to the post after the observability stack.
     */
    private fun ciliumInstallRecordsAnnotations() {
        val annotator: CiliumInstallAnnotator = getKoin().get()
        whenever(mockCiliumService.install(any(), any())).thenAnswer {
            annotator.installStarted()
            annotator.installFinished()
            Result.success(Unit)
        }
    }

    @Test
    fun `up posts the Cilium install annotations to Grafana only after the observability stack is up`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        ciliumInstallRecordsAnnotations()
        setupClusterInvokingServerReadyHook()
        var stackDeployed = false
        var annotationsPostedBeforeStack = false
        whenever(mockObservabilityStackService.deploy(any(), anyOrNull())).thenAnswer {
            stackDeployed = true
            Result.success(Unit)
        }
        whenever(mockGrafanaDashboardService.createAnnotation(any(), any())).thenAnswer { invocation ->
            if (!stackDeployed) annotationsPostedBeforeStack = true
            val request = invocation.getArgument<GrafanaAnnotationRequest>(1)
            GrafanaAnnotationResponse(id = request.time)
        }

        newUp().execute()

        assertThat(annotationsPostedBeforeStack).isFalse()
        val requestCaptor = argumentCaptor<GrafanaAnnotationRequest>()
        verify(mockGrafanaDashboardService, times(2)).createAnnotation(any(), requestCaptor.capture())
        assertThat(requestCaptor.allValues.map { it.text })
            .containsExactly(CiliumInstallAnnotator.STARTED_TEXT, CiliumInstallAnnotator.FINISHED_TEXT)
        assertThat(requestCaptor.allValues).allSatisfy { assertThat(it.tags).contains("cilium") }
        assertThat(getKoin().get<CiliumInstallAnnotator>().pending).isEmpty()
    }

    @Test
    fun `up does not post Cilium annotations on a redirect cluster, which has no local Grafana`() {
        val state = happyState(cni = CniMode.Cilium)
        whenever(mockClusterStateManager.load()).thenReturn(
            state.copy(initConfig = state.initConfig?.copy(telemetryRedirect = TelemetryRedirect.fromBaseHost("10.9.9.9"))),
        )
        ciliumInstallRecordsAnnotations()
        setupClusterInvokingServerReadyHook()

        newUp().execute()

        verify(mockGrafanaDashboardService, never()).createAnnotation(any(), any())
    }

    @Test
    fun `up never calls the Grafana annotation API on a Flannel cluster`() {
        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        verify(mockGrafanaDashboardService, never()).createAnnotation(any(), any())
    }

    @Test
    fun `a failed annotation post is reported and does not fail up`() {
        whenever(mockClusterStateManager.load()).thenReturn(happyState(cni = CniMode.Cilium))
        ciliumInstallRecordsAnnotations()
        setupClusterInvokingServerReadyHook()
        whenever(mockGrafanaDashboardService.createAnnotation(any(), any()))
            .thenThrow(IllegalStateException("Grafana annotation API at http://10.0.0.1:3000/api/annotations returned 502"))

        val emitted = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )

        assertThatCode { newUp().execute() }.doesNotThrowAnyException()

        val failures = emitted.filterIsInstance<Event.Grafana.AnnotationFailed>()
        assertThat(failures).hasSize(1)
        assertThat(failures.single().reason).contains("returned 502")
        assertThat(failures.single().isError()).isTrue()
    }
}
