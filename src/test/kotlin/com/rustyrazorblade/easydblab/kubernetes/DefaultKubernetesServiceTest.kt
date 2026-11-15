package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.nio.file.Path

/**
 * [DefaultKubernetesService] resolves the cluster's address afresh for every operation and closes
 * the client it built, so a long-lived holder such as the server's status cache sees a rewritten
 * kubeconfig, a newly pinned context or a new proxy port on its next call.
 */
class DefaultKubernetesServiceTest {
    private val kubeconfig = Path.of("/workspace/kubeconfig")
    private var pinned = KubeApi(kubeconfig, "first-context")
    private val requested = mutableListOf<KubeApi>()
    private val built = mutableListOf<KubernetesClient>()

    /** Records each address it is asked for and hands out a stub client that answers every call. */
    private val recordingFactory =
        object : KubernetesClientFactory {
            override fun createClient(api: KubeApi): KubernetesClient {
                requested += api
                return mock<KubernetesClient>(defaultAnswer = Answers.RETURNS_DEEP_STUBS).also { built += it }
            }
        }

    private val service = DefaultKubernetesService(recordingFactory) { pinned }

    @Test
    fun `a context pinned between two calls is used by the second call`() {
        service.listPods()
        pinned = KubeApi(kubeconfig, "second-context")
        service.listPods()

        assertThat(requested).containsExactly(
            KubeApi(kubeconfig, "first-context"),
            KubeApi(kubeconfig, "second-context"),
        )
    }

    @Test
    fun `every client built for a call is closed after it`() {
        service.listPods()
        service.isReachable()

        assertThat(built).hasSize(2)
        built.forEach { verify(it).close() }
    }
}
