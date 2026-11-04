package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.InetAddress

/**
 * Runs [LocalClusterTooling] with the host's real helm and kubectl against a real K3s cluster, to
 * prove what the unit tests cannot: that stdin reaches the tool (`apply -f -`, `--values -`), that a
 * failure carries the tool's exit status and stderr, and that helm keeps its repository under the
 * workspace.
 *
 * The test starts its own K3s container and does not share [SharedK3s]'s, because later stages apply
 * cluster-scoped kinds. Its workspace is a `@TempDir` that holds the container's kubeconfig and the
 * `direct` key in `.socks5-proxy.env` (`ProxyEnvFile.recordDirect`), so the workspace wrappers reach
 * the cluster without a tunnel. Everything the fixtures need is local: the helm repository is an okhttp
 * `MockWebServer` serving an `index.yaml` and a chart archive that the test builds with `helm package`
 * and `helm repo index` from the fixture chart under `src/integrationTest/resources`. The test needs
 * only Docker, helm and kubectl, and fails with a message naming whichever is missing. It never skips.
 */
class LocalClusterToolingIntegrationTest {
    companion object {
        private val log = LoggerFactory.getLogger(LocalClusterToolingIntegrationTest::class.java)

        private const val NAMESPACE = "default"
        private const val CHART_RESOURCE_DIRECTORY = "/com/rustyrazorblade/easydblab/services/cluster-tooling-chart"
        private val CHART_FILES = listOf("Chart.yaml", "values.yaml", "templates/configmap.yaml")
        private const val CHART_NAME = "cluster-tooling-fixture"
        private const val CHART_VERSION = "0.1.0"
        private const val REPO_NAME = "fixture"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val KUBECTL_FAILURE_STATUS = 1

        private lateinit var k3s: K3sContainer

        @BeforeAll
        @JvmStatic
        fun startCluster() {
            // Checked before the container starts, so a missing tool costs seconds and not a K3s boot.
            requireDocker()
            requireOnPath("helm", "kubectl")
            val container = K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
            container.withPrivilegedMode(true)
            container.withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!
                    .withCgroupnsMode("host")
                    .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
            }
            container.withLogConsumer(Slf4jLogConsumer(log))
            container.withEnv("K3S_SNAPSHOTTER", "native")
            container.withPreloadedImages()
            container.start()
            k3s = container
        }

        @AfterAll
        @JvmStatic
        fun stopCluster() {
            if (::k3s.isInitialized) k3s.stop()
        }

        private fun requireDocker() {
            check(DockerClientFactory.instance().isDockerAvailable) {
                "Docker is not available, so the K3s container this test needs cannot start. Start (or restart) Docker and run it again."
            }
        }

