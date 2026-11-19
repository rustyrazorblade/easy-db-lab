package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [Fabric8ControlPlaneConnector.open] opens three clients, one each for the reader, the sampler and
 * the node watcher. When the third cannot be opened, the first two must not be left open, and the
 * caller must see the failure that stopped the open.
 */
class Fabric8ControlPlaneConnectorTest {
    @Test
    fun `a failed third open closes the first two clients and reports the failure`() {
        val first: KubernetesClient = mock()
        val second: KubernetesClient = mock()
        val unreachable = IllegalStateException("kubeconfig unreadable")
        val provider: K8sClientProvider = mock()
        whenever(provider.createClient()).thenReturn(first, second).thenThrow(unreachable)

        assertThatThrownBy { Fabric8ControlPlaneConnector(provider).open(CniMode.Flannel) }.isSameAs(unreachable)

        verify(first).close()
        verify(second).close()
    }
}
