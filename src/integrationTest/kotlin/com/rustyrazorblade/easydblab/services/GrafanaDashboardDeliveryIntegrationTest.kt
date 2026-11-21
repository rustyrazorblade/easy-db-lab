package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.withClusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.ServiceDns
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardConfigMaps
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardTreeWriter
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The core dashboards' delivery, end to end: the real [DefaultGrafanaDeployService] run twice
 * against a K3s cluster with two dashboard trees, then Grafana read back through its own API.
 *
 * The trees are `portable-dashboards/v1` and `v2` on the test classpath, discovered by the real
 * [GrafanaDashboardCatalog]; `v2` edits `portable-a/one.json` and removes `portable-b/two.json`.
 * Each deploy is followed by the Grafana rollout wait the stack deploy runs, so the assertions see
 * what `grafana update-config` leaves when it returns. Grafana's state is first on a claim, as on a
 * cluster the operator brings, then on the host path, as on AWS. The cluster has its own K3s
 * container because the deploy writes to the `default` namespace.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GrafanaDashboardDeliveryIntegrationTest {
    companion object {
        private const val NAMESPACE = "default"
        private const val TENANT = "default"
        private const val READY_TIMEOUT_SECONDS = 420
        private const val EXEC_TIMEOUT_SECONDS = 60L

        /** K3s's bundled provisioner, standing in for #1035's retained StorageClass. */
        private const val LOCAL_PATH_CLASS = "local-path"
        private const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        private const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"

        /** Test-classpath directory holding the `v1` and `v2` dashboard trees. */
        private const val DASHBOARD_TREES = "portable-dashboards"
        private const val EDITED = "portable-a/one.json"
        private const val REMOVED = "portable-b/two.json"
        private val V1_PATHS = listOf(Constants.Grafana.HOME_DASHBOARD_PATH, EDITED, REMOVED)

        /** The v1 tree as Grafana should hold it: each uid to the title of its folder. */
        private val V1_FOLDERS =
            mapOf("portable-home" to "infrastructure", "portable-one" to "portable-a", "portable-two" to "portable-b")

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s-dashboards")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let {
                    (it as K3sContainer).withPreloadedImages(
                        Constants.Grafana.Documents.SIGV4_PROXY_IMAGE,
                        Constants.Grafana.Documents.WEB_SERVER_IMAGE,
                        LOCAL_PATH_PROVISIONER_IMAGE,
                        LOCAL_PATH_HELPER_IMAGE,
                    )
                }
    }

    private lateinit var client: KubernetesClient
    private lateinit var templates: TemplateService

    private val bucket = "dashboard-delivery-${UUID.randomUUID().toString().take(8)}"
    private val state =
        ClusterState(
            name = "dashboards",
            s3Bucket = bucket,
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )
    private val hosting =
        ObservabilityHosting.AWS.copy(
            placement = Placement.ANY_NODE,
            storage = BackendStorage.RetainedClaim(LOCAL_PATH_CLASS, Quantity("1Gi")),
        )

    @BeforeAll
    fun setup() {
        SharedLocalStack.s3Client().use { SharedLocalStack.createBucketIfMissing(it, bucket) }
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        SharedK3s.ensurePodAdmission(client, NAMESPACE)
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(state)
        templates = TemplateService(stateManager)
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /**
     * Runs the Grafana deploy with the tree `portable-dashboards/<version>` and Grafana's state on
     * [storage], then waits for Grafana's rollout, as the stack deploy does after it.
     */
    private fun deployDashboards(
        version: String,
        storage: BackendStorage = hosting.storage,
    ) {
        val clientProvider = mock<K8sClientProvider>()
        whenever(clientProvider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        val eventBus = EventBus()
        val k8sService = DefaultK8sService(clientProvider, eventBus)
        SharedLocalStack.s3Client().use { s3 ->
            DefaultGrafanaDeployService(
                k8sService,
                GrafanaManifestBuilder(templates),
                GrafanaDashboardTreeWriter(GrafanaDashboardCatalog.discover("$DASHBOARD_TREES/$version")),
                eventBus,
                ConfigChangeReport(k8sService, eventBus),
                BucketRegion(s3),
            ).deploy(
                DashboardInstallContext(state.clusterLabelName(), TenantSet.of(TENANT)),
                bucket,
                hosting.copy(storage = storage),
                ServiceDns(TestScopes.EC2.namespace).pyroscope(),
                TestScopes.EC2,
            ).getOrThrow()
        }
        withClusterDiagnostics(client, NAMESPACE) {
            k8sService
                .waitForRollouts(
                    listOf(WorkloadRef(WorkloadKind.Deployment, Constants.K8s.GRAFANA_APP_LABEL)),
                    NAMESPACE,
                    READY_TIMEOUT_SECONDS,
                ).getOrThrow()
        }
    }

    private fun isReady(pod: Pod): Boolean =
        pod.metadata.deletionTimestamp == null &&
            pod.status
                ?.conditions
                .orEmpty()
                .any { it.type == "Ready" && it.status == "True" }

    /** Runs `curl` on [path] of the grafana Service from inside the Grafana container. */
    private fun grafanaApi(path: String): String {
        val grafana = ClusterServices.GRAFANA
        val url = "http://${ServiceDns(TestScopes.EC2.namespace).host(grafana.service)}:${grafana.servicePort}$path"
        val pod =
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withLabel("app.kubernetes.io/name", Constants.K8s.GRAFANA_APP_LABEL)
                .list()
                .items
                .single(::isReady)
        return client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(pod.metadata.name)
            .inContainer("grafana")
            .redirectingOutput()
            .exec("curl", "-s", "-u", "admin:admin", url)
            .use { watch ->
                val output = watch.output.readAllBytes().toString(Charsets.UTF_8)
                watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                output
            }
    }

    /** Every dashboard Grafana serves, as its uid to the title of the folder it is filed in. */
    private fun dashboardFolders(): Map<String, String> =
        Json
            .parseToJsonElement(grafanaApi("/api/search?type=dash-db"))
            .jsonArray
            .map { it.jsonObject }
            .associate { hit -> hit.text("uid") to hit.text("folderTitle") }

    /** The dashboard Grafana serves under [uid]. */
    private fun dashboard(uid: String): JsonObject =
        Json
            .parseToJsonElement(grafanaApi("/api/dashboards/uid/$uid"))
            .jsonObject
            .getValue("dashboard")
            .jsonObject

    /** The value the metrics picker of [dashboard] is set to. */
    private fun metricsPicker(dashboard: JsonObject): String =
        dashboard
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .single { it.text("name") == "metrics_datasource" }
            .getValue("current")
            .jsonObject
            .text("value")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** The names of the ConfigMaps carrying the core dashboard label. */
    private fun dashboardConfigMaps(): Set<String> =
        client
            .configMaps()
            .inNamespace(NAMESPACE)
            .withLabel(Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL, Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE)
            .list()
            .items
            .map { it.metadata.name }
            .toSet()

    @Test
    @Order(1)
    fun `on a claim every dashboard is filed in its folder under its uid with the pickers set`() {
        deployDashboards("v1")

        assertThat(dashboardFolders()).isEqualTo(V1_FOLDERS)
        assertThat(metricsPicker(dashboard("portable-one"))).isEqualTo(Constants.Grafana.DatasourceUid.MIMIR)
        assertThat(dashboardConfigMaps()).isEqualTo(GrafanaDashboardConfigMaps.namesOf(V1_PATHS.associateWith { "" }))
    }

    @Test
    @Order(2)
    fun `a second deploy serves the edited dashboard and removes the deleted one and only its ConfigMap`() {
        val before = dashboardConfigMaps()

        deployDashboards("v2")

        assertThat(dashboard("portable-one").text("title")).isEqualTo("Portable One Edited")
        assertThat(dashboardFolders()).isEqualTo(V1_FOLDERS - "portable-two")
        assertThat(dashboardConfigMaps()).isEqualTo(before - GrafanaDashboardConfigMaps.namesOf(mapOf(REMOVED to "")))
        val datasources =
            client
                .configMaps()
                .inNamespace(NAMESPACE)
                .withName("grafana-datasources")
                .get()
        assertThat(datasources)
            .describedAs("a ConfigMap without the dashboard label is never pruned")
            .isNotNull()
    }

    @Test
    @Order(3)
    fun `on a host path Grafana holds the same folders and uids as on a claim`() {
        // `up` gives the host directory to Grafana's uid before the deploy; on this node it is done here.
        val path = GrafanaManifestBuilder.GRAFANA_DATA_PATH
        val uid = GrafanaManifestBuilder.GRAFANA_UID
        val prepared = k3s.execInContainer("sh", "-c", "mkdir -p $path && chown -R $uid:$uid $path")
        check(prepared.exitCode == 0) { "could not prepare $path: ${prepared.stderr}" }

        deployDashboards("v1", BackendStorage.HostPath)

        assertThat(dashboardFolders()).isEqualTo(V1_FOLDERS)
    }
}
