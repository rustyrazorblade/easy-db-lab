package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardProvisioningConfig
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * A dashboard install with a reload returns only once the running Grafana serves the new tree.
 *
 * Grafana 13.2.2 reads the tree through a bind mount of the stack's `dashboards/` directory. Right
 * after the installer renames a new tree into `current`, Docker Desktop's file sharing can still show
 * the container the old, removed directory, empty, for a few hundred milliseconds. A reload that walks
 * it then deletes every provisioned dashboard, saves none, and answers 200, and nothing walks the tree
 * again. Each cycle here swaps the packaged tree and a one-dashboard tree with a new uid, as
 * `local dashboards --from` does, and checks with no wait that Grafana serves the tree just installed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalStackDashboardsReloadIntegrationTest {
    private companion object {
        const val GRAFANA_PORT = 3000
        const val CYCLES = 10
        const val HTTP_OK = 200
        val STARTUP: Duration = Duration.ofMinutes(3)
        const val PROVISIONING = "/etc/grafana/provisioning/dashboards"
    }

    private lateinit var root: File
    private lateinit var stack: File
    private lateinit var grafanaContainer: GenericContainer<*>
    private lateinit var manifest: LocalStackManifest
    private val grafana = LocalGrafanaHttp()
    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
    private val packaged = PackagedDashboardTree(GrafanaDashboardCatalog.discover(), emptyList())

    @BeforeAll
    fun start(
        @TempDir tempDir: File,
    ) {
        root = tempDir
        stack = File(root, "lab-local")
        val provisioning =
            File(root, "provisioning").apply {
                mkdirs()
                File(this, "dashboards.yaml").writeText(
                    GrafanaDashboardProvisioningConfig
                        .forDashboardTree("${Constants.LocalStack.DASHBOARDS_MOUNT}/${Constants.LocalStack.DASHBOARDS_TREE}")
                        .toYaml(),
                )
            }
        manifest =
            LocalStackManifest(
                toolVersion = "it",
                mode = LocalStackSpec.Mode.Live.MANIFEST_NAME,
                homeTenant = "default",
                tenants = listOf("default"),
                grafanaPort = 0,
                documentsPort = 0,
                files = emptyMap(),
            )
        LocalStackDashboards(grafana, packaged).install(stack, manifest, source = null, reload = false)
        grafanaContainer =
            GenericContainer(GrafanaManifestBuilder.GRAFANA_IMAGE)
                .withExposedPorts(GRAFANA_PORT)
                .withFileSystemBind(provisioning.absolutePath, PROVISIONING, BindMode.READ_ONLY)
                .withFileSystemBind(
                    File(stack, Constants.LocalStack.DASHBOARDS_DIR).absolutePath,
                    Constants.LocalStack.DASHBOARDS_MOUNT,
                    BindMode.READ_ONLY,
                ).withEnv("GF_SECURITY_ADMIN_PASSWORD", Constants.Grafana.ADMIN_PASSWORD)
                // Logs each file a reload's walk saves or deletes, for the failure description.
                .withEnv("GF_LOG_FILTERS", "provisioning.dashboard:debug")
                .waitingFor(Wait.forHttp("/api/health").forStatusCode(HTTP_OK).withStartupTimeout(STARTUP))
                .apply { start() }
        manifest = manifest.copy(grafanaPort = grafanaContainer.getMappedPort(GRAFANA_PORT))
        // Grafana answers its health check before its first provisioning pass has finished.
        val deadline = System.nanoTime() + STARTUP.toNanos()
        while (!served(homeUid()) && System.nanoTime() < deadline) Thread.sleep(Duration.ofSeconds(1).toMillis())
        assertThat(served(homeUid())).describedAs("the packaged home dashboard after startup").isTrue()
    }

    @AfterAll
    fun stop() {
        if (::grafanaContainer.isInitialized) grafanaContainer.stop()
    }

    private fun homeUid(): String =
        Json
            .parseToJsonElement(File(stack, "dashboards/current/${Constants.Grafana.HOME_DASHBOARD_PATH}").readText())
            .jsonObject
            .getValue("uid")
            .jsonPrimitive.content

    private fun served(uid: String): Boolean {
        val auth = Base64.getEncoder().encodeToString("${Constants.Grafana.ADMIN_USER}:${Constants.Grafana.ADMIN_PASSWORD}".toByteArray())
        val request =
            HttpRequest
                .newBuilder(URI("http://${Constants.LocalStack.LOOPBACK}:${manifest.grafanaPort}/api/dashboards/uid/$uid"))
                .header("Authorization", "Basic $auth")
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
    }

    @Test
    fun `every install with a reload returns with Grafana serving the tree it installed`() {
        val dashboards = LocalStackDashboards(grafana, packaged)
        val missed = mutableListOf<String>()
        repeat(CYCLES) { cycle ->
            dashboards.install(stack, manifest, source = null, reload = true)
            if (!served(homeUid())) missed += "cycle $cycle: the packaged tree"

            val probe = "edl-reload-probe-$cycle"
            val work =
                File(root, "work-$cycle").apply {
                    File(this, "mine/probe.json")
                        .apply { parentFile.mkdirs() }
                        .writeText("""{"uid":"$probe","title":"Reload probe $cycle","panels":[],"schemaVersion":39}""")
                }
            dashboards.install(stack, manifest, DirectoryDashboardTree(work), reload = true)
            if (!served(probe)) missed += "cycle $cycle: $probe"
        }

        // On a failure, Grafana's provisioning lines show what each reload's walk found on disk.
        val walks = grafanaContainer.logs.lines().filter { "provisioning.dashboard" in it && "up to date" !in it }
        assertThat(missed)
            .describedAs("installs that returned before Grafana served their tree\n${walks.joinToString("\n")}")
            .isEmpty()
    }
}
