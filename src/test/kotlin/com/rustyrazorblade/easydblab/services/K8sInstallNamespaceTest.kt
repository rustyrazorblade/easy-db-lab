package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NamespaceList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Path

/**
 * `up`'s namespace step fails naming the namespace when the API server refuses it, keeping the
 * Fabric8 exception as the cause so the Kubernetes error handler can still describe a 403.
 */
class K8sInstallNamespaceTest {
    private val client: KubernetesClient = mock()
    private val namespaces: NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> = mock()
    private val named: Resource<Namespace> = mock()
    private val provider =
        K8sClientProvider(
            { KubeApi(Path.of("unused-kubeconfig"), "unused-context") },
            object : KubernetesClientFactory {
                override fun createClient(api: KubeApi): KubernetesClient = client
            },
        )
    private val scope = InstallScope(K8sNamespace("lab-a"), "id-1")

    @Test
    fun `a forbidden namespace read fails up naming the namespace, keeping the 403`() {
        val denied = KubernetesClientException("namespaces \"lab-a\" is forbidden", Constants.HttpStatus.FORBIDDEN, null)
        whenever(client.namespaces()).thenReturn(namespaces)
        whenever(namespaces.withName("lab-a")).thenReturn(named)
        whenever(named.get()).thenThrow(denied)

        val thrown = catchThrowable { K8sInstallNamespace(provider, { scope }, EventBus()).ensure() }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'lab-a'")
            .hasMessageContaining("is forbidden")
            .hasCause(denied)
    }
}
