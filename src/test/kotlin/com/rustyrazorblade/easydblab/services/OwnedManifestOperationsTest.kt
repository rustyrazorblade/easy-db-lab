package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every object Kotlin applies carries the two ownership labels on its top-level metadata, and only
 * there: a label in the selector would force a delete and recreate, and one in the pod template would
 * roll every workload (design D6).
 */
class OwnedManifestOperationsTest {
    private val inner = RecordingManifests()
    private var scope = InstallScope(K8sNamespace("lab-a"), "id-1")
    private val owned = OwnedManifestOperations(inner) { scope }

    private val ownership =
        mapOf(
            Constants.K8s.MANAGED_BY_LABEL to Constants.K8s.MANAGED_BY_VALUE,
            Constants.K8s.CLUSTER_LABEL to "id-1",
        )

    private fun deployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName("mimir")
            .addToLabels("app.kubernetes.io/name", "mimir")
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", "mimir")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", "mimir")
            .endMetadata()
            .endTemplate()
            .endSpec()
            .build()

    @Test
    fun `a Deployment gets both labels on its metadata, keeping its own, with the selector and pod template untouched`() {
        val original = deployment()

        owned.applyResource(original).getOrThrow()

        val applied = inner.applied.single() as io.fabric8.kubernetes.api.model.apps.Deployment
        assertThat(applied.metadata.labels).containsAllEntriesOf(ownership).containsEntry("app.kubernetes.io/name", "mimir")
        assertThat(applied.spec.selector).isEqualTo(deployment().spec.selector)
        assertThat(applied.spec.template).isEqualTo(deployment().spec.template)
    }

    @Test
    fun `the caller's object is not changed`() {
        val original = deployment()

        owned.applyResource(original).getOrThrow()

        assertThat(original).isEqualTo(deployment())
    }

    @Test
    fun `an object with no labels and a cluster-scoped object are labelled too`() {
        owned
            .applyResource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("c")
                    .endMetadata()
                    .build(),
            ).getOrThrow()
        owned
            .applyResource(
                ClusterRoleBuilder()
                    .withNewMetadata()
                    .withName("r-id-1")
                    .endMetadata()
                    .build(),
            ).getOrThrow()

        assertThat(inner.applied.map { it.metadata.labels }).allSatisfy { assertThat(it).isEqualTo(ownership) }
    }

    @Test
    fun `the cluster id comes from the scope at the time of each apply`() {
        owned
            .applyResource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("c")
                    .endMetadata()
                    .build(),
            ).getOrThrow()
        scope = InstallScope(K8sNamespace("lab-a"), "id-2")
        owned
            .applyResource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("c")
                    .endMetadata()
                    .build(),
            ).getOrThrow()

        assertThat(inner.applied.map { it.metadata.labels[Constants.K8s.CLUSTER_LABEL] }).containsExactly("id-1", "id-2")
    }

    @Test
    fun `an object's own value for an ownership label is replaced by the cluster's`() {
        val foreign =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("c")
                .addToLabels(Constants.K8s.CLUSTER_LABEL, "someone-else")
                .endMetadata()
                .build()

        owned.applyResource(foreign).getOrThrow()

        assertThat(
            inner.applied
                .single()
                .metadata.labels,
        ).containsEntry(Constants.K8s.CLUSTER_LABEL, "id-1")
    }
}
