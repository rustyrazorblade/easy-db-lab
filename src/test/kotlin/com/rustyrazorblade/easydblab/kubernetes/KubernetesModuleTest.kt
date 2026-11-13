package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.WorkspaceKubeApiLocator
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.mock
import java.io.File

/**
 * The `KubernetesService` Koin wiring reaches the cluster through the workspace's [KubeApiLocator],
 * so `kit status`, the kit workload probe and the server's pod listing target the context pinned in
 * cluster state, not the kubeconfig's current-context.
 */
class KubernetesModuleTest : BaseKoinTest() {
    private val requested = mutableListOf<KubeApi>()

    /** Records the address it is asked for; the client it returns is never used for real I/O. */
    private val recordingFactory =
        object : KubernetesClientFactory {
            override fun createClient(api: KubeApi): KubernetesClient {
                requested += api
                return mock()
            }
        }

    private val workspace get() = context.workingDirectory

    override fun additionalTestModules(): List<Module> =
        listOf(
            kubernetesModule,
            module {
                single<KubernetesClientFactory> { recordingFactory }
                single { ClusterStateManager(File(workspace, "state.json")) }
                single<KubeApiLocator> { WorkspaceKubeApiLocator(get(), workspace) }
            },
        )

    @Test
    fun `KubernetesService targets the recorded context, not the kubeconfig's current-context`() {
        val kubeconfig = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)
        kubeconfig.writeText(TWO_CONTEXT_KUBECONFIG)
        get<ClusterStateManager>().save(
            ClusterState(name = "test", versions = mutableMapOf()).apply { kubeContext = "pinned-context" },
        )

        get<KubernetesService>().listPods()

        val api = requested.single()
        assertThat(api).isEqualTo(KubeApi(kubeconfig.toPath(), "pinned-context"))
        ProxiedKubernetesClientFactory().createClient(api).use { client ->
            assertThat(client.configuration.masterUrl).startsWith("https://pinned.example:6443")
        }
    }

    private companion object {
        val TWO_CONTEXT_KUBECONFIG =
            """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://current.example:6443
                insecure-skip-tls-verify: true
              name: current
            - cluster:
                server: https://pinned.example:6443
                insecure-skip-tls-verify: true
              name: pinned
            contexts:
            - context:
                cluster: current
                user: admin
              name: current-context
            - context:
                cluster: pinned
                user: admin
              name: pinned-context
            current-context: current-context
            kind: Config
            users:
            - name: admin
              user:
                token: t
            """.trimIndent()
    }
}
