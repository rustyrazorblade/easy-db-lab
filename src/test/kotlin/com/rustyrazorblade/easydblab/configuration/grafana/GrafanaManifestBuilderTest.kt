package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for GrafanaManifestBuilder.
 *
 * Uses real TemplateService (never mocked per project convention) to verify
 * dashboard JSON loading and template variable substitution.
 */
class GrafanaManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: GrafanaManifestBuilder
    private lateinit var templateService: TemplateService
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            ),
        )
        templateService = getKoin().get()
        builder = GrafanaManifestBuilder(templateService)
    }

    @Test
    fun `the Cassandra folder has its own provisioning provider naming the folder`() {
        // Folders come from a provider that names one, not from the directory structure. The
        // alternative, foldersFromFilesStructure, derives a folder per subdirectory - and since
        // every dashboard already sits in a subdirectory named after itself, it would file the
        // other ten into folders called "system", "s3" and so on.
        val yaml = provisioningYaml()

        // Settings only: the file's own comment explains why foldersFromFilesStructure is absent,
        // and names it while doing so.
        assertThat(settingsOf(yaml)).doesNotContain("foldersFromFilesStructure")
        assertThat(yaml).contains("folder: 'Cassandra'")
        assertThat(yaml).contains("path: $GRAFANA_CASSANDRA_PATH")
    }

    private fun provisioningYaml(): String =
        checkNotNull(builder.buildDashboardProvisioningConfigMap().data["dashboards.yaml"]) {
            "provisioning ConfigMap has no dashboards.yaml key"
        }

    private fun settingsOf(yaml: String): String = yaml.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

    @Test
    fun `the root provider still files its dashboards at the root of the list`() {
        val yaml = provisioningYaml()

        assertThat(yaml).contains("folder: ''")
        assertThat(yaml).contains("folderUid: ''")
        assertThat(yaml).contains("path: $GRAFANA_DASHBOARD_ROOT\n")
    }

    @Test
    fun `every dashboard mounts under the provider that owns its folder`() {
        // A dashboard mounted outside its provider's path lands in the wrong folder, or in no
        // folder at all if nothing sweeps where it was mounted. Neither fails anything at deploy
        // time; the dashboard is just missing from where someone looks for it.
        GrafanaDashboard.entries.forEach { dashboard ->
            assertThat(dashboard.mountPath)
                .describedAs("mount path for ${dashboard.name}")
                .startsWith("${dashboard.folderPath}/")
        }
    }

    @Test
    fun `the Cassandra dashboards are the ones in the Cassandra folder`() {
        val cassandra = GrafanaDashboard.entries.filter { it.folder == GRAFANA_CASSANDRA_FOLDER }

        assertThat(cassandra).containsExactlyInAnyOrder(
            GrafanaDashboard.CASSANDRA_OVERVIEW,
            GrafanaDashboard.CLUSTER_COMPARISON,
            GrafanaDashboard.CASSANDRA_JVM,
            GrafanaDashboard.READ_PATH_ANATOMY,
            GrafanaDashboard.WRITE_PATH_BACKPRESSURE,
            GrafanaDashboard.NODE_DIVERGENCE,
        )
        assertThat(cassandra).allSatisfy { dashboard ->
            assertThat(dashboard.mountPath).startsWith("$GRAFANA_CASSANDRA_PATH/")
            // Every dashboard in this folder is optional: four of the six are being written now,
            // and a missing JSON must never stop Grafana from starting.
            assertThat(dashboard.optional).isTrue()
        }
    }

    @Test
    fun `every other dashboard stays at the root path`() {
        val root = GrafanaDashboard.entries.filter { it.folder.isEmpty() }

        assertThat(root).contains(GrafanaDashboard.SYSTEM, GrafanaDashboard.TEMPO, GrafanaDashboard.CLICKHOUSE)
        assertThat(root).allSatisfy { dashboard ->
            assertThat(dashboard.mountPath).startsWith("$GRAFANA_DASHBOARD_ROOT/")
            assertThat(dashboard.mountPath).doesNotContain("$GRAFANA_DASHBOARD_ROOT-")
        }
    }

    @Test
    fun `buildAllResources skips an optional dashboard whose JSON does not exist yet`() {
        // The volume's optional flag only covers a missing ConfigMap. Building the ConfigMap reads
        // the JSON off the classpath and fails hard when it is absent, so an enum entry added
        // ahead of its JSON would break the whole deployment without this.
        //
        // Stated as a rule rather than a head count, so it holds both while a dashboard's JSON is
        // still being written and after it lands: a dashboard is in the output exactly when its
        // JSON exists, and anything left out has to have been optional.
        val configMapNames = builder.buildAllResources().map { it.metadata.name }

        GrafanaDashboard.entries.forEach { dashboard ->
            if (javaClass.getResource("/${dashboard.jsonFileName}") != null) {
                assertThat(configMapNames)
                    .describedAs("${dashboard.name} has JSON, so it must be deployed")
                    .contains(dashboard.configMapName)
            } else {
                assertThat(dashboard.optional)
                    .describedAs("${dashboard.name} has no JSON, so it must be optional")
                    .isTrue()
                assertThat(configMapNames)
                    .describedAs("${dashboard.name} has no JSON, so it must be skipped")
                    .doesNotContain(dashboard.configMapName)
            }
        }

        // Vacuity guard: the loop above proves nothing if the enum is ever empty.
        assertThat(configMapNames).contains(
            GrafanaDashboard.SYSTEM.configMapName,
            GrafanaDashboard.CASSANDRA_OVERVIEW.configMapName,
        )
    }

    @Test
    fun `buildDashboardConfigMap preserves Grafana built-in variables`() {
        val configMap = builder.buildDashboardConfigMap(GrafanaDashboard.CLICKHOUSE)
        val json = configMap.data[GrafanaDashboard.CLICKHOUSE.jsonFileName]!!

        assertThat(json).contains("\$__rate_interval")
    }

    @Test
    fun `buildDeployment includes grafana and image renderer containers`() {
        val deployment = builder.buildDeployment()
        val containers = deployment.spec.template.spec.containers

        assertThat(containers).hasSize(2)
        assertThat(containers.map { it.name }).containsExactly("grafana", "grafana-image-renderer")
    }

    @Test
    fun `buildDeployment rendering env vars reference correct ports`() {
        val deployment = builder.buildDeployment()
        val containers = deployment.spec.template.spec.containers
        val grafanaContainer = containers.first { it.name == "grafana" }
        val rendererContainer = containers.first { it.name == "grafana-image-renderer" }

        val rendererPort = rendererContainer.ports.first().containerPort
        val grafanaPort = grafanaContainer.ports.first().containerPort
        val envMap = grafanaContainer.env.associate { it.name to it.value }

        assertThat(envMap["GF_RENDERING_SERVER_URL"]).contains(":$rendererPort/")
        assertThat(envMap["GF_RENDERING_CALLBACK_URL"]).contains(":$grafanaPort/")
    }

    @Test
    fun `buildDeployment does not attempt to install the bundled Pyroscope core plugin`() {
        // Regression: grafana-pyroscope-datasource is a BUNDLED core plugin in Grafana 13.x.
        // Listing it in GF_INSTALL_PLUGINS makes the boot-time install fail
        // ("cannot install a Core plugin") and Grafana CrashLoopBackOffs. Only externally
        // distributed plugins may appear here.
        val deployment = builder.buildDeployment()
        val grafanaContainer =
            deployment.spec.template.spec.containers
                .first { it.name == "grafana" }
        val plugins =
            grafanaContainer.env
                .first { it.name == "GF_INSTALL_PLUGINS" }
                .value
                .split(",")

        assertThat(plugins).doesNotContain("grafana-pyroscope-datasource")
        assertThat(plugins).contains(
            "grafana-clickhouse-datasource",
            "victoriametrics-logs-datasource",
            "grafana-polystat-panel",
        )
    }

    @Test
    fun `buildDeployment includes volume mounts for all dashboards`() {
        val deployment = builder.buildDeployment()
        val container =
            deployment.spec.template.spec.containers
                .first()

        GrafanaDashboard.entries.forEach { dashboard ->
            val mount = container.volumeMounts.find { it.name == dashboard.volumeName }
            assertThat(mount)
                .describedAs("Volume mount for ${dashboard.name}")
                .isNotNull
            assertThat(mount!!.mountPath).isEqualTo(dashboard.mountPath)
            assertThat(mount.readOnly).isTrue()
        }
    }

    @Test
    fun `buildDeployment includes volumes for all dashboards with correct optional flag`() {
        val deployment = builder.buildDeployment()
        val volumes = deployment.spec.template.spec.volumes

        GrafanaDashboard.entries.forEach { dashboard ->
            val volume = volumes.find { it.name == dashboard.volumeName }
            assertThat(volume)
                .describedAs("Volume for ${dashboard.name}")
                .isNotNull
            assertThat(volume!!.configMap.name).isEqualTo(dashboard.configMapName)
            assertThat(volume.configMap.optional)
                .describedAs("Optional flag for ${dashboard.name}")
                .isEqualTo(dashboard.optional)
        }
    }
}
