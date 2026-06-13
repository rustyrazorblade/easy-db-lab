package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
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
        whenever(mockK8sService.deleteConfigMapsByLabels(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockOtelSyncService.syncConfigMap(any())).thenReturn(Result.success(Unit))

        service = DefaultMetricsRegistryService(mockK8sService, mockOtelSyncService, eventBus)
    }

    @Test
    fun `register creates ConfigMap with workload-metrics and kit labels`() {
        val target = KitMetrics.Scrape(port = 9363, path = "/metrics")
        service.register(controlHost = controlHost, kitName = "clickhouse", targets = listOf(target))

        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-clickhouse-clickhouse",
            data =
                mapOf(
                    "kit-name" to "clickhouse",
                    "job-name" to "clickhouse",
                    "port" to "9363",
                    "path" to "/metrics",
                ),
            labels =
                mapOf(
                    Constants.K8s.WORKLOAD_METRICS_LABEL to "true",
                    Constants.K8s.KIT_LABEL to "clickhouse",
                ),
        )
    }

    @Test
    fun `register uses kitName-jobName as ConfigMap name when job is provided`() {
        val target = KitMetrics.Scrape(port = 20180, path = "/metrics", job = "tikv")
        service.register(controlHost = controlHost, kitName = "tidb", targets = listOf(target))

        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-tidb-tikv",
            data =
                mapOf(
                    "kit-name" to "tidb",
                    "job-name" to "tikv",
                    "port" to "20180",
                    "path" to "/metrics",
                ),
            labels =
                mapOf(
                    Constants.K8s.WORKLOAD_METRICS_LABEL to "true",
                    Constants.K8s.KIT_LABEL to "tidb",
                ),
        )
    }

    @Test
    fun `register creates one ConfigMap per target for multi-target kit`() {
        val targets =
            listOf(
                KitMetrics.Scrape(port = 31080, path = "/metrics", job = "tidb-sql"),
                KitMetrics.Scrape(port = 32180, path = "/metrics", job = "tikv"),
                KitMetrics.Scrape(port = 32379, path = "/metrics", job = "pd"),
                KitMetrics.Scrape(port = 32234, path = "/metrics", job = "tiflash"),
            )
        service.register(controlHost = controlHost, kitName = "tidb", targets = targets)

        val tidbLabels = mapOf(Constants.K8s.WORKLOAD_METRICS_LABEL to "true", Constants.K8s.KIT_LABEL to "tidb")
        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-tidb-tidb-sql",
            data = mapOf("kit-name" to "tidb", "job-name" to "tidb-sql", "port" to "31080", "path" to "/metrics"),
            labels = tidbLabels,
        )
        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-tidb-tikv",
            data = mapOf("kit-name" to "tidb", "job-name" to "tikv", "port" to "32180", "path" to "/metrics"),
            labels = tidbLabels,
        )
        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-tidb-pd",
            data = mapOf("kit-name" to "tidb", "job-name" to "pd", "port" to "32379", "path" to "/metrics"),
            labels = tidbLabels,
        )
        verify(mockK8sService).createConfigMap(
            controlHost = controlHost,
            namespace = "default",
            name = "easydblab-metrics-tidb-tiflash",
            data = mapOf("kit-name" to "tidb", "job-name" to "tiflash", "port" to "32234", "path" to "/metrics"),
            labels = tidbLabels,
        )
    }

    @Test
    fun `register emits MetricsRegistered event after sync`() {
        val events = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() {}
            },
        )

        val targets =
            listOf(
                KitMetrics.Scrape(port = 9363, path = "/metrics"),
            )
        service.register(controlHost = controlHost, kitName = "clickhouse", targets = targets)

        assertThat(events).anyMatch { it is Event.Kit.MetricsRegistered && it.kit == "clickhouse" && it.ports == listOf(9363) }
    }

    @Test
    fun `register syncs OTel ConfigMap after registering`() {
        val target = KitMetrics.Scrape(port = 9363, path = "/metrics")
        service.register(controlHost = controlHost, kitName = "clickhouse", targets = listOf(target))

        verify(mockOtelSyncService).syncConfigMap(controlHost)
    }

    @Test
    fun `deregister deletes ConfigMaps by label selector`() {
        service.deregister(controlHost = controlHost, kitName = "clickhouse")

        verify(mockK8sService).deleteConfigMapsByLabels(
            controlHost = controlHost,
            namespace = "default",
            labels =
                mapOf(
                    Constants.K8s.WORKLOAD_METRICS_LABEL to "true",
                    Constants.K8s.KIT_LABEL to "clickhouse",
                ),
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
    fun `deregister is a no-op when no ConfigMaps exist`() {
        whenever(mockK8sService.deleteConfigMapsByLabels(any(), any(), any()))
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

        val target = KitMetrics.Scrape(port = 9363, path = "/metrics")
        val result = service.register(controlHost = controlHost, kitName = "clickhouse", targets = listOf(target))

        assertThat(result.isFailure).isTrue()
        // deregister() is called as cleanup on failure, which triggers a sync to remove any
        // partial ConfigMaps. One syncConfigMap call is expected from that cleanup path.
        verify(mockOtelSyncService).syncConfigMap(controlHost)
    }
}
