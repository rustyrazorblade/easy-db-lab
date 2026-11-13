package com.rustyrazorblade.easydblab.services.access

import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ServicePortTest {
    private val nodePortService: Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName("clickhouse-nodeport")
            .withNamespace("default")
            .endMetadata()
            .withNewSpec()
            .addNewPort()
            .withName("http")
            .withPort(8123)
            .withNodePort(30123)
            .endPort()
            .addNewPort()
            .withName("native")
            .withPort(9000)
            .withNodePort(30900)
            .endPort()
            .endSpec()
            .build()

    @Test
    fun `a kit endpoint's NodePort maps to the Service port behind it`() {
        assertThat(servicePortFor(nodePortService, 30900)).isEqualTo(9000)
    }

    @Test
    fun `a Service port is used as it is`() {
        assertThat(servicePortFor(nodePortService, 8123)).isEqualTo(8123)
    }

    @Test
    fun `a port the Service does not expose fails, naming the Service and the ports it has`() {
        assertThatThrownBy { servicePortFor(nodePortService, 5432) }
            .hasMessageContaining("default/clickhouse-nodeport")
            .hasMessageContaining("5432")
            .hasMessageContaining("8123")
    }
}
