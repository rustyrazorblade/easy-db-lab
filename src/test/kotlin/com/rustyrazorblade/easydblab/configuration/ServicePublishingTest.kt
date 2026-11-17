package com.rustyrazorblade.easydblab.configuration

import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [ServicePublishing.publish] must never leave a port of a NodePort Service without a fixed node
 * port: Kubernetes would give it a random one in 30000-32767, publishing an internal port and
 * possibly taking a port a kit claims later.
 */
class ServicePublishingTest {
    private val service: Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName("backend")
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(8080)
            .endPort()
            .addNewPort()
            .withName("grpc")
            .withPort(9090)
            .endPort()
            .endSpec()
            .build()

    @Test
    fun `on owned nodes only the mapped ports are listed, each on its fixed node port`() {
        val published = ServicePublishing.NodePorts.publish(service, mapOf("http" to 31080))

        assertThat(published.spec.type).isEqualTo("NodePort")
        assertThat(published.spec.ports.associate { it.name to it.nodePort }).containsExactlyEntriesOf(mapOf("http" to 31080))
    }

    @Test
    fun `a node port for a port the Service does not have is refused`() {
        assertThatThrownBy { ServicePublishing.NodePorts.publish(service, mapOf("htpp" to 31080)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("htpp")
            .hasMessageContaining("backend")
    }

    @Test
    fun `off owned nodes the Service keeps every port and no node port`() {
        val published = ServicePublishing.ClusterIp.publish(service, mapOf("http" to 31080))

        assertThat(published.spec.type).isEqualTo("ClusterIP")
        assertThat(published.spec.ports.map { it.name }).containsExactly("http", "grpc")
        assertThat(published.spec.ports.mapNotNull { it.nodePort }).isEmpty()
    }
}
