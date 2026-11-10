package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
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
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.mock
import org.mockito.stubbing.Answer
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
 * workspace. A fixture kit (`typed-steps-kit`) then runs the ten typed steps that drive helm or
 * kubectl through [WorkloadStepExecutor] and the production services, with `ClusterTooling` rebound
 * to the local form and every [RemoteOperationsService] call failing the test: no step may use SSH.
 *
 * The test starts its own K3s container and does not share [SharedK3s]'s, because the kit applies
 * cluster-scoped kinds (a namespace, a CRD). Its workspace is a `@TempDir` that holds the container's kubeconfig and the
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

        private const val KIT_RESOURCE_DIRECTORY = "/com/rustyrazorblade/easydblab/services/typed-steps-kit"
        private val KIT_FILES =
            listOf("kit.yaml", "values.yaml", "manifest.yaml", "kustomize/kustomization.yaml", "kustomize/configmaps.yaml")
        private const val SERVED_RESOURCE_DIRECTORY = "/com/rustyrazorblade/easydblab/services/typed-steps-served"
        private const val EXTRA_NAMESPACE = "typed-steps-extra"
        private const val CRD_NAME = "widgets.typedsteps.example.com"
        private const val GREETING = "from the step variable"

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

    /**
     * Runs the fixture kit's install, stop and uninstall steps through [WorkloadStepExecutor] with the production
     * services, rebinding only [ClusterTooling] to the local form. [RemoteOperationsService] is a mock that
     * throws on any call, so a step that reached for SSH fails its phase, and the recorded calls are checked
     * to be none at the end. Kit files, the chart repository and the url-served manifest all come from the
     * test, through one [MockWebServer] and the workspace.
     */
    @Test
    fun `the ten typed steps run through the local form with no SSH`() {
        val served = File(workspace, "served").apply { mkdirs() }
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.dispatcher = FilesDispatcher(served)
            val url = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.port}"
            buildRepository(served, url)
            copyResources(SERVED_RESOURCE_DIRECTORY, listOf("manifest-url.yaml"), served)
            val kitDir = File(workspace, "typed-steps")
            copyResources(KIT_RESOURCE_DIRECTORY, KIT_FILES, kitDir)
            val kit = installConfigYaml.decodeFromString(KitConfig.serializer(), File(kitDir, "kit.yaml").readText())
            val variables =
                mapOf(
                    "REPO_URL" to url,
                    "MANIFEST_URL" to "$url/manifest-url.yaml",
                    "KUSTOMIZE_DIR" to File(kitDir, "kustomize").absolutePath,
                    "GREETING" to GREETING,
                )
            val remoteOps =
                mock<RemoteOperationsService>(defaultAnswer = Answer<Any> { throw AssertionError("SSH used: ${it.method.name}") })
            val app = koinApplication { modules(servicesModule, localToolingModule(remoteOps)) }
            try {
                val events = recordEvents(app.koin.get())
                val phase = { name: String, steps: List<InstallStep> -> runPhase(app.koin.get(), name, steps, kitDir, variables) }

                phase("install", kit.install)
                assertInstalled()
                assertReleasesFound(app.koin.get())
                phase("stop", kit.stop)
                assertStopped()
                phase("uninstall", kit.uninstall)
                assertUninstalled(events)
                assertThat(app.koin.get<HelmService>().releaseExists("typed-removed", NAMESPACE)).isFalse()
                assertThat(mockingDetails(remoteOps).invocations).describedAs("SSH calls").isEmpty()
            } finally {
                app.close()
            }
        }
    }

    /** Production wiring with the seam rebound to the local form, which is how a harness selects it until #1040. */
    private fun localToolingModule(remoteOps: RemoteOperationsService) =
        module {
            single<ClusterTooling> { tooling }
            single<RemoteOperationsService> { remoteOps }
            single<K8sService> { mock() }
            single { EventBus() }
            single { ToolWrapperInstaller() }
        }

    /** Runs [steps] as phase [name] of the fixture kit; a failed step throws its own exception, with the tool's stderr. */
    private fun runPhase(
        executor: WorkloadStepExecutor,
        name: String,
        steps: List<InstallStep>,
        kitDir: File,
        variables: Map<String, String>,
    ) {
        val context =
            StepExecutionContext(
                kitName = "typed-steps",
                controlHost = ClusterHost("203.0.113.1", "10.0.0.1", "control0", "us-west-2a"),
                clusterState = ClusterState(name = "typed-steps", versions = mutableMapOf()),
                variables = variables,
                kitDir = kitDir,
                workspaceDir = workspace,
            )
        executor.execute(steps, name, context).getOrThrow()
    }

    private fun recordEvents(bus: EventBus): List<Event> {
        val events = mutableListOf<Event>()
        bus.addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        return events
    }

    private fun configMapData(name: String): Map<String, String>? =
        client
            .configMaps()
            .inNamespace(NAMESPACE)
            .withName(name)
            .get()
            ?.data

    /** What the install steps made: helm values from a map and from a file, a namespace, a manifest, a url, a kustomization. */
    private fun assertInstalled() {
        assertThat(configMapData("typed-kept-config")).containsEntry("greeting", "set by map")
        assertThat(configMapData("typed-removed-config")).containsEntry("greeting", "values from the file")
        assertThat(client.namespaces().withName(EXTRA_NAMESPACE).get()).isNotNull
        assertThat(configMapData("typed-manifest")).containsEntry("greeting", GREETING)
        assertThat(
            client
                .apiextensions()
                .v1()
                .customResourceDefinitions()
                .withName(CRD_NAME)
                .get(),
        ).isNotNull
        assertThat(configMapData("typed-manifest-url")).containsEntry("from", "manifest-url")
        assertThat(configMapData("typed-kustomize-selected")).containsEntry("from", "kustomize")
    }

    /**
     * `releaseExists` filters with `^<release>$`. The local form hands that to helm as it is, with no shell
     * to read the `$`, and the anchors make a name that is only a prefix of a release not match it.
     */
    private fun assertReleasesFound(helm: HelmService) {
        assertThat(helm.releaseExists("typed-kept", NAMESPACE)).isTrue()
        assertThat(helm.releaseExists("typed", NAMESPACE)).isFalse()
    }

    /** The delete by name removed its object, and the selector (spaces and parentheses included) removed exactly its matches. */
    private fun assertStopped() {
        assertThat(configMapData("typed-manifest-url")).isNull()
        assertThat(configMapData("typed-kustomize-selected")).isNull()
        assertThat(configMapData("typed-kustomize-other")).isNull()
        assertThat(configMapData("typed-kustomize-kept")).containsEntry("from", "kustomize")
    }

    /** The release something still uses is kept and reported; the one nothing uses is uninstalled. */
    private fun assertUninstalled(events: List<Event>) {
        assertThat(events.filterIsInstance<Event.Kit.HelmReleaseKept>().map { it.release }).containsExactly("typed-kept")
        assertThat(configMapData("typed-kept-config")).isNotNull
        assertThat(configMapData("typed-removed-config")).isNull()
    }

    /** Packages the fixture chart into [directory] and writes the `index.yaml` that points at [url]. */
    private fun buildRepository(
        directory: File,
        url: String,
    ) {
        val chart = File(workspace, "chart-source").apply { mkdirs() }
        copyResources(CHART_RESOURCE_DIRECTORY, CHART_FILES, chart)
        tooling.run(ClusterTool.HELM, listOf("package", chart.absolutePath, "--destination", directory.absolutePath), echo = false)
        tooling.run(ClusterTool.HELM, listOf("repo", "index", directory.absolutePath, "--url", url), echo = false)
    }

    /** Copies the classpath resources [files] under [resourceDirectory] into [target], keeping their relative paths. */
    private fun copyResources(
        resourceDirectory: String,
        files: List<String>,
        target: File,
    ) {
        for (path in files) {
            val destination = File(target, path).apply { parentFile.mkdirs() }
            destination.writeBytes(
                checkNotNull(LocalClusterToolingIntegrationTest::class.java.getResourceAsStream("$resourceDirectory/$path")) {
                    "Missing fixture file $path under $resourceDirectory"
                }.use { it.readBytes() },
            )
        }
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
