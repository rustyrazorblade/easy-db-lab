package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NetworkMode
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.di.configurationModule
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.currentContextOf
import com.rustyrazorblade.easydblab.kubernetes.kubernetesModule
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import com.rustyrazorblade.easydblab.services.KitProcessEnvironment
import io.fabric8.kubernetes.client.Config
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock
import java.io.File
import java.net.ServerSocket
import java.net.URI

/**
 * A K3s in Docker workspace reaches a real K3s API with no SOCKS tunnel: the `@RequiresProxy`
 * preflight never asks for one and leaves no port published, the production Kubernetes client
 * factory reaches the API (and ignores a port some earlier command left published), and the
 * workspace's `kubectl` wrapper, launched through [KitProcessEnvironment], runs the real kubectl
 * without asking for `socks start`. The SOCKS service fails the test on any call.
 *
 * Needs Docker and kubectl (CI installs it in `.github/workflows/pr-checks.yml`).
 */
@ResourceLock(Constants.Proxy.PORT_PROPERTY)
class DirectNetworkModeIntegrationTest {
    @TempDir
    lateinit var workspace: File

    private lateinit var koin: KoinApplication
    private val stateManager by lazy { ClusterStateManager(File(workspace, "state.json")) }
    private val kubeconfig by lazy { File(workspace, Constants.K3s.LOCAL_KUBECONFIG) }

    @BeforeEach
    fun workspace() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        kubeconfig.writeText(SharedK3s.kubeConfigYaml())
        stateManager.replace(
            ClusterState(name = "local", environment = Environment.K3sDocker(File(workspace, "data").path))
                .apply { markInfrastructureUp() },
        )
        koin =
            koinApplication {
                modules(
                    configurationModule,
                    kubernetesModule,
                    module { single { Context.forCli(File(workspace, "user"), workspace) } },
                )
            }
    }

    @AfterEach
    fun cleanUp() {
        koin.close()
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
        assertThat(System.getProperty("socksProxyHost")).isNull()
        assertThat(System.getProperty("socksProxyPort")).isNull()
    }

    @Test
    fun `the preflight starts no tunnel and the API is reached with no proxy`() {
        val envFile = ProxyEnvFile(workspace)
        ProxyPreflight(
            stateManager,
            mock(defaultAnswer = { error("a direct workspace must never ask for a tunnel") }),
            WorkspaceShellTools(ToolWrapperInstaller(), EnvScriptWriter(DirectSshRoute(22))),
            workspace,
        ).ensureTunnel()

        assertThat(System.getProperty(Constants.Proxy.PORT_PROPERTY)).isNull()
        assertThat(envFile.read().networkMode).isEqualTo(NetworkMode.Direct)
        assertThat(namespacesThroughFactory()).contains("default")
    }

    @Test
    fun `a proxy port left published by an earlier command is not used`() {
        // A port nothing listens on: a client sent through it could not reach the API.
        val deadPort = ServerSocket(0).use { it.localPort }
        System.setProperty(Constants.Proxy.PORT_PROPERTY, deadPort.toString())

        assertThat(namespacesThroughFactory()).contains("default")
    }

    @Test
    fun `the workspace kubectl wrapper runs the real kubectl with no tunnel`() {
        WorkspaceShellTools(ToolWrapperInstaller(), EnvScriptWriter(DirectSshRoute(22))).prepare(workspace, stateManager.load())
        val builder =
            ProcessBuilder("kubectl", "get", "namespace", "default", "-o", "name")
                .directory(workspace)
                .redirectErrorStream(true)
        KitProcessEnvironment(ToolWrapperInstaller(), stateManager).applyTo(builder, workspace, emptyMap())
        // The caller's own proxy settings pass through untouched: kubectl reaches the API only if the
        // wrapper keeps this NO_PROXY, since the HTTPS_PROXY beside it leads nowhere.
        val deadPort = ServerSocket(0).use { it.localPort }
        builder.environment()["HTTPS_PROXY"] = "http://127.0.0.1:$deadPort"
        builder.environment()["NO_PROXY"] = "localhost,127.0.0.1,${apiHost()}"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertThat(process.waitFor()).withFailMessage(output).isZero()
        assertThat(output.trim()).isEqualTo("namespace/default")
    }

    /** The host of the API server the kubeconfig names. */
    private fun apiHost(): String = URI(Config.fromKubeconfig(kubeconfig.readText()).masterUrl).host

    private fun namespacesThroughFactory(): List<String> {
        val api = KubeApi(kubeconfig.toPath(), requireNotNull(currentContextOf(kubeconfig.toPath())))
        return koin.koin.get<KubernetesClientFactory>().createClient(api).use { client ->
            assertThat(client.configuration.httpsProxy).isNull()
            client
                .namespaces()
                .list()
                .items
                .map { it.metadata.name }
        }
    }
}
