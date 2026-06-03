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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CiliumServiceTest : BaseKoinTest() {
    private val emittedEvents = mutableListOf<Event>()
    private lateinit var mockRemoteOps: RemoteOperationsService

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
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
            },
        )

    @BeforeEach
    fun setup() {
        emittedEvents.clear()
        mockRemoteOps = getKoin().get()
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any())).doReturn(Response(""))
    }

    private fun makeService(): DefaultCiliumService =
        DefaultCiliumService(
            remoteOps = getKoin().get(),
            eventBus = getKoin().get(),
        )

    @Test
    fun `install calls executeRemotely with cilium install command`() {
        makeService().install(controlHost)

        val commandCaptor = argumentCaptor<String>()
        verify(mockRemoteOps).executeRemotely(eq(controlHost), commandCaptor.capture(), any(), any())

        val command = commandCaptor.firstValue
        assertThat(command).contains("cilium install")
        assertThat(command).contains("--version 1.19.4")
        assertThat(command).contains("k8sServiceHost=${controlHost.private}")
        assertThat(command).contains("k8sServicePort=6443")
        assertThat(command).contains("tunnelProtocol=vxlan")
        assertThat(command).contains("routingMode=tunnel")
        assertThat(command).contains("operator.replicas=1")
        assertThat(command).contains("hubble.relay.enabled=true")
        assertThat(command).contains("hubble.ui.enabled=true")
        assertThat(command).contains("hubble.metrics.enabled=")
    }

    @Test
    fun `install emits Installing, InstallingChart, and Installed events on success`() {
        makeService().install(controlHost)

        assertThat(emittedEvents).contains(Event.Cilium.Installing)
        assertThat(emittedEvents).contains(Event.Cilium.InstallingChart)
        assertThat(emittedEvents).contains(Event.Cilium.Installed)
    }

    @Test
    fun `install emits InstallFailed and returns failure when executeRemotely throws`() {
        whenever(mockRemoteOps.executeRemotely(any(), any(), any(), any()))
            .doThrow(RuntimeException("cilium install failed"))

        val result = makeService().install(controlHost)

        assertThat(result.isFailure).isTrue()
        val failedEvents = emittedEvents.filterIsInstance<Event.Cilium.InstallFailed>()
        assertThat(failedEvents).hasSize(1)
        assertThat(failedEvents.first().error).contains("cilium install failed")
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
