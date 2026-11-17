package com.rustyrazorblade.easydblab

import io.fabric8.kubernetes.api.model.NamespaceBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Proves that [SharedK3s.ensurePodAdmission] makes a namespace ready for pods without waiting on the
 * kube-controller-manager, whose ServiceAccount and root-CA loops lag by over a minute on a loaded
 * shared cluster.
 */
class SharedK3sIntegrationTest {
    @Test
    fun `a created namespace has its default ServiceAccount and root CA as soon as createNamespace returns`() {
        SharedK3s.createNamespace("shared-k3s-ready")

        SharedK3s.client().use { client ->
            assertThat(
                client
                    .serviceAccounts()
                    .inNamespace("shared-k3s-ready")
                    .withName("default")
                    .get(),
            ).isNotNull
            assertThat(
                client
                    .configMaps()
                    .inNamespace("shared-k3s-ready")
                    .withName(SharedK3s.ROOT_CA_CONFIG_MAP)
                    .get()
                    ?.data,
            ).containsKey(SharedK3s.ROOT_CA_KEY)
        }
    }

    @Test
    fun `ensuring pod admission twice succeeds, as when the controller has already created the account`() {
        SharedK3s.client().use { client ->
            client
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName("shared-k3s-twice")
                        .endMetadata()
                        .build(),
                ).create()

            SharedK3s.ensurePodAdmission(client, "shared-k3s-twice")
            assertThatCode { SharedK3s.ensurePodAdmission(client, "shared-k3s-twice") }.doesNotThrowAnyException()
        }
    }
}
