package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CiliumInstallServiceTest : BaseKoinTest() {
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var ciliumService: CiliumInstallService
    private val emittedEvents = mutableListOf<Event>()

    private val controlHost =
        Host(
            public = "54.1.2.3",
            private = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<RemoteOperationsService> { mockRemoteOps }
                single<EventBus> {
                    EventBus().also { bus ->
                        bus.addListener(
                            object : EventListener {
                                override fun onEvent(envelope: EventEnvelope) {
                                    emittedEvents.add(envelope.event)
                                }

                                override fun close() {}
                            },
                        )
                    }
                }
                factory<CiliumInstallService> { DefaultCiliumInstallService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockRemoteOps = mock()
        emittedEvents.clear()
        ciliumService = getKoin().get()
    }

    @Test
    fun `install runs helm repo add then helm upgrade install`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(controlHost), any(), any(), any()))
            .thenReturn(successResponse)

        ciliumService.install(controlHost)

        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, times(2)).executeRemotely(eq(controlHost), captor.capture(), any(), any())

        val repoCmd = captor.firstValue
        assertThat(repoCmd).contains("helm repo add cilium")
        assertThat(repoCmd).contains("https://helm.cilium.io/")

        val installCmd = captor.secondValue
        assertThat(installCmd).contains("helm upgrade --install cilium")
        assertThat(installCmd).contains("kubeProxyReplacement=true")
        assertThat(installCmd).contains("hubble.relay.enabled=true")
        assertThat(installCmd).contains("hubble.ui.enabled=true")
        assertThat(installCmd).contains("hubble.metrics.enabled")
        assertThat(installCmd).contains("--namespace kube-system")
        assertThat(installCmd).contains("--wait")
    }

    @Test
    fun `install emits Installed event on success`() {
        val successResponse = Response(text = "", stderr = "")
        whenever(mockRemoteOps.executeRemotely(eq(controlHost), any(), any(), any()))
            .thenReturn(successResponse)

        val result = ciliumService.install(controlHost)

        assertThat(result.isSuccess).isTrue()
        assertThat(emittedEvents).contains(Event.Cilium.Installed)
    }

    @Test
    fun `install emits InstallFailed event and returns failure when helm throws`() {
        whenever(mockRemoteOps.executeRemotely(eq(controlHost), any(), any(), any()))
            .thenThrow(RuntimeException("helm: command not found"))

        val result = ciliumService.install(controlHost)

        assertThat(result.isFailure).isTrue()
        val failedEvents = emittedEvents.filterIsInstance<Event.Cilium.InstallFailed>()
        assertThat(failedEvents).hasSize(1)
        assertThat(failedEvents.first().error).contains("helm: command not found")
    }

    @Test
    fun `start-k3s-server script disables flannel for Cilium CNI`() {
        val scriptPath = "/com/rustyrazorblade/easydblab/services/start-k3s-server.sh"
        val script =
            javaClass.getResourceAsStream(scriptPath)?.bufferedReader()?.readText()
                ?: error("start-k3s-server.sh not found on classpath at $scriptPath")

        assertThat(script).contains("--flannel-backend=none")
        assertThat(script).contains("--disable-network-policy")
    }
}
