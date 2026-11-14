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
 * Tests for [GrafanaDashboardConfigMaps], which delivers the dashboard tree through the K8s API where
 * Grafana's state is on a claim and there is no node directory to copy it into.
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
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        grafana = GrafanaManifestBuilder(getKoin().get()).buildAllResources(DocumentsBucket("acct", "us-west-2"), claimed)
    }

    private val tree by lazy { GrafanaDashboardTreeWriter(TestDashboardCatalog.catalog).render("http://pyroscope:4040", installContext) }

    private fun dashboardMaps(resources: List<HasMetadata>) =
        resources.filterIsInstance<ConfigMap>().filter { it.metadata.name.startsWith(GrafanaDashboardConfigMaps.NAME_PREFIX) }

    private fun grafanaPod(resources: List<HasMetadata>) =
        resources
            .filterIsInstance<Deployment>()
            .single()
            .spec.template.spec

    @Test
    fun `the shipped tree is split into ConfigMaps that each fit well under the API's object size limit`() {
        val maps = dashboardMaps(GrafanaDashboardConfigMaps.mountInto(grafana, tree))

        assertThat(maps).hasSizeGreaterThan(1)
        assertThat(maps).allSatisfy { map ->
            val bytes = map.data.entries.sumOf { (key, value) -> key.toByteArray().size + value.toByteArray().size }
            assertThat(bytes).describedAs(map.metadata.name).isLessThanOrEqualTo(Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES)
        }
        assertThat(maps.flatMap { it.data.values }).containsExactlyInAnyOrderElementsOf(tree.values)
    }

    @Test
    fun `Grafana's container sees every dashboard at its folder path under the tree root, read-only`() {
        val resources = GrafanaDashboardConfigMaps.mountInto(grafana, tree)
        val pod = grafanaPod(resources)

        val mount =
            pod.containers
                .single { it.name == "grafana" }
                .volumeMounts
                .single { it.mountPath == GRAFANA_DASHBOARD_ROOT }
        assertThat(mount.readOnly).isTrue()
        val volume = pod.volumes.single { it.name == mount.name }
        val keyToMap = dashboardMaps(resources).flatMap { map -> map.data.keys.map { it to map } }.toMap()
        val paths =
            volume.projected.sources.flatMap { source ->
                source.configMap.items.map { item ->
                    assertThat(keyToMap.getValue(item.key).metadata.name).isEqualTo(source.configMap.name)
                    item.path to keyToMap.getValue(item.key).data.getValue(item.key)
                }
            }
        assertThat(paths.toMap()).isEqualTo(tree)
        assertThat(pod.containers.filter { it.name != "grafana" }).allSatisfy { container ->
            assertThat(container.volumeMounts.orEmpty().map { it.name }).doesNotContain(mount.name)
        }
    }

    @Test
    fun `a dashboard change changes Grafana's config hash`() {
        fun hash(tree: Map<String, String>): String? =
            ConfigHashAnnotator
                .annotate(GrafanaDashboardConfigMaps.mountInto(grafana, tree), mapOf("grafana-datasources" to emptyMap()))
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]

        // Same length, so the packing and the pod template stay the same and only the ConfigMap data differs.
        val (path, json) = tree.entries.first()
        val edited = tree + (path to json.replaceFirst("\"title\"", "\"Title\""))
        assertThat(edited.getValue(path)).isNotEqualTo(json).hasSameSizeAs(json)
        assertThat(hash(tree)).isNotNull().isEqualTo(hash(tree)).isNotEqualTo(hash(edited))
    }

    @Test
    fun `a single dashboard too large for one ConfigMap is refused`() {
        val huge = mapOf("big/huge.json" to "x".repeat(Constants.Grafana.DASHBOARD_CONFIGMAP_MAX_BYTES))

        assertThatThrownBy { GrafanaDashboardConfigMaps.mountInto(grafana, huge) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("big/huge.json")
    }
}
