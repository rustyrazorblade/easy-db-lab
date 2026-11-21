package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [OwnedManifestOperations] over a real [DefaultK8sManifestOperations] on the shared K3s: the
 * ownership labels reach the live object's metadata, the selector and pod template the API server
 * stores are the ones the caller built, and applying the same Deployment again changes nothing the
 * Deployment controller acts on, so no workload rolls (design D6).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnedManifestOperationsIntegrationTest {
    private companion object {
        const val NAMESPACE = "owned-manifests"
        const val CLUSTER_ID = "owned-cluster"
        const val NAME_LABEL = "app.kubernetes.io/name"
    }

    private lateinit var client: KubernetesClient
    private lateinit var owned: OwnedManifestOperations

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val provider = WorkspaceK8s.provider(SharedK3s.kubeConfigYaml())
        owned =
            OwnedManifestOperations(DefaultK8sManifestOperations(provider, EventBus())) {
                InstallScope(K8sNamespace(NAMESPACE), CLUSTER_ID)
            }
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun deployment(): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName("owned")
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, "owned")
            .endMetadata()
            .withNewSpec()
            .withReplicas(0)
            .withNewSelector()
            .addToMatchLabels(NAME_LABEL, "owned")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(NAME_LABEL, "owned")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("main")
            .withImage("busybox:1.36")
            .withCommand("sleep", "3600")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private fun live(): Deployment =
        client
            .apps()
            .deployments()
            .inNamespace(NAMESPACE)
            .withName("owned")
            .get()

    @Test
    fun `the live Deployment carries the labels on its metadata only, and a re-apply does not roll it`() {
        owned.applyResource(deployment()).getOrThrow()
        val first = live()

        assertThat(first.metadata.labels)
            .containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
            .containsEntry(NAME_LABEL, "owned")
        assertThat(first.spec.selector.matchLabels).isEqualTo(mapOf(NAME_LABEL to "owned"))
        assertThat(first.spec.template.metadata.labels).isEqualTo(mapOf(NAME_LABEL to "owned"))

        owned.applyResource(deployment()).getOrThrow()
        val second = live()

        // The controller bumps the generation only when the spec, and so the pod template, changed.
        assertThat(second.metadata.generation).isEqualTo(first.metadata.generation)
        assertThat(second.spec.template).isEqualTo(first.spec.template)
    }
}
