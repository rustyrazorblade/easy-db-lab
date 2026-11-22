package com.rustyrazorblade.easydblab.services.tenancy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NamespaceList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.function.UnaryOperator

/**
 * The namespace `up` installs into is created labelled when absent, and adopted when present: the
 * user named it, so its own labels are kept and the cluster's are added (design D11).
 */
class NamespaceEnsurerTest {
    private val scope = InstallScope(K8sNamespace("lab-a"), "id-1")

    @Test
    fun `an absent namespace is planned as a new one carrying both labels`() {
        val plan = NamespaceEnsurer.plan(existing = null, scope = scope)

        assertThat(plan).isInstanceOf(NamespacePlan.Create::class.java)
        assertThat(plan.namespace.metadata.name).isEqualTo("lab-a")
        assertThat(plan.namespace.metadata.labels).isEqualTo(OwnershipLabels.of("id-1"))
    }

    @Test
    fun `an existing namespace is adopted, keeping its own labels and adding the cluster's`() {
        val existing =
            NamespaceBuilder()
                .withNewMetadata()
                .withName("lab-a")
                .addToLabels("team", "storage")
                .endMetadata()
                .build()

        val plan = NamespaceEnsurer.plan(existing, scope)

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        assertThat(plan.namespace.metadata.labels)
            .containsAllEntriesOf(OwnershipLabels.of("id-1"))
            .containsEntry("team", "storage")
        assertThat(existing.metadata.labels).containsOnlyKeys("team")
    }

    @Test
    fun `an existing namespace that already carries the labels needs no change`() {
        val existing =
            NamespaceBuilder()
                .withNewMetadata()
                .withName("lab-a")
                .addToLabels(OwnershipLabels.of("id-1"))
                .endMetadata()
                .build()

        assertThat(NamespaceEnsurer.plan(existing, scope)).isInstanceOf(NamespacePlan.Unchanged::class.java)
    }

    @Test
    fun `a namespace labelled by an earlier init of the same cluster is adopted with the new cluster id`() {
        val existing = labelled(OwnershipLabels.of("old-id") + ("team" to "storage"))

        val plan = NamespaceEnsurer.plan(existing, scope)

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        assertThat(plan.namespace.metadata.labels)
            .containsAllEntriesOf(OwnershipLabels.of("id-1"))
            .containsEntry("team", "storage")
        assertThat(NamespaceEnsurer.plan(plan.namespace, scope)).isInstanceOf(NamespacePlan.Unchanged::class.java)
    }

    @Nested
    inner class Ensure {
        private val client: KubernetesClient = mock()
        private val namespaces: NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> = mock()
        private val named: Resource<Namespace> = mock()
        private val toCreate: Resource<Namespace> = mock()

        @BeforeEach
        fun stubs() {
            whenever(client.namespaces()).thenReturn(namespaces)
            whenever(namespaces.withName("lab-a")).thenReturn(named)
            whenever(namespaces.resource(any())).thenReturn(toCreate)
        }

        private fun conflict() = KubernetesClientException("namespaces \"lab-a\" already exists", Constants.HttpStatus.CONFLICT, null)

        @Test
        fun `the stale cluster id on an adopted namespace is overwritten on the live object, and the next up is unchanged`() {
            val stale = labelled(OwnershipLabels.of("old-id") + ("team" to "storage"))
            whenever(named.get()).thenReturn(stale)

            assertThat(NamespaceEnsurer.ensure(client, scope)).isInstanceOf(NamespacePlan.Adopt::class.java)

            val edit = argumentCaptor<UnaryOperator<Namespace>>()
            verify(named).edit(edit.capture())
            val edited = edit.firstValue.apply(stale)
            assertThat(edited.metadata.labels)
                .containsAllEntriesOf(OwnershipLabels.of("id-1"))
                .containsEntry("team", "storage")
            whenever(named.get()).thenReturn(edited)
            assertThat(NamespaceEnsurer.ensure(client, scope)).isInstanceOf(NamespacePlan.Unchanged::class.java)
        }

        @Test
        fun `a create that conflicts is planned once more from the namespace that won`() {
            whenever(named.get()).thenReturn(null, labelled(emptyMap()))
            whenever(toCreate.create()).thenThrow(conflict())

            assertThat(NamespaceEnsurer.ensure(client, scope)).isInstanceOf(NamespacePlan.Adopt::class.java)
        }

        @Test
        fun `a create that conflicts again after the retry fails instead of retrying without end`() {
            whenever(named.get()).thenReturn(null)
            whenever(toCreate.create()).thenThrow(conflict())

            assertThatThrownBy { NamespaceEnsurer.ensure(client, scope) }
                .isInstanceOf(KubernetesClientException::class.java)
            verify(toCreate, times(2)).create()
        }
    }

    private fun labelled(labels: Map<String, String>) =
        NamespaceBuilder()
            .withNewMetadata()
            .withName("lab-a")
            .addToLabels(labels)
            .endMetadata()
            .build()
}
