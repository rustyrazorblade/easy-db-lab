package com.rustyrazorblade.easydblab.services.tenancy

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [NamespaceEnsurer] against a real API server: an absent namespace is created labelled, and an
 * existing unlabelled one, `default` included, is adopted with its own labels kept (design D11).
 * Each test uses a namespace of its own on the shared K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamespaceEnsurerIntegrationTest {
    private lateinit var client: KubernetesClient

    @BeforeAll
    fun setup() {
        client = SharedK3s.client()
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun scope(namespace: String) = InstallScope(K8sNamespace(namespace), "ensurer-cluster")

    private fun labelsOf(namespace: String): Map<String, String> =
        client
            .namespaces()
            .withName(namespace)
            .get()
            .metadata.labels
            .orEmpty()

    @Test
    fun `an absent namespace is created carrying both ownership labels`() {
        val plan = NamespaceEnsurer.ensure(client, scope("ensurer-absent"))

        assertThat(plan).isInstanceOf(NamespacePlan.Create::class.java)
        assertThat(labelsOf("ensurer-absent")).containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
    }

    @Test
    fun `an existing unlabelled namespace is adopted, keeping its own labels`() {
        SharedK3s.createNamespace("ensurer-existing")
        client.namespaces().withName("ensurer-existing").edit {
            it.apply { metadata.labels = metadata.labels.orEmpty() + ("team" to "storage") }
        }

        val plan = NamespaceEnsurer.ensure(client, scope("ensurer-existing"))

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        assertThat(labelsOf("ensurer-existing"))
            .containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
            .containsEntry("team", "storage")
        assertThat(NamespaceEnsurer.ensure(client, scope("ensurer-existing"))).isInstanceOf(NamespacePlan.Unchanged::class.java)
    }

    @Test
    fun `the default namespace is adopted, not refused`() {
        val plan = NamespaceEnsurer.ensure(client, InstallScope(K8sNamespace("default"), "ensurer-default"))

        assertThat(plan).isNotInstanceOf(NamespacePlan.Create::class.java)
        assertThat(labelsOf("default")).containsAllEntriesOf(OwnershipLabels.of("ensurer-default"))
    }
}