        private fun requireOnPath(vararg tools: String) {
            val directories =
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .filter { it.isNotEmpty() }
            val missing = tools.filter { tool -> directories.none { File(it, tool).let { file -> file.isFile && file.canExecute() } } }
            check(missing.isEmpty()) {
                "${missing.joinToString()} not found on PATH. The local form runs the host's helm and kubectl, so this test needs both " +
                    "installed (CI installs pinned versions in .github/workflows/pr-checks.yml). It does not skip without them."
            }
        }
    }

    @TempDir
    lateinit var workspace: File

    private lateinit var tooling: LocalClusterTooling
    private lateinit var client: KubernetesClient

    @BeforeEach
    fun setup() {
        assertSocksPropertiesUnset()
        File(workspace, Constants.K3s.LOCAL_KUBECONFIG).writeText(k3s.kubeConfigYaml)
        ProxyEnvFile(workspace).recordDirect(true)
        tooling = LocalClusterTooling(workspace, KitProcessEnvironment(ToolWrapperInstaller()))
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
    }

    @AfterEach
    fun tearDown() {
        client.close()
        assertSocksPropertiesUnset()
    }

    @Test
    fun `kubectl apply reads the manifest from stdin and the object is in the cluster`() {
        val manifest = configMap("applied-from-stdin", "greeting" to "hello from stdin")

        val output =
            tooling.run(
                ClusterTool.KUBECTL,
                listOf("apply", "-f", Constants.ClusterTooling.STDIN_ARGUMENT),
                Serialization.asYaml(manifest),
                echo = false,
            )

        assertThat(output).contains("applied-from-stdin")
        assertThat(
            client
                .configMaps()
                .inNamespace(NAMESPACE)
                .withName("applied-from-stdin")
                .get()
                .data,
        ).containsEntry("greeting", "hello from stdin")
    }

    @Test
    fun `a kubectl failure carries exit status 1 and a stderr that names the resource type`() {
        val failure =
            runCatching { tooling.run(ClusterTool.KUBECTL, listOf("get", "nosuchkind"), echo = false) }
                .exceptionOrNull()

        assertThat(failure).isInstanceOf(ClusterToolFailedException::class.java)
        failure as ClusterToolFailedException
        assertThat(failure.tool).isEqualTo(ClusterTool.KUBECTL)
        assertThat(failure.exitStatus).isEqualTo(KUBECTL_FAILURE_STATUS)
        assertThat(failure.stderr).contains("nosuchkind")
    }

    @Test
    fun `helm installs a chart from a repository with values on stdin and keeps the repository under the workspace`() {
        val repositoryDirectory = File(workspace, "chart-repository").apply { mkdirs() }
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.dispatcher = FilesDispatcher(repositoryDirectory)
            val url = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.port}"
            buildRepository(repositoryDirectory, url)

            tooling.run(ClusterTool.HELM, listOf("repo", "add", REPO_NAME, url), echo = false)
            tooling.run(ClusterTool.HELM, listOf("repo", "update"), echo = false)
            val values = Serialization.asYaml(mapOf("greeting" to "values from stdin"))
            tooling.run(
                ClusterTool.HELM,
                listOf(
                    "upgrade",
                    "--install",
                    "fixture-release",
                    "$REPO_NAME/$CHART_NAME",
                    "--version",
                    CHART_VERSION,
                    "--namespace",
                    NAMESPACE,
                    "--values",
                    Constants.ClusterTooling.STDIN_ARGUMENT,
                ),
                values,
                echo = false,
            )
        }

        // The value on stdin, not the chart's default, reached the rendered object.
        assertThat(
            client
                .configMaps()
                .inNamespace(NAMESPACE)
                .withName("fixture-release-config")
                .get()
                .data,
        ).containsEntry("greeting", "values from stdin")
        val repositories = File(workspace, "${Constants.ClusterTooling.HELM_HOME_DIRECTORY}/config/repositories.yaml")
        assertThat(repositories).isFile
        assertThat(repositories.readText()).contains(REPO_NAME)
    }

    /** Packages the fixture chart into [directory] and writes the `index.yaml` that points at [url]. */
    private fun buildRepository(
        directory: File,
        url: String,
    ) {
        val chart = File(workspace, "chart-source").apply { mkdirs() }
        for (path in CHART_FILES) {
            val target = File(chart, path).apply { parentFile.mkdirs() }
            target.writeBytes(
                checkNotNull(LocalClusterToolingIntegrationTest::class.java.getResourceAsStream("$CHART_RESOURCE_DIRECTORY/$path")) {
                    "Missing fixture chart file $path under $CHART_RESOURCE_DIRECTORY"
                }.use { it.readBytes() },
            )
        }
        tooling.run(ClusterTool.HELM, listOf("package", chart.absolutePath, "--destination", directory.absolutePath), echo = false)
        tooling.run(ClusterTool.HELM, listOf("repo", "index", directory.absolutePath, "--url", url), echo = false)
    }

    private fun configMap(
        name: String,
        vararg data: Pair<String, String>,
    ): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(NAMESPACE)
            .endMetadata()
            .withData<String, String>(data.toMap())
            .build()

    private fun assertSocksPropertiesUnset() {
        assertThat(System.getProperty("socksProxyHost")).isNull()
        assertThat(System.getProperty("socksProxyPort")).isNull()
    }

    /** Serves the files directly inside [directory] by name and answers 404 for anything else. */
    private class FilesDispatcher(
        private val directory: File,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val file = File(directory, request.target.substringBefore('?').removePrefix("/"))
            return if (file.isFile && file.parentFile == directory) {
                MockResponse
                    .Builder()
                    .code(HTTP_OK)
                    .body(Buffer().write(file.readBytes()))
                    .build()
            } else {
                MockResponse.Builder().code(HTTP_NOT_FOUND).build()
            }
        }
    }
}
