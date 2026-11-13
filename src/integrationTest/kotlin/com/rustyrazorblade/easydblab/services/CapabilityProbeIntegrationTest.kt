package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.ProxiedKubernetesClientFactory
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [CapabilityProbeService] against a real API server: Pod Security admission, a ResourceQuota and
 * namespace creation are the API server's own behaviour, which no fake reproduces. Each test works in
 * a namespace of its own on the shared K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityProbeIntegrationTest {
    private companion object {
        const val ENFORCE_LABEL = "pod-security.kubernetes.io/enforce"
    }

    @TempDir
    lateinit var directory: File

    private lateinit var client: KubernetesClient
    private lateinit var api: KubeApi
    private val probe = CapabilityProbeService(ProxiedKubernetesClientFactory())

    @BeforeAll
    fun setup() {
        client = SharedK3s.client()
        val kubeconfig = File(directory, "kubeconfig").apply { writeText(SharedK3s.kubeConfigYaml()) }
        api = KubeApi(kubeconfig.toPath(), KubeConfigUtils.parseConfig(kubeconfig).currentContext)
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun createNamespace(
        name: String,
        labels: Map<String, String> = emptyMap(),
    ) {
        SharedK3s.createNamespace(name)
        if (labels.isNotEmpty()) {
            client.namespaces().withName(name).edit { it.apply { metadata.labels = metadata.labels.orEmpty() + labels } }
        }
    }

    @Test
    fun `a namespace enforcing baseline admits none of the three, and no Pod is left behind`() {
        createNamespace("probe-baseline", mapOf(ENFORCE_LABEL to "baseline"))

        val result = probe.probe(api, "probe-baseline")

        val admission = listOf(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH)
        assertThat(admission).noneMatch { it in result.capabilities }
        assertThat(result.notes.keys).containsExactlyInAnyOrderElementsOf(admission)
        assertThat(result.notes.values).allSatisfy { assertThat(it).contains("PodSecurity") }
        assertThat(
            client
                .pods()
                .inNamespace("probe-baseline")
                .list()
                .items,
        ).isEmpty()
    }

    @Test
    fun `an unlabelled namespace admits the three, and node SSH is never probed present`() {
        createNamespace("probe-open")

        val result = probe.probe(api, "probe-open")

        assertThat(result.capabilities.present())
            .containsExactly(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH)
        assertThat(NodeCapability.NODE_SSH in result.capabilities).isFalse()
        assertThat(result.notes).isEmpty()
    }

    @Test
    fun `a missing namespace is created and probed`() {
        val result = probe.probe(api, "probe-created")

        assertThat(client.namespaces().withName("probe-created").get()).isNotNull()
        assertThat(result.capabilities.present()).contains(NodeCapability.PRIVILEGED)
    }

    @Test
    fun `a namespace that admits no Pod fails the probe, naming the namespace and the reason`() {
        createNamespace("probe-closed")
        client
            .resource(
                ResourceQuotaBuilder()
                    .withNewMetadata()
                    .withName("no-pods")
                    .withNamespace("probe-closed")
                    .endMetadata()
                    .withNewSpec()
                    .addToHard("pods", Quantity("0"))
                    .endSpec()
                    .build(),
            ).create()

        assertThatThrownBy { probe.probe(api, "probe-closed") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("probe-closed")
            .hasMessageContaining("baseline")
            .hasMessageContaining("exceeded quota")
    }
}
