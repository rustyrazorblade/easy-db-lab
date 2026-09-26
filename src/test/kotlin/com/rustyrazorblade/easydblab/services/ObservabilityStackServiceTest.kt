package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests the two modes of [DefaultObservabilityStackService].
 *
 * Uses real manifest builders and TemplateService (never mock configuration classes) so the tests
 * assert on the actual resources the builders emit. The redirect branch is the reason this service
 * exists as a first-class code path, so both modes are exercised against the real builder output
 * rather than against counts.
 */
class ObservabilityStackServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockK8sClientProvider: K8sClientProvider
    private lateinit var mockRemoteOps: RemoteOperationsService
    private lateinit var mockDashboardService: GrafanaDashboardService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var mockK8sClient: KubernetesClient

    private lateinit var service: DefaultObservabilityStackService

    private val controlNode =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
                single { mock<K8sClientProvider>().also { mockK8sClientProvider = it } }
                single<RemoteOperationsService> { mock<RemoteOperationsService>().also { mockRemoteOps = it } }
                single { mock<GrafanaDashboardService>().also { mockDashboardService = it } }
                single { mock<ClusterStateManager>().also { mockClusterStateManager = it } }

                // Real TemplateService and manifest builders — never mock configuration classes.
                single { TemplateService(get(), get()) }
                single { BeylaManifestBuilder(get()) }
                single { EbpfExporterManifestBuilder() }
                single { JournaldOtelManifestBuilder(get()) }
                single { OtelManifestBuilder(get()) }
                single { PyroscopeManifestBuilder(get()) }
                single { TempoManifestBuilder(get()) }
                single { MimirManifestBuilder(get()) }
                single { LokiManifestBuilder(get()) }
                single { RegistryManifestBuilder() }
                single { S3ManagerManifestBuilder(get()) }
                single { YaceManifestBuilder(get()) }
                single { KubeStateMetricsManifestBuilder() }
            },
        )

    @BeforeEach
    fun setup() {
        mockK8sService = getKoin().get()
        mockK8sClientProvider = getKoin().get()
        mockRemoteOps = getKoin().get()
        mockDashboardService = getKoin().get()
        mockClusterStateManager = getKoin().get()

        mockK8sClient = mock()
        val mockConfigMapOps = mock<MixedOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockAnyNsOps = mock<AnyNamespaceOperation<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val mockFiltered = mock<FilterWatchListDeletable<ConfigMap, ConfigMapList, Resource<ConfigMap>>>()
        val emptyConfigMapList = ConfigMapList().also { it.items = mutableListOf() }

        whenever(mockK8sClient.configMaps()).thenReturn(mockConfigMapOps)
        whenever(mockConfigMapOps.inAnyNamespace()).thenReturn(mockAnyNsOps)
        whenever(mockAnyNsOps.withLabel(any<String>(), any<String>())).thenReturn(mockFiltered)
        whenever(mockFiltered.list()).thenReturn(emptyConfigMapList)
        whenever(mockK8sClientProvider.createClient(any())).thenReturn(mockK8sClient)

        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test",
            ),
        )

        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDeployment(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForRollouts(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any())).thenReturn(Result.success(emptyMap()))
        whenever(mockDashboardService.uploadDashboards(any(), any())).thenReturn(Result.success(Unit))

        service =
            DefaultObservabilityStackService(
                mockK8sService,
                mockK8sClientProvider,
                mockRemoteOps,
                mockClusterStateManager,
                getKoin().get(),
                getKoin().get(),
                mockDashboardService,
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                getKoin().get(),
                ConfigChangeReport(mockK8sService, getKoin().get()),
            )
    }

    private fun stateWithCni(cni: CniMode) =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test",
            initConfig = InitConfig(region = "us-west-2", cni = cni),
        )

    /** A cluster whose init config records a telemetry redirect to an external stack. */
    private fun redirectState() =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test",
            initConfig = InitConfig(region = "us-west-2", telemetryRedirect = TelemetryRedirect.fromBaseHost("10.0.0.9")),
        )

    /** Re-arms the K8s mock for a second deploy in the same test, after a reset. */
    private fun stubK8sSuccess() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any(), any<HasMetadata>())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDeployment(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.rolloutRestartDaemonSet(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForRollouts(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any())).thenReturn(Result.success(emptyMap()))
    }

    /** The OTel collector config YAML as it was applied to the cluster. */
    private fun appliedOtelConfig(): String {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(any(), captor.capture())
        val configMap = captor.allValues.filterIsInstance<ConfigMap>().single { it.metadata.name == "otel-collector-config" }
        return checkNotNull(configMap.data["otel-collector-config.yaml"])
    }

    /** Every applied resource as a "kind/name" string, in apply order. */
    private fun appliedKindNames(): List<String> {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(any(), captor.capture())
        return captor.allValues.map { "${it.kind}/${it.metadata?.name}" }
    }

    /** Every command passed to [RemoteOperationsService.executeRemotely]. */
    private fun remoteCommands(): List<String> {
        val captor = argumentCaptor<String>()
        verify(mockRemoteOps, atLeastOnce()).executeRemotely(any(), captor.capture(), any(), any())
        return captor.allValues
    }

    @Test
    fun `local mode deploys the in-cluster backends, dashboards, and on-node directories`() {
        service.deploy(controlNode).getOrThrow()

        val kindNames = appliedKindNames()
        val names = kindNames.map { it.substringAfter("/") }

        // The four local telemetry backends are applied, and the Pyroscope server (not the agent) runs.
        assertThat(kindNames).contains("Deployment/mimir", "Deployment/loki", "Deployment/tempo", "Deployment/pyroscope")
        assertThat(names).doesNotContain("victoriametrics", "victorialogs")

        // Dashboards are uploaded only in local mode.
        verify(mockDashboardService).uploadDashboards(any(), eq("default"))

        // Both on-node data directories are prepared over SSH.
        val commands = remoteCommands()
        assertThat(commands).anyMatch { it.contains("/mnt/db1/pyroscope") }
        assertThat(commands).anyMatch { it.contains(GrafanaManifestBuilder.GRAFANA_DATA_PATH) }
    }

    @Test
    fun `local mode prepares Tempo's WAL directory on the control node, owned by the Tempo user`() {
        service.deploy(controlNode).getOrThrow()

        assertThat(remoteCommands()).anyMatch {
            it.contains("mkdir -p ${TempoManifestBuilder.DATA_HOST_PATH}") &&
                it.contains(
                    "chown -R ${TempoManifestBuilder.TEMPO_UID}:${TempoManifestBuilder.TEMPO_UID} ${TempoManifestBuilder.DATA_HOST_PATH}",
                )
        }
    }

    @Test
    fun `local mode prepares Mimir's data directory on the control node`() {
        service.deploy(controlNode).getOrThrow()

        assertThat(remoteCommands()).anyMatch { it.contains("mkdir -p ${MimirManifestBuilder.DATA_HOST_PATH}") }
    }

    @Test
    fun `local mode prepares Loki's data directory on the control node, owned by the Loki user`() {
        service.deploy(controlNode).getOrThrow()

        assertThat(remoteCommands()).anyMatch {
            it.contains("mkdir -p ${LokiManifestBuilder.DATA_HOST_PATH}") &&
                it.contains(
                    "chown -R ${LokiManifestBuilder.LOKI_UID}:${LokiManifestBuilder.LOKI_UID} ${LokiManifestBuilder.DATA_HOST_PATH}",
                )
        }
    }

    @Test
    fun `cluster-config carries the traces prefix instead of the per-cluster prefix`() {
        service.deploy(controlNode).getOrThrow()

        val data = argumentCaptor<Map<String, String>>()
        verify(mockK8sService).createConfigMap(any(), any(), eq("cluster-config"), data.capture(), any())
        assertThat(data.firstValue).containsEntry("traces_s3_prefix", "observability/traces")
        assertThat(data.firstValue).doesNotContainKey("cluster_s3_prefix")
    }

    @Test
    fun `redirect mode skips local backends and dashboards but still runs the eBPF agent`() {
        whenever(mockClusterStateManager.load()).thenReturn(redirectState())

        service.deploy(controlNode).getOrThrow()

        val kindNames = appliedKindNames()
        val names = kindNames.map { it.substringAfter("/") }

        // None of the local backends nor the Pyroscope server are applied under redirect.
        assertThat(names).doesNotContain("mimir", "loki", "tempo")
        assertThat(kindNames).doesNotContain("Deployment/pyroscope")
        // The eBPF profiling agent still runs on every node, pointed at the external stack.
        assertThat(kindNames).contains("DaemonSet/pyroscope-ebpf")

        // No Grafana in redirect mode, so no dashboards uploaded.
        verify(mockDashboardService, never()).uploadDashboards(any(), any())

        // Neither on-node directory (Pyroscope server data, Grafana data) is prepared: the server
        // and Grafana do not exist here, so redirect makes no SSH calls at all.
        verify(mockRemoteOps, never()).executeRemotely(any(), any(), any(), any())

        // Readiness is still gated on the applied collectors coming up.
        verify(mockK8sService).waitForPodsReady(any(), any())
    }

    @Test
    fun `kube-state-metrics is deployed in both local and redirect mode`() {
        service.deploy(controlNode).getOrThrow()
        assertThat(appliedKindNames()).contains("Deployment/kube-state-metrics", "Service/kube-state-metrics")

        reset(mockK8sService)
        stubK8sSuccess()
        whenever(mockClusterStateManager.load()).thenReturn(redirectState())
        service.deploy(controlNode).getOrThrow()
        assertThat(appliedKindNames()).contains("Deployment/kube-state-metrics", "Service/kube-state-metrics")
    }

    @Test
    fun `the collector config carries the Cilium scrape jobs only when the cluster state says Cilium`() {
        whenever(mockClusterStateManager.load()).thenReturn(stateWithCni(CniMode.Cilium))
        service.deploy(controlNode).getOrThrow()
        assertThat(appliedOtelConfig()).contains("cilium-agent").contains("cilium-operator")

        reset(mockK8sService)
        stubK8sSuccess()
        whenever(mockClusterStateManager.load()).thenReturn(stateWithCni(CniMode.Flannel))
        service.deploy(controlNode).getOrThrow()
        assertThat(appliedOtelConfig()).doesNotContain("cilium-agent").doesNotContain("cilium-operator")
    }

    @Test
    fun `the readiness gate runs only after every applied workload has finished rolling out`() {
        // Pod readiness alone passes while a restarted workload's old pods are still serving, which
        // is how `update-config` reported ready with replacement pods at 0/1.
        service.deploy(controlNode).getOrThrow()

        val applied = appliedKindNames()
        val workloads = argumentCaptor<List<WorkloadRef>>()
        val order = inOrder(mockK8sService)
        order.verify(mockK8sService).waitForRollouts(any(), workloads.capture(), eq("default"), any())
        order.verify(mockK8sService).waitForPodsReady(any(), any())

        // Grafana is applied by the dashboard service rather than a stage, so it is not among these.
        val expected = applied.filter { it.startsWith("Deployment/") || it.startsWith("DaemonSet/") }.distinct() + "Deployment/grafana"
        assertThat(workloads.firstValue.map { it.toString() }).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(expected).contains("DaemonSet/otel-collector", "Deployment/tempo", "Deployment/pyroscope")
    }

    /**
     * A datasource change (the tenant header) rolls Grafana. Without waiting for that rollout, `up` and
     * `grafana update-config` report the stack ready while the old Grafana pod still serves, and the
     * annotation posts and dashboard installs that follow can reach it.
     */
    @Test
    fun `the Grafana rollout is waited on after the dashboards are uploaded`() {
        service.deploy(controlNode).getOrThrow()

        val workloads = argumentCaptor<List<WorkloadRef>>()
        val order = inOrder(mockDashboardService, mockK8sService)
        order.verify(mockDashboardService).uploadDashboards(any(), any())
        order.verify(mockK8sService).waitForRollouts(any(), workloads.capture(), eq("default"), any())
        assertThat(workloads.firstValue).contains(WorkloadRef(WorkloadKind.Deployment, "grafana"))
    }

    /**
     * The operator is told which workloads roll by comparing against the running hashes. If those
     * cannot be read, the deploy stops before it changes the cluster, rather than applying with no
     * report or reporting every workload as unchanged.
     */
    @Test
    fun `a failure to read the running configuration hashes fails the deploy before anything is applied`() {
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any()))
            .thenReturn(Result.failure(IllegalStateException("apiserver unreachable")))

        val result = service.deploy(controlNode)

        assertThat(result.exceptionOrNull()).hasMessageContaining("apiserver unreachable")
        verify(mockK8sService, never()).applyResource(any(), any<HasMetadata>())
    }

    @Test
    fun `redirect mode deploys no Grafana, so it waits on none`() {
        whenever(mockClusterStateManager.load()).thenReturn(redirectState())

        service.deploy(controlNode).getOrThrow()

        val workloads = argumentCaptor<List<WorkloadRef>>()
        verify(mockK8sService).waitForRollouts(any(), workloads.capture(), eq("default"), any())
        assertThat(workloads.firstValue).doesNotContain(WorkloadRef(WorkloadKind.Deployment, "grafana"))
    }

    /**
     * A deploy used to rollout-restart every workload it applied, so a dashboard edit restarted Tempo
     * and Pyroscope. Workloads now roll only when their pod template changes, which the config hash
     * makes happen exactly when their configuration does.
     */
    @Test
    fun `a deploy never force-restarts a workload`() {
        service.deploy(controlNode).getOrThrow()

        verify(mockK8sService, never()).rolloutRestartDeployment(any(), any(), any())
        verify(mockK8sService, never()).rolloutRestartDaemonSet(any(), any(), any())
    }

    @Test
    fun `every applied workload carries the hash of the configuration it reads, including cluster-config`() {
        service.deploy(controlNode).getOrThrow()
        val firstTempo = appliedTemplateHash("tempo")
        val firstGrafanaIndependent = appliedTemplateHash("otel-collector")

        // Only the runtime cluster-config differs between the two deploys: a new control node IP.
        reset(mockK8sService)
        stubK8sSuccess()
        service.deploy(controlNode.copy(privateIp = "10.0.1.99")).getOrThrow()

        assertThat(firstTempo).isNotBlank()
        assertThat(appliedTemplateHash("tempo"))
            .describedAs("Tempo reads cluster-config, so a change to it rolls Tempo")
            .isNotEqualTo(firstTempo)
        assertThat(firstGrafanaIndependent).isNotBlank()
    }

    @Test
    fun `an identical deploy leaves every workload's hash unchanged`() {
        service.deploy(controlNode).getOrThrow()
        val first = listOf("tempo", "pyroscope", "otel-collector").associateWith { appliedTemplateHash(it) }

        reset(mockK8sService)
        stubK8sSuccess()
        service.deploy(controlNode).getOrThrow()

        assertThat(listOf("tempo", "pyroscope", "otel-collector").associateWith { appliedTemplateHash(it) }).isEqualTo(first)
    }

    /** The config-hash annotation on the pod template of the applied workload named [name]. */
    private fun appliedTemplateHash(name: String): String? {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(any(), captor.capture())
        val template =
            when (val workload = captor.allValues.single { it.metadata.name == name && it.kind in setOf("Deployment", "DaemonSet") }) {
                is io.fabric8.kubernetes.api.model.apps.Deployment -> workload.spec.template
                is io.fabric8.kubernetes.api.model.apps.DaemonSet -> workload.spec.template
                else -> error("unexpected workload $workload")
            }
        return template.metadata?.annotations?.get(Constants.K8s.CONFIG_HASH_ANNOTATION)
    }

    @Test
    fun `a rollout that does not finish fails the deploy without reporting the stack ready`() {
        whenever(mockK8sService.waitForRollouts(any(), any(), any(), any()))
            .thenReturn(Result.failure(IllegalStateException("Deployment/tempo: 0 of 1 updated replicas are available")))

        val result = service.deploy(controlNode)

        assertThat(result.exceptionOrNull()).hasMessageContaining("Deployment/tempo: 0 of 1 updated replicas are available")
        verify(mockK8sService, never()).waitForPodsReady(any(), any())
    }

    @Test
    fun `a cluster-config ConfigMap failure aborts the deploy and reports the failure`() {
        // The cluster-config ConfigMap is the source of the `cluster` label every signal ships with.
        // If it cannot be created, provisioning must fail loudly rather than report success over a
        // broken origin-identity mechanism that would leave two DCs indistinguishable on one Grafana.
        val emitted = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("apiserver rejected the ConfigMap")))

        val result = service.deploy(controlNode)

        assertThat(result.isFailure).isTrue()
        val failure = emitted.filterIsInstance<Event.Provision.ClusterConfigMapFailed>().single()
        assertThat(failure.reason).contains("apiserver rejected the ConfigMap")
        assertThat(failure.isError()).isTrue()
        // Nothing was applied: the abort happens before any manifest reaches the cluster.
        verify(mockK8sService, never()).applyResource(any(), any<HasMetadata>())
    }

    /**
     * Nothing force-restarts a workload any more, so the operator needs to be told which workloads a
     * deploy rolls: those whose configuration hash differs from the one running on the cluster.
     */
    @Test
    fun `each applied workload reports whether its configuration changed from the running one`() {
        service.deploy(controlNode).getOrThrow()
        val runningTempoHash = appliedTemplateHash("tempo")

        reset(mockK8sService)
        stubK8sSuccess()
        // An Answer bypasses Kotlin's Result unboxing: the JVM method returns the bare map.
        whenever(mockK8sService.workloadConfigHashes(any(), any(), eq("default"))).thenAnswer { invocation ->
            invocation.getArgument<List<WorkloadRef>>(1).associateWith { if (it.name == "tempo") runningTempoHash else "stale" }
        }
        val emitted = recordEvents()

        service.deploy(controlNode).getOrThrow()

        val compared = emitted.filterIsInstance<Event.Grafana.WorkloadConfigCompared>().associateBy { it.workload }
        assertThat(compared["Deployment/tempo"]?.changed).isFalse()
        assertThat(compared["DaemonSet/otel-collector"]?.changed).isTrue()
        assertThat(compared.keys).containsExactlyInAnyOrderElementsOf(
            appliedKindNames().filter { it.startsWith("Deployment/") || it.startsWith("DaemonSet/") }.distinct(),
        )
    }

    @Test
    fun `a workload not yet on the cluster reports its configuration as changed`() {
        whenever(mockK8sService.workloadConfigHashes(any(), any(), any())).thenReturn(Result.success(emptyMap()))
        val emitted = recordEvents()

        service.deploy(controlNode).getOrThrow()

        assertThat(emitted.filterIsInstance<Event.Grafana.WorkloadConfigCompared>()).isNotEmpty().allMatch { it.changed }
    }

    /** The config hash on the otel-collector DaemonSet among the resources applied so far. */
    private fun appliedCollectorHash(): String {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(any(), captor.capture())
        return captor.allValues
            .filterIsInstance<DaemonSet>()
            .single { it.metadata.name == Constants.OtelCollector.SERVICE_NAME }
            .spec.template.metadata.annotations
            .getValue(Constants.K8s.CONFIG_HASH_ANNOTATION)
    }

    /**
     * A kit start re-syncs the collector through [DefaultOtelSyncService]; the next `up` or
     * `grafana update-config` redeploys it through this service. Both must stamp the same config
     * hash, or that next deploy sees a "changed" collector and rolls it for nothing.
     */
    @ParameterizedTest(name = "{0}, redirect={1}")
    @MethodSource("collectorClusters")
    fun `the stack deploy and a kit sync stamp the collector with the same config hash`(
        cni: CniMode,
        redirect: TelemetryRedirect?,
    ) {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = "easy-db-lab-test",
                initConfig = InitConfig(region = "us-west-2", cni = cni, telemetryRedirect = redirect),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)

        service.deploy(controlNode).getOrThrow()
        val deployHash = appliedCollectorHash()

        reset(mockK8sService)
        stubK8sSuccess()
        DefaultOtelSyncService(
            mockK8sClientProvider,
            mockK8sService,
            getKoin().get(),
            mockClusterStateManager,
            getKoin().get(),
            ConfigChangeReport(mockK8sService, getKoin().get()),
        ).syncConfigMap(controlNode).getOrThrow()

        assertThat(appliedCollectorHash()).isEqualTo(deployHash)
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

    companion object {
        @JvmStatic
        fun collectorClusters(): List<Arguments> =
            listOf(CniMode.Flannel, CniMode.Cilium).flatMap { cni ->
                listOf(
                    Arguments.of(cni, null),
                    Arguments.of(cni, TelemetryRedirect.fromBaseHost("10.0.0.9")),
                )
            }
    }
}
