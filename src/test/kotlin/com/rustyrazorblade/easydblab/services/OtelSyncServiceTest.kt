package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.fixedContract
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapList
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
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

    /** The capabilities the sync under test sees; AWS unless a test declares otherwise. */
    private var capabilities: NodeCapabilities = NodeCapabilities.AWS

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    /** An AWS cluster: its control node is where the backends publish their ports. */
    private val withControlHost =
        ClusterState(
            name = "test",
            s3Bucket = "easy-db-lab-test",
            hosts = mapOf(ServerType.Control to listOf(controlHost)),
        )

    /** A cluster brought by the operator: no control node in its state. */
    private val noControlHost = withControlHost.copy(hosts = emptyMap())

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sClientProvider>().also { mockK8sClientProvider = it } }
                single { mock<K8sService>().also { mockK8sService = it } }
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(withControlHost)
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
        val mockNamespacedOps =
            mock<NonNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        mockFiltered = mock()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inNamespace(any<String>())).thenReturn(mockNamespacedOps)
        whenever(mockNamespacedOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)

        whenever(mockK8sClientProvider.createClient()).thenReturn(mockK8sClient)
        whenever(mockK8sService.applyResource(any())).thenReturn(Result.success(Unit))
        stackDeployed(true)

        service =
            DefaultOtelSyncService(
                mockK8sClientProvider,
                mockK8sService,
                otelManifestBuilder,
                ObservabilityDeployInputs(getKoin().get(), fixedContract({ capabilities }), getKoin().get<ProfileRegion>()),
                ConfigChangeReport(mockK8sService, getKoin().get()),
                CollectorPresence(mockK8sService),
            )
    }

    @Test
    fun `syncConfigMap creates the K8s client for the control host in state`() {
        service.syncConfigMap()

        verify(mockK8sClientProvider).createClient()
    }

    @Test
    fun `syncConfigMap applies the OTel collector ConfigMap`() {
        val applied = captureApplied()

        service.syncConfigMap()

        assertThat(applied.filterIsInstance<ConfigMap>().map { it.metadata.name }).containsExactly("otel-collector-config")
    }

    @Test
    fun `a sync under an environment without CloudWatch renders no yace scrape job`() {
        val awsConfig = collectorConfigFromSync()
        capabilities = NodeCapabilities.LOCAL_DOCKER

        val localConfig = collectorConfigFromSync()

        assertThat(awsConfig).contains("job_name: \"yace\"")
        assertThat(localConfig).doesNotContain("yace")
    }

    private fun collectorConfigFromSync(): String {
        val applied = captureApplied()
        service.syncConfigMap()
        return applied
            .filterIsInstance<ConfigMap>()
            .single()
            .data.values
            .single()
    }

    /** Every resource the sync applies, in order. */
    private fun captureApplied(): MutableList<HasMetadata> {
        val applied = mutableListOf<HasMetadata>()
        whenever(mockK8sService.applyResource(any())).thenAnswer { invocation ->
            applied += invocation.getArgument<HasMetadata>(0)
            Result.success(Unit)
        }
        return applied
    }

    /**
     * Whether the observability stack is deployed, as the cluster tells it: the collector DaemonSet
     * the deploy stamped with its config hash, or none.
     */
    private fun stackDeployed(deployed: Boolean) {
        val collector = WorkloadRef(WorkloadKind.DaemonSet, Constants.OtelCollector.SERVICE_NAME)
        // An Answer bypasses Kotlin's Result unboxing: the JVM method returns the bare map.
        whenever(mockK8sService.workloadConfigHashes(any(), any())).thenAnswer {
            mapOf(collector to if (deployed) "deployed-hash" else null)
        }
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
        service.syncConfigMap()

        registerKitScrapeTarget()
        val after = captureApplied()
        service.syncConfigMap()

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
        service.syncConfigMap()

        verify(mockK8sService, never()).rolloutRestartDaemonSet(any(), any())
    }

    @Test
    fun `syncConfigMap reports whether the collector configuration changed`() {
        val applied = captureApplied()
        service.syncConfigMap()
        val collector = WorkloadRef(WorkloadKind.DaemonSet, Constants.OtelCollector.SERVICE_NAME)
        val runningHash = appliedConfigHash(applied)
        // An Answer bypasses Kotlin's Result unboxing: the JVM method returns the bare map.
        whenever(mockK8sService.workloadConfigHashes(any(), any())).thenAnswer { mapOf(collector to runningHash) }
        val events = recordEvents()

        service.syncConfigMap()
        registerKitScrapeTarget()
        service.syncConfigMap()

        assertThat(events.filterIsInstance<Event.Grafana.WorkloadConfigCompared>()).containsExactly(
            Event.Grafana.WorkloadConfigCompared(collector.toString(), changed = false),
            Event.Grafana.WorkloadConfigCompared(collector.toString(), changed = true),
        )
    }

    @Test
    fun `syncConfigMap returns failure when applyResource fails`() {
        whenever(mockK8sService.applyResource(any()))
            .thenReturn(Result.failure(RuntimeException("K8s API error")))

        val result = service.syncConfigMap()

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `syncConfigMap closes the K8s client after use`() {
        service.syncConfigMap()

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

        service.syncConfigMap()
        val appliedConfigMap = applied.filterIsInstance<ConfigMap>().singleOrNull()

        val yaml =
            checkNotNull(appliedConfigMap?.data?.get("otel-collector-config.yaml")) {
                "Applied ConfigMap must contain otel-collector-config.yaml"
            }
        assertThat(yaml).contains("scylladb")
        assertThat(yaml).contains("9180")
    }

    /**
     * A kit start/stop regenerates the core collector only. The Cilium scrape jobs are the host
     * add-on's, in its own ConfigMap, so a kit start can neither drop them nor roll the add-on.
     */
    @Test
    fun `syncConfigMap touches only the core collector, so the add-on keeps its Cilium jobs`() {
        val ciliumState =
            ClusterState(
                name = "test",
                s3Bucket = "easy-db-lab-test",
                hosts = withControlHost.hosts,
                initConfig = InitConfig(region = "us-west-2", cni = CniMode.Cilium),
            )
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(ciliumState)
        val applied = captureApplied()

        service.syncConfigMap()

        assertThat(applied.map { it.metadata.name }).noneMatch { it.startsWith("otel-collector-host") }
        val yaml = checkNotNull(applied.filterIsInstance<ConfigMap>().single().data["otel-collector-config.yaml"])
        assertThat(yaml).doesNotContain("cilium-agent")
    }

    /**
     * Nothing the collector renders depends on the control host: the cluster-config it hashes against
     * carries no node IP, and the collector reaches every backend by Service DNS. So a sync needs no
     * control host, and the hash it stamps is the one a state with a control host gets.
     */
    @Test
    fun `a sync runs with no control host, renders no node IP, and stamps the same hash as with one`() {
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(noControlHost)
        val applied = captureApplied()

        service.syncConfigMap().getOrThrow()

        assertThat(appliedConfigHash(applied)).isEqualTo(collectorHash(ClusterConfigData.of(withControlHost, "us-west-2")))
        val config =
            applied
                .filterIsInstance<ConfigMap>()
                .single()
                .data.values
                .single()
        assertThat(
            config.replace("0.0.0.0:", ""),
        ).describedAs("no endpoint at a node IP").doesNotContainPattern("""\b\d{1,3}(\.\d{1,3}){3}:\d+""")
        assertThat(config).contains("mimir.default.svc", "loki.default.svc", "tempo.default.svc")
    }

    /**
     * A K3s in Docker workspace has no account bucket (#1061), and the collector writes only to the
     * in-cluster backends, so a kit start registers its scrape job there: through the real registry
     * and the real sync, the collector config is rendered with the kit's job and applied.
     */
    @Test
    fun `a K3s in Docker state with no account bucket registers a kit scrape job and renders the collector`() {
        val local =
            ClusterState(
                name = "local",
                environment = Environment.K3sDocker("/work/observability-data"),
                initConfig = InitConfig(region = ""),
            )
        whenever(getKoin().get<ClusterStateManager>().load()).thenReturn(local)
        capabilities = NodeCapabilities.LOCAL_DOCKER
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any())).thenAnswer {
            registerKitScrapeTarget()
            Result.success(Unit)
        }
        val applied = captureApplied()
        val registry = DefaultMetricsRegistryService(mockK8sService, service, getKoin().get()) { TestScopes.EC2 }

        registry.register("scylladb", listOf(KitMetrics.Scrape(port = 9180, path = "/metrics"))).getOrThrow()

        val config =
            applied
                .filterIsInstance<ConfigMap>()
                .single()
                .data.values
                .single()
        assertThat(config).contains("scylladb", "9180").doesNotContainPattern("__[A-Z][A-Z0-9_]*__")
    }

    /** With no observability stack, a sync applies nothing: a kit start must not create the collector or its RBAC. */
    @Test
    fun `with no observability stack a sync applies nothing and reports the job as recorded`() {
        stackDeployed(false)
        val applied = captureApplied()

        val outcome = service.syncConfigMap().getOrThrow()

        assertThat(applied).isEmpty()
        assertThat(outcome).isEqualTo(CollectorSync.Deferred)
    }

    /**
     * With the stack deployed, a sync changes only what a scrape job changes: the collector's
     * ConfigMap, and its DaemonSet, whose template carries the new config hash and so rolls. The
     * ServiceAccount, the cluster-wide RBAC and the Service are the deploy's and are left alone.
     */
    @Test
    fun `with the stack deployed a sync applies only the collector ConfigMap and DaemonSet`() {
        val applied = captureApplied()

        val outcome = service.syncConfigMap().getOrThrow()

        assertThat(applied.map { "${it.kind}/${it.metadata.name}" }).containsExactly(
            "ConfigMap/otel-collector-config",
            "DaemonSet/${Constants.OtelCollector.SERVICE_NAME}",
        )
        assertThat(outcome).isEqualTo(CollectorSync.Applied)
    }

    /**
     * A kit start on a cluster with no stack records its scrape job and changes nothing else: the
     * registry ConfigMap is written, no collector resource is applied, and the operator is told the
     * job applies when the stack is deployed.
     */
    @Test
    fun `a kit start with no observability stack records its scrape job and applies no collector resource`() {
        stackDeployed(false)
        val written = mutableListOf<String>()
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any())).thenAnswer {
            written += it.getArgument<String>(1)
            Result.success(Unit)
        }
        val applied = captureApplied()
        val events = recordEvents()
        val registry = DefaultMetricsRegistryService(mockK8sService, service, getKoin().get()) { TestScopes.EC2 }

        registry.register("scylladb", listOf(KitMetrics.Scrape(port = 9180, path = "/metrics"))).getOrThrow()

        assertThat(written).containsExactly("easydblab-metrics-scylladb-scylladb")
        assertThat(applied).isEmpty()
        assertThat(events).contains(Event.Kit.MetricsRecorded(kit = "scylladb", ports = listOf(9180)))
        assertThat(events).noneMatch { it is Event.Kit.MetricsRegistered }
    }

    /** The collector's hash against [clusterConfig], built the way [CollectorResources] builds it. */
    private fun collectorHash(clusterConfig: Map<String, String>): String =
        ConfigHashAnnotator
            .annotate(
                otelManifestBuilder.buildAllResources(
                    TestScopes.EC2,
                    capabilities,
                    emptyList(),
                    null,
                    CollectorResources.podLogSource(capabilities),
                ),
                mapOf(ClusterConfigData.NAME to clusterConfig),
            ).let(::appliedConfigHash)
}
