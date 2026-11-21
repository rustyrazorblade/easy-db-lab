package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.TestDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.BackendStorage
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.Placement
import com.rustyrazorblade.easydblab.configuration.StoreCredentials
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [GrafanaDashboardConfigMaps], which delivers the core dashboard tree through the K8s API
 * on every environment: one ConfigMap per dashboard, projected per folder into Grafana.
 */
class GrafanaDashboardConfigMapsTest : BaseKoinTest() {
    private val installContext = DashboardInstallContext("lab-abc123", TenantSet.of("acme"))
    private val claimed =
        ObservabilityHosting(Placement.ANY_NODE, BackendStorage.RetainedClaim("retained", Quantity("10Gi")), StoreCredentials.InstanceRole)
    private lateinit var grafana: List<HasMetadata>

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        grafana = GrafanaManifestBuilder(getKoin().get()).buildAllResources(TestScopes.EC2, DocumentsBucket("acct", "us-west-2"), claimed)
    }

    private val tree by lazy { GrafanaDashboardTreeWriter(TestDashboardCatalog.catalog).render("http://pyroscope:4040", installContext) }

    private fun dashboardMaps(resources: List<HasMetadata>) =
        resources.filterIsInstance<ConfigMap>().filter {
            it.metadata.labels[Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL] == Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE
        }

    private fun grafanaPod(resources: List<HasMetadata>) =
        resources
            .filterIsInstance<Deployment>()
            .single()
            .spec.template.spec

    private fun grafanaMounts(resources: List<HasMetadata>) =
        grafanaPod(resources)
            .containers
            .single { it.name == "grafana" }
            .volumeMounts
            .filter { it.mountPath.startsWith("$GRAFANA_DASHBOARD_ROOT/") }

    /** Each ConfigMap [volume] projects, with the file paths it projects from that map. */
    private fun projectedInto(
        resources: List<HasMetadata>,
        volume: String,
    ) = grafanaPod(resources)
        .volumes
        .single { it.name == volume }
        .projected.sources
        .map { source -> source.configMap.name to source.configMap.items.map { it.path } }

    @Test
    fun `each dashboard is its own ConfigMap, named for its folder and stem, holding only its file name`() {
        val small =
            mapOf(
                "cassandra/ab-comparison.json" to "{\"a\":1}",
                "infrastructure/System_Overview.v2.json" to "{\"b\":2}",
            )

        val maps = dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, small)).associateBy { it.metadata.name }

        assertThat(maps.keys).containsExactlyInAnyOrder(
            "grafana-dashboard-cassandra-ab-comparison",
            "grafana-dashboard-infrastructure-system-overview-v2",
        )
        assertThat(maps.getValue("grafana-dashboard-cassandra-ab-comparison").data)
            .isEqualTo(mapOf("ab-comparison.json" to "{\"a\":1}"))
        assertThat(maps.getValue("grafana-dashboard-infrastructure-system-overview-v2").data)
            .isEqualTo(mapOf("System_Overview.v2.json" to "{\"b\":2}"))
    }

    @Test
    fun `the shipped tree becomes one ConfigMap per dashboard, each with both labels and within budget`() {
        val maps = dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, tree))

        assertThat(maps).hasSize(tree.size)
        assertThat(maps).allSatisfy { map ->
            assertThat(map.metadata.name).startsWith(Constants.Grafana.DASHBOARD_CONFIGMAP_PREFIX)
            assertThat(map.metadata.labels)
                .containsEntry("app.kubernetes.io/name", "grafana")
                .containsEntry(Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL, Constants.Grafana.DASHBOARD_CONFIGMAP_LABEL_VALUE)
            assertThat(map.data).hasSize(1)
        }
        assertThat(maps.flatMap { it.data.values }).containsExactlyInAnyOrderElementsOf(tree.values)
    }

    @Test
    fun `removing one dashboard leaves every other ConfigMap name unchanged`() {
        val before = dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, tree)).map { it.metadata.name }
        val removed = tree.keys.first()

        val after = dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, tree - removed)).map { it.metadata.name }

        assertThat(after).hasSize(before.size - 1)
        assertThat(before).containsAll(after)
    }

    @Test
    fun `two paths that map to one ConfigMap name are refused, naming both`() {
        val clashing = mapOf("a-b/c.json" to "{}", "a/b-c.json" to "{}")

        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, clashing) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a-b/c.json")
            .hasMessageContaining("a/b-c.json")
    }

    @Test
    fun `the same file name in two folders is two ConfigMaps, each projected into its own folder volume`() {
        val sameName = mapOf("cassandra/overview.json" to "{\"a\":1}", "opensearch/overview.json" to "{\"b\":2}")

        val resources = GrafanaDashboardConfigMaps.mountInto(grafana, sameName)

        val maps = dashboardMaps(resources)
        assertThat(maps.map { it.metadata.name to it.data })
            .containsExactlyInAnyOrder(
                "grafana-dashboard-cassandra-overview" to mapOf("overview.json" to "{\"a\":1}"),
                "grafana-dashboard-opensearch-overview" to mapOf("overview.json" to "{\"b\":2}"),
            )
        assertThat(grafanaMounts(resources).map { it.name to it.mountPath })
            .containsExactlyInAnyOrder(
                "dashboards-cassandra" to "$GRAFANA_DASHBOARD_ROOT/cassandra",
                "dashboards-opensearch" to "$GRAFANA_DASHBOARD_ROOT/opensearch",
            )
        assertThat(projectedInto(resources, "dashboards-cassandra"))
            .containsExactly("grafana-dashboard-cassandra-overview" to listOf("overview.json"))
        assertThat(projectedInto(resources, "dashboards-opensearch"))
            .containsExactly("grafana-dashboard-opensearch-overview" to listOf("overview.json"))
    }

    @Test
    fun `a single dashboard too large for one ConfigMap is refused, naming it`() {
        val huge = mapOf("big/huge.json" to "x".repeat(Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES + 1))

        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, huge) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("big/huge.json")
    }

    @Test
    fun `a dashboard whose file name and JSON are exactly the budget is accepted, one byte more is refused`() {
        val fileName = "edge.json"
        // A two-byte character, so the budget is counted in bytes, not characters.
        val filler = "é" + "x".repeat(Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES - fileName.length - 2)
        val atBudget = mapOf("big/$fileName" to filler)
        assertThat(fileName.toByteArray().size + filler.toByteArray().size).isEqualTo(Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES)

        assertThat(dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, atBudget))).hasSize(1)
        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf("big/$fileName" to filler + "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("big/$fileName")
    }

    @Test
    fun `a path that is not exactly one folder and one file is refused, naming it`() {
        for (path in listOf("a/b/c.json", "top.json")) {
            assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf(path to "{}")) }
                .describedAs(path)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("exactly one folder")
                .hasMessageContaining(path)
        }
    }

    @Test
    fun `a dashboard whose ConfigMap name would exceed 253 characters is refused, naming it`() {
        // 18 (prefix) + 50 (folder) + 1 + 200 (stem) = 269 characters; the 205-character file name is a legal key.
        val path = "f".repeat(50) + "/" + "s".repeat(200) + ".json"

        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf(path to "{}")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ConfigMap name longer than 253")
            .hasMessageContaining(path)
    }

    @Test
    fun `a file name that is not a legal ConfigMap key is refused, naming it`() {
        val spaced = "cassandra/my dashboard.json"
        val long = "c/" + "k".repeat(250) + ".json"

        for (path in listOf(spaced, long)) {
            assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf(path to "{}")) }
                .describedAs(path)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not a legal ConfigMap key")
                .hasMessageContaining(path)
        }
    }

    @Test
    fun `a folder whose volume name Grafana's pod already declares is refused, naming it`() {
        // dashboards-config is the volume holding Grafana's dashboard provisioning config.
        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf("config/x.json" to "{}")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("dashboards-config")
            .hasMessageContaining("config/x.json")
    }

    @Test
    fun `a folder volume name of 63 characters is accepted, 64 is refused`() {
        // dashboards- is 11 characters, so a 52-character folder makes a 63-character volume name.
        val longest = "f".repeat(52)
        val tooLong = "f".repeat(53)

        assertThat(grafanaMounts(GrafanaDashboardConfigMaps.mountInto(grafana, mapOf("$longest/x.json" to "{}"))).map { it.name })
            .containsExactly("dashboards-$longest")
        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, mapOf("$tooLong/x.json" to "{}")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(tooLong)
    }

    @Test
    fun `a folder name that is not a DNS-1123 label is refused`() {
        val badFolder = mapOf("Bad_Folder/x.json" to "{}")

        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, badFolder) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Bad_Folder")
    }

    /**
     * A projected volume makes each top-level entry a symlink into its `..data` directory, and
     * Grafana's file provider does not follow a symlinked directory: a folder mounted as a subdirectory
     * of one volume is never swept. So each folder is its own volume, mounted at the folder's path,
     * which leaves only the files as symlinks inside a real directory.
     */
    @Test
    fun `each folder is one projected volume named for it, mounted read-only at its folder path`() {
        val resources = GrafanaDashboardConfigMaps.mountInto(grafana, tree)
        val pod = grafanaPod(resources)
        val mounts = grafanaMounts(resources)
        val folders = tree.keys.map { it.substringBefore('/') }.distinct()

        assertThat(mounts.map { it.name to it.mountPath })
            .containsExactlyInAnyOrderElementsOf(folders.map { "dashboards-$it" to "$GRAFANA_DASHBOARD_ROOT/$it" })
        assertThat(mounts).allSatisfy { assertThat(it.readOnly).isTrue() }
        val byName = dashboardMaps(resources).associateBy { it.metadata.name }
        val paths =
            mounts.flatMap { mount ->
                pod.volumes.single { it.name == mount.name }.projected.sources.flatMap { source ->
                    source.configMap.items.map { item ->
                        assertThat(item.path).describedAs("a file directly in its folder").doesNotContain("/")
                        "${mount.mountPath.removePrefix("$GRAFANA_DASHBOARD_ROOT/")}/${item.path}" to
                            byName.getValue(source.configMap.name).data.getValue(item.key)
                    }
                }
            }
        assertThat(paths.toMap()).isEqualTo(tree)
        val mounted = mounts.map { it.name }
        assertThat(pod.containers.filter { it.name != "grafana" }).allSatisfy { container ->
            assertThat(container.volumeMounts.orEmpty().map { it.name }).doesNotContainAnyElementsOf(mounted)
        }
    }

    @Test
    fun `the home dashboard lies under a mounted folder`() {
        val mounts = grafanaMounts(GrafanaDashboardConfigMaps.mountInto(grafana, tree))
        val home =
            grafanaPod(grafana)
                .containers
                .single { it.name == "grafana" }
                .env
                .single { it.name == "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH" }
                .value

        assertThat(mounts.map { it.mountPath }).anySatisfy { assertThat(home).startsWith("$it/") }
    }

    @Test
    fun `editing one dashboard changes Grafana's config hash`() {
        fun hash(tree: Map<String, String>): String? =
            ConfigHashAnnotator
                .annotate(GrafanaDashboardConfigMaps.mountInto(grafana, tree), mapOf("grafana-datasources" to emptyMap()))
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]

        val (path, json) = tree.entries.first()
        val edited = tree + (path to json.replaceFirst("\"title\"", "\"Title\""))
        assertThat(edited.getValue(path)).isNotEqualTo(json).hasSameSizeAs(json)
        assertThat(hash(tree)).isNotNull().isEqualTo(hash(tree)).isNotEqualTo(hash(edited))
    }
}
