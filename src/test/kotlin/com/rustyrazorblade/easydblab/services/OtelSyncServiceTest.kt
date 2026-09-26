package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.AnyNamespaceOperation
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
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

class OtelSyncServiceTest : BaseKoinTest() {
    private lateinit var mockK8sClientProvider: K8sClientProvider
    private lateinit var mockK8sService: K8sService
    private lateinit var otelManifestBuilder: OtelManifestBuilder
    private lateinit var mockK8sClient: KubernetesClient
    private lateinit var mockFiltered: FilterWatchListDeletable<ConfigMap, ConfigMapList, Resource<ConfigMap>>
    private lateinit var service: OtelSyncService

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
                single { mock<K8sClientProvider>().also { mockK8sClientProvider = it } }
                single { mock<K8sService>().also { mockK8sService = it } }
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
        mockK8sClientProvider = getKoin().get()
        mockK8sService = getKoin().get()
        otelManifestBuilder = OtelManifestBuilder(getKoin().get())
        mockK8sClient = mock()

        val mockConfigMapOps =
            mock<MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockAnyNsOps =
            mock<AnyNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        mockFiltered = mock()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inAnyNamespace()).thenReturn(mockAnyNsOps)
        whenever(mockAnyNsOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)

        whenever(mockK8sClientProvider.createClient(any())).thenReturn(mockK8sClient)
        whenever(mockK8sService.applyResource(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any())).thenReturn(Result.success(emptyMap()))

        service =
            DefaultOtelSyncService(
                mockK8sClientProvider,
                mockK8sService,
                otelManifestBuilder,
                getKoin().get(),
                getKoin().get(),
                ConfigChangeReport(mockK8sService, getKoin().get()),
            )
    }

    @Test
    fun `syncConfigMap creates K8s client for the given host`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sClientProvider).createClient(controlHost)
    }

    @Test
    fun `syncConfigMap applies the OTel collector ConfigMap`() {
        val applied = captureApplied()

        service.syncConfigMap(controlHost)

        assertThat(applied.filterIsInstance<ConfigMap>().map { it.metadata.name }).containsExactly("otel-collector-config")
    }

    /** Every resource the sync applies, in order. */
    private fun captureApplied(): MutableList<HasMetadata> {
        val applied = mutableListOf<HasMetadata>()
        whenever(mockK8sService.applyResource(any(), any())).thenAnswer { invocation ->
            applied += invocation.getArgument<HasMetadata>(1)
            Result.success(Unit)
        }
        return applied
    }

    private fun appliedConfigHash(applied: List<HasMetadata>): String =
        applied
            .filterIsInstance<DaemonSet>()
            .single { it.metadata.name == Constants.OtelCollector.SERVICE_NAME }
            .spec.template.metadata.annotations
            .getValue(Constants.K8s.CONFIG_HASH_ANNOTATION)

    private fun registerKitScrapeTarget() {
        val metricsConfigMap =
            ConfigMap().apply {
                data = mapOf("kit-name" to "scylladb", "job-name" to "scylladb", "port" to "9180", "path" to "/metrics")
            }
        whenever(mockFiltered.list()).thenReturn(ConfigMapList().also { it.items = mutableListOf(metricsConfigMap) })
    }

    /** A kit start changes the scrape config, so the collector's template changes and it rolls. */
    @Test
    fun `syncConfigMap changes the collector hash when a kit registers a scrape target`() {
        val before = captureApplied()
        service.syncConfigMap(controlHost)

        registerKitScrapeTarget()
        val after = captureApplied()
        service.syncConfigMap(controlHost)

        assertThat(appliedConfigHash(after)).isNotEqualTo(appliedConfigHash(before))
    }

    private fun recordEvents(): MutableList<Event> {
        val emitted = mutableListOf<Event>()
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

    /** The rollout comes from the hash changing; a forced restart would leave the stale hash behind. */
    @Test
    fun `syncConfigMap does not force a rollout restart`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sService, never()).rolloutRestartDaemonSet(any(), any(), any())
    }

    @Test
    fun `syncConfigMap reports whether the collector configuration changed`() {
        val applied = captureApplied()
        service.syncConfigMap(controlHost)
        val collector = WorkloadRef(WorkloadKind.DaemonSet, Constants.OtelCollector.SERVICE_NAME)
        val runningHash = appliedConfigHash(applied)
        // An Answer bypasses Kotlin's Result unboxing: the JVM method returns the bare map.
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any())).thenAnswer { mapOf(collector to runningHash) }
        val events = recordEvents()

        service.syncConfigMap(controlHost)
        registerKitScrapeTarget()
        service.syncConfigMap(controlHost)

        assertThat(events.filterIsInstance<Event.Grafana.WorkloadConfigCompared>()).containsExactly(
            Event.Grafana.WorkloadConfigCompared(collector.toString(), changed = false),
            Event.Grafana.WorkloadConfigCompared(collector.toString(), changed = true),
        )
    }

    @Test
    fun `syncConfigMap returns failure when applyResource fails`() {
        whenever(mockK8sService.applyResource(any(), any()))
            .thenReturn(Result.failure(RuntimeException("K8s API error")))

        val result = service.syncConfigMap(controlHost)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `syncConfigMap closes the K8s client after use`() {
        service.syncConfigMap(controlHost)

        verify(mockK8sClient).close()
    }

    @Test
    fun `syncConfigMap applies ConfigMap containing dynamic jobs from workload scrape ConfigMaps`() {
        val metricsConfigMap =
            ConfigMap().apply {
                data =
                    mapOf(
                        "kit-name" to "scylladb",
                        "job-name" to "scylladb",
                        "port" to "9180",
                        "path" to "/metrics",
                    )
            }
        whenever(mockFiltered.list()).thenReturn(ConfigMapList().also { it.items = mutableListOf(metricsConfigMap) })
        val applied = captureApplied()

        service.syncConfigMap(controlHost)
        val appliedConfigMap = applied.filterIsInstance<ConfigMap>().singleOrNull()

        val yaml =
            checkNotNull(appliedConfigMap?.data?.get("otel-collector-config.yaml")) {
                "Applied ConfigMap must contain otel-collector-config.yaml"
            }
        assertThat(yaml).contains("scylladb")
        assertThat(yaml).contains("9180")
    }

    /**
     * A kit start/stop regenerates the whole ConfigMap. On a Cilium cluster that regeneration must
     * keep the Cilium scrape jobs, or the first kit start would silently drop agent and operator
     * metrics for the rest of the cluster's life.
     */
    @Test
    fun `syncConfigMap keeps the Cilium scrape jobs when the cluster state says Cilium`() {
        val ciliumState =
            ClusterState(
                name = "test",
                versions = mutableMapOf(),
                initConfig = InitConfig(region = "us-west-2", cni = CniMode.Cilium),
            )
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(ciliumState)
        val applied = captureApplied()

        service.syncConfigMap(controlHost)
        val appliedConfigMap = applied.filterIsInstance<ConfigMap>().singleOrNull()

        val yaml = checkNotNull(appliedConfigMap?.data?.get("otel-collector-config.yaml"))
        assertThat(yaml).contains("cilium-agent").contains("cilium-operator")
    }
}
