package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.TestDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.StoreCredentials
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaFront
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse

/**
 * Test suite for [DefaultGrafanaDeployService].
 *
 * Uses the real [GrafanaManifestBuilder] and dashboard tree, with [K8sService] mocked at the
 * cluster boundary: the dashboards travel as ConfigMaps on every storage mode, and the maps the
 * deploy no longer renders are pruned after the Deployment is applied.
 */
class GrafanaDeployServiceTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var manifestBuilder: GrafanaManifestBuilder

    private val claimed =
        ObservabilityHosting(
            Placement.ANY_NODE,
            BackendStorage.RetainedClaim("retained-class", Quantity("10Gi")),
            StoreCredentials.InstanceRole,
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<K8sService>().also { mockK8sService = it } }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(ClusterState(name = "test", s3Bucket = "easy-db-lab-test"))
        manifestBuilder = GrafanaManifestBuilder(TemplateService(stateManager, getKoin().get()))
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyResource(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.pruneConfigMaps(any(), any(), any())).thenReturn(Result.success(emptyList()))
        whenever(mockK8sService.workloadConfigHashes(any(), any()))
            .thenReturn(Result.success(mapOf(WorkloadRef(WorkloadKind.Deployment, "grafana") to "running-hash")))
    }

    private val eventBus = EventBus()

    private fun bucketInRegion(region: String): S3Client =
        mock<S3Client>().also {
            whenever(it.getBucketLocation(any<GetBucketLocationRequest>()))
                .thenReturn(GetBucketLocationResponse.builder().locationConstraint(region).build())
        }

    private fun context(tenant: String) = DashboardInstallContext("test-abc", TenantSet.of(tenant))

    private fun service() =
        DefaultGrafanaDeployService(
            mockK8sService,
            manifestBuilder,
            GrafanaDashboardTreeWriter(TestDashboardCatalog.catalog),
            eventBus,
            ConfigChangeReport(mockK8sService, eventBus),
            BucketRegion(bucketInRegion("eu-west-1")),
        )

    private fun deploy(
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
        tenant: String = "acme",
        pyroscopeUrl: String = PYROSCOPE_URL,
    ) = service().deploy(context(tenant), "acct-bucket", hosting, pyroscopeUrl, TestScopes.EC2)

    private fun applied(): List<HasMetadata> {
        val captor = argumentCaptor<HasMetadata>()
        verify(mockK8sService, atLeastOnce()).applyResource(captor.capture())
        return captor.allValues
    }

    private fun dashboardMaps(resources: List<HasMetadata>) =
        resources.filterIsInstance<ConfigMap>().filter {
            it.metadata.labels[Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL] == Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE
        }

    @Test
    fun `Grafana reaches each backend by its Service DNS name, never localhost`() {
        service().createDatasourcesConfigMap(TenantSet.of("acme"), TestScopes.EC2)

        val data = argumentCaptor<Map<String, String>>()
        verify(mockK8sService).createConfigMap(any(), any(), data.capture(), any())
        val yaml = checkNotNull(data.firstValue["datasources.yaml"])
        assertThat(yaml)
            .contains(
                "http://mimir.default.svc:9009/prometheus",
                "http://loki.default.svc:3100",
                "http://tempo.default.svc:3200",
                "http://pyroscope.default.svc:4040",
            ).doesNotContain("localhost")
    }

    @Test
    fun `in another namespace the datasources are written there and reach that namespace's Services`() {
        service().createDatasourcesConfigMap(TenantSet.of("acme"), InstallScope(K8sNamespace("lab-a"), "id-1"))

        val data = argumentCaptor<Map<String, String>>()
        verify(mockK8sService).createConfigMap(eq("lab-a"), any(), data.capture(), any())
        val yaml = checkNotNull(data.firstValue["datasources.yaml"])
        assertThat(yaml)
            .contains("http://mimir.lab-a.svc:9009/prometheus", "http://loki.lab-a.svc:3100")
            .doesNotContain(".default.svc")
    }

    @Test
    fun `createDatasourcesConfigMap calls k8sService with correct params`() {
        val result = service().createDatasourcesConfigMap(TenantSet.of("acme"), TestScopes.EC2)

        assertThat(result.isSuccess).isTrue()
        verify(mockK8sService).createConfigMap(
            namespace = eq("default"),
            name = eq("grafana-datasources"),
            data = any(),
            labels = eq(mapOf("app.kubernetes.io/name" to "grafana")),
        )
    }

    @Test
    fun `deploy fails when createDatasourcesConfigMap fails`() {
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("ConfigMap creation failed")))

        val result = deploy()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to create Grafana datasources ConfigMap")
    }

    /**
     * An undeliverable dashboard is found before the cluster is touched: not even the datasources
     * ConfigMap may be written when the deploy is going to fail.
     */
    @Test
    fun `a catalog with two paths for one ConfigMap name fails the deploy before anything is written`() {
        val clashing = clashingService()

        val result = clashing.deploy(context("acme"), "acct-bucket", ObservabilityHosting.AWS, PYROSCOPE_URL, TestScopes.EC2)

        assertThat(result.exceptionOrNull()).hasMessageContaining("a-b/c.json").hasMessageContaining("a/b-c.json")
        verify(mockK8sService, never()).createConfigMap(any(), any(), any(), any())
        verify(mockK8sService, never()).applyResource(any())
    }

    @Test
    fun `a dashboard tree that fails validation is never reported as rendered`() {
        val emitted = recordEvents()

        val result = clashingService().prepare(context("acme"), "acct-bucket", ObservabilityHosting.AWS, PYROSCOPE_URL, TestScopes.EC2)

        assertThat(result.isFailure).isTrue()
        assertThat(emitted.filterIsInstance<Event.Grafana.DashboardsRendered>()).isEmpty()
    }

    private fun clashingService() =
        DefaultGrafanaDeployService(
            mockK8sService,
            manifestBuilder,
            GrafanaDashboardTreeWriter(GrafanaDashboardCatalog.discover(CLASHING_CATALOG)),
            eventBus,
            ConfigChangeReport(mockK8sService, eventBus),
            BucketRegion(bucketInRegion("eu-west-1")),
        )

    @Test
    fun `deploy fails when applyResource fails, and prunes nothing`() {
        whenever(mockK8sService.applyResource(any()))
            .thenReturn(Result.failure(RuntimeException("Apply failed")))

        val result = deploy()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Failed to apply")
        verify(mockK8sService, never()).pruneConfigMaps(any(), any(), any())
    }

    /**
     * The dashboards travel the same way whatever stores Grafana's state: no SSH copy on a host
     * path, and the same set of ConfigMaps as on a claim.
     */
    @Test
    fun `the same dashboard ConfigMaps are applied on a host path and on a claim`() {
        deploy(ObservabilityHosting.AWS).getOrThrow()
        val onHostPath = dashboardMaps(applied()).associate { it.metadata.name to it.data }

        clearInvocations(mockK8sService)
        deploy(claimed).getOrThrow()
        val onClaim = dashboardMaps(applied()).associate { it.metadata.name to it.data }

        assertThat(onHostPath).isNotEmpty().hasSize(TestDashboardCatalog.catalog.dashboards.size).isEqualTo(onClaim)
    }

    /**
     * Pruning before the Deployment would leave a live template naming a deleted map, and a new pod
     * would hang in ContainerCreating. The prune keeps exactly the maps this deploy rendered.
     */
    @Test
    fun `stale dashboard ConfigMaps are pruned by label after the Grafana Deployment is applied, keeping the rendered ones`() {
        deploy().getOrThrow()

        val rendered = dashboardMaps(applied()).map { it.metadata.name }.toSet()
        val order = inOrder(mockK8sService)
        order.verify(mockK8sService).applyResource(argThat { this is Deployment && metadata.name == "grafana" })
        val keep = argumentCaptor<Set<String>>()
        order.verify(mockK8sService).pruneConfigMaps(
            eq("default"),
            eq(mapOf(Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL to Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE)),
            keep.capture(),
        )
        assertThat(keep.firstValue).isEqualTo(rendered)
    }

    @Test
    fun `a failed prune fails the deploy`() {
        whenever(mockK8sService.pruneConfigMaps(any(), any(), any())).thenReturn(Result.failure(RuntimeException("list denied")))

        val result = deploy()

        assertThat(result.exceptionOrNull()?.message).contains("list denied")
    }

    @Test
    fun `the Pyroscope URL it is given reaches the rendered profiling dashboard`() {
        deploy(pyroscopeUrl = "http://10.9.8.7:31040").getOrThrow()

        val pyroscopeLinks = dashboardMaps(applied()).flatMap { it.data.values }.filter { "Pyroscope UI" in it }
        assertThat(pyroscopeLinks).isNotEmpty().allSatisfy { assertThat(it).contains("http://10.9.8.7:31040") }
    }

    @Test
    fun `dashboard ConfigMaps are applied before the Deployment that mounts them`() {
        deploy(claimed).getOrThrow()

        val kinds = applied().map { "${it.kind}/${it.metadata.name}" }
        assertThat(kinds.indexOfFirst { it.startsWith("ConfigMap/${Constants.Grafana.DASHBOARD_CONFIGMAP_PREFIX}") })
            .isNotNegative()
            .isLessThan(kinds.indexOf("Deployment/grafana"))
    }

    @Test
    fun `deploy reports how many dashboards it rendered`() {
        val emitted = recordEvents()

        deploy().getOrThrow()

        assertThat(emitted.filterIsInstance<Event.Grafana.DashboardsRendered>())
            .containsExactly(Event.Grafana.DashboardsRendered(TestDashboardCatalog.catalog.dashboards.size))
    }

    /**
     * Grafana reads its datasources from `grafana-datasources` at start. A datasource change — such as
     * the tenant header the Tempo and Pyroscope datasources send — must roll Grafana, or it keeps
     * querying with the old provisioning while `up` reports success.
     */
    @Test
    fun `a datasource change rolls Grafana through its config hash`() {
        deploy(tenant = "acme").getOrThrow()
        deploy(tenant = "acme").getOrThrow()
        deploy(tenant = "other").getOrThrow()

        val hashes =
            applied().filterIsInstance<Deployment>().map {
                it.spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]
            }
        assertThat(hashes).hasSize(3).doesNotContainNull()
        assertThat(hashes[1]).describedAs("same datasources, same hash").isEqualTo(hashes[0])
        assertThat(hashes[2]).describedAs("a new tenant changes the datasources").isNotEqualTo(hashes[1])
    }

    @Test
    fun `deploy reports that Grafana rolls when its configuration differs from the running one`() {
        val emitted = recordEvents()

        deploy().getOrThrow()

        assertThat(emitted.filterIsInstance<Event.Grafana.WorkloadConfigCompared>())
            .containsExactly(Event.Grafana.WorkloadConfigCompared("Deployment/grafana", changed = true))
    }

    /**
     * The documents proxy signs for the account bucket's own region, from GetBucketLocation, not the
     * cluster's (the test user's us-west-2), and the web server reads the account bucket.
     */
    @Test
    fun `the applied Grafana pod reads the account bucket in the bucket's own region`() {
        deploy().getOrThrow()

        val resources = applied()
        val proxy =
            resources
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.spec.containers
                .single { it.name == "documents-sigv4-proxy" }
        assertThat(proxy.args).containsSequence("--region", "eu-west-1").containsSequence("--host", "s3.eu-west-1.amazonaws.com")
        val web = resources.filterIsInstance<ConfigMap>().single { it.metadata.name == GrafanaFront.CONFIGMAP_NAME }
        assertThat(web.data.values.single()).contains("/acct-bucket/reports/;")
    }

    /**
     * The hosting the stack deploy resolves is the one the applied Grafana Deployment carries: an
     * environment that does not own its nodes gets no control-node selector, no host path, and the
     * grafana-data claim, applied before the Deployment that mounts it.
     */
    @Test
    fun `the hosting passed to deploy reaches the applied Grafana Deployment`() {
        deploy(claimed).getOrThrow()

        val resources = applied()
        val pod =
            resources
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.spec
        assertThat(pod.nodeSelector.orEmpty()).isEmpty()
        assertThat(pod.tolerations.orEmpty()).isEmpty()
        assertThat(pod.volumes.mapNotNull { it.hostPath }).isEmpty()
        assertThat(pod.volumes.mapNotNull { it.persistentVolumeClaim?.claimName }).containsExactly("grafana-data")
        val kinds = resources.map { "${it.kind}/${it.metadata.name}" }
        assertThat(kinds.indexOf("PersistentVolumeClaim/grafana-data")).isNotNegative().isLessThan(kinds.indexOf("Deployment/grafana"))
    }

    private fun recordEvents(): List<Event> {
        val emitted = mutableListOf<Event>()
        eventBus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
        return emitted
    }

    private companion object {
        const val PYROSCOPE_URL = "http://pyroscope.default.svc:4040"

        /** A catalog whose `a-b/c.json` and `a/b-c.json` both map to `grafana-dashboard-a-b-c`. */
        const val CLASHING_CATALOG = "grafana-catalog-fixtures/clashing-names"
    }
}
