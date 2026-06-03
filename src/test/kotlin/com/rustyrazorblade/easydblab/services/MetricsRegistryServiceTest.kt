package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MetricsRegistryServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockOtelSyncService: OtelSyncService
    private lateinit var eventBus: EventBus
    private lateinit var service: MetricsRegistryService

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
                single { mock<OtelSyncService>().also { mockOtelSyncService = it } }
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sService = getKoin().get()
        mockOtelSyncService = getKoin().get()
        eventBus = EventBus()

        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.deleteConfigMap(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockOtelSyncService.syncConfigMap(any())).thenReturn(Result.success(Unit))

        service = DefaultMetricsRegistryService(mockK8sService, mockOtelSyncService, eventBus)
    }

    @Test
    fun `register creates ConfigMap with workload-metrics label`() {
        service.register(controlHost = controlHost, kitName = "clickhouse", port = 9363, path = "/metrics")

        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-clickhouse",
            data =
                mapOf(
                    "job-name" to "clickhouse",
                    "port" to "9363",
                    "path" to "/metrics",
                ),
            labels = mapOf("easydblab.com/workload-metrics" to "true"),
        )
    }

    @Test
    fun `register emits MetricsRegistered event`() {
        val events = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() {}
            },
        )

        service.register(controlHost = controlHost, kitName = "clickhouse", port = 9363, path = "/metrics")

        assertThat(events).anyMatch { it is Event.Kit.MetricsRegistered && it.kit == "clickhouse" && it.port == 9363 }
    }

    @Test
    fun `register syncs OTel ConfigMap after registering`() {
        service.register(controlHost = controlHost, kitName = "clickhouse", port = 9363, path = "/metrics")

        verify(mockOtelSyncService).syncConfigMap(controlHost)
    }

    @Test
    fun `deregister deletes ConfigMap`() {
        service.deregister(controlHost = controlHost, kitName = "clickhouse")

        verify(mockK8sService).deleteConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-clickhouse",
        )
    }

    @Test
    fun `deregister emits MetricsDeregistered event`() {
        val events = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() {}
            },
        )

        service.deregister(controlHost = controlHost, kitName = "clickhouse")

        assertThat(events).anyMatch { it is Event.Kit.MetricsDeregistered && it.kit == "clickhouse" }
    }

    @Test
    fun `deregister is a no-op when ConfigMap does not exist`() {
        whenever(mockK8sService.deleteConfigMap(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("not found")))

        val result = service.deregister(controlHost = controlHost, kitName = "nonexistent")

        assertThat(result.isSuccess).isTrue()
        verify(mockOtelSyncService).syncConfigMap(controlHost)
    }

    @Test
    fun `deregister syncs OTel ConfigMap after deregistering`() {
        service.deregister(controlHost = controlHost, kitName = "clickhouse")

        verify(mockOtelSyncService).syncConfigMap(controlHost)
    }

    @Test
    fun `register returns failure when ConfigMap creation fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s error")))

        val result = service.register(controlHost = controlHost, kitName = "clickhouse", port = 9363, path = "/metrics")

        assertThat(result.isFailure).isTrue()
        verify(mockOtelSyncService, never()).syncConfigMap(any())
    }
}
