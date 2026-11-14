package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.CassandraPodTarget
import com.rustyrazorblade.easydblab.services.CqlSessionService
import com.rustyrazorblade.easydblab.services.servicesModule
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock
import java.io.File

/**
 * The production wiring of the port-forward seam. It starts Koin with the real [servicesModule] and
 * [serviceAccessModule], not `BaseKoinTest`, so a forward is shown to build its client through the
 * workspace's recorded kubeconfig and pinned context — on a cluster the user brings, the
 * kubeconfig's current-context may name a different cluster.
 */
class ServiceAccessModuleTest {
    @TempDir
    lateinit var tempDir: File

    private val context by lazy { TestContextFactory.createTestContext(tempDir) }
    private val workspace get() = context.workingDirectory

    private val requested = mutableListOf<KubeApi>()

    /** Records the address it is asked for; the mock client it returns fails the forward itself. */
    private val recordingFactory =
        object : KubernetesClientFactory {
            override fun createClient(api: KubeApi): KubernetesClient {
                requested += api
                return mock()
            }
        }

    private val kubeconfig get() = File(workspace, Constants.K3s.LOCAL_KUBECONFIG)

    @Test
    fun `a Service port-forward reaches the cluster on the pinned context, not the kubeconfig's current-context`() {
        withPortForwardCluster { koin ->
            runCatching { koin.get<ServiceAccess>().open(ClusterServices.LOKI) }
        }

        assertThat(requested).containsExactly(KubeApi(kubeconfig.toPath(), "pinned-context"))
    }

    @Test
    fun `the CQL pod port-forward reaches the cluster on the pinned context, not the kubeconfig's current-context`() {
        File(workspace, CassandraPodTarget.KIT).mkdirs()

        withPortForwardCluster { koin -> koin.get<CqlSessionService>().execute("SELECT now() FROM system.local") }

        assertThat(requested).containsExactly(KubeApi(kubeconfig.toPath(), "pinned-context"))
    }

    private fun withPortForwardCluster(block: (Koin) -> Unit) {
        kubeconfig.writeText(TWO_CONTEXT_KUBECONFIG)
        val stateManager = ClusterStateManager(File(workspace, "state.json"))
        stateManager.save(
            ClusterState(name = "test", versions = mutableMapOf()).apply {
                exposure = Exposure.PortForward
                kubeContext = "pinned-context"
            },
        )
        val leaves =
            module {
                single<Context> { context }
                single { stateManager }
                single<KubernetesClientFactory> { recordingFactory }
                single<SocksProxyService> { mock() }
            }
        val app = koinApplication { modules(servicesModule, serviceAccessModule, leaves) }
        try {
            block(app.koin)
        } finally {
            app.close()
        }
    }

    private companion object {
        val TWO_CONTEXT_KUBECONFIG =
            """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://current.example:6443
              name: current
            - cluster:
                server: https://pinned.example:6443
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
