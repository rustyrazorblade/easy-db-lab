package com.rustyrazorblade.easydblab.services.tenancy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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

    @Test
    fun `an adopt that overwrites another cluster's id names the label, its old value and its new one`() {
        val plan = NamespaceEnsurer.plan(labelled(OwnershipLabels.of("old-id") + ("team" to "storage")), scope)

        assertThat((plan as NamespacePlan.Adopt).replaced)
            .containsExactly(ReplacedLabel(Constants.K8s.CLUSTER_LABEL, "old-id", "id-1"))
    }

    @Test
    fun `an adopt of an unlabelled namespace replaces nothing`() {
        val plan = NamespaceEnsurer.plan(labelled(mapOf("team" to "storage")), scope)

        assertThat((plan as NamespacePlan.Adopt).replaced).isEmpty()
    }

    private fun labelled(labels: Map<String, String>) =
        NamespaceBuilder()
            .withNewMetadata()
            .withName("lab-a")
            .addToLabels(labels)
            .endMetadata()
            .build()
}
