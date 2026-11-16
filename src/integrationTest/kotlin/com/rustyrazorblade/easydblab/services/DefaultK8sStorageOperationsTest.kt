package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@EnableKubernetesMockClient(crud = true)
class DefaultK8sStorageOperationsTest {
    private lateinit var storageOps: DefaultK8sStorageOperations
    private lateinit var mockEventBus: EventBus
    private lateinit var mockClientProvider: K8sClientProvider

    lateinit var server: KubernetesMockServer

    @BeforeEach
    fun setup() {
        mockEventBus = mock()
        mockClientProvider = mock()
        whenever(mockClientProvider.createClient()).thenAnswer { server.createClient() }
        storageOps = DefaultK8sStorageOperations(mockClientProvider, mockEventBus)
    }

    @Test
    fun `ensureKitLocalStorageClass creates a no-provisioner WaitForFirstConsumer Retain class, labelled`() {
        val result = storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")

        assertThat(result.isSuccess).isTrue()

        val sc =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .withName("edl-local-kafka")
                .get()

        assertThat(sc).isNotNull()
        assertThat(sc.volumeBindingMode).isEqualTo("WaitForFirstConsumer")
        assertThat(sc.reclaimPolicy).isEqualTo("Retain")
        assertThat(sc.provisioner).isEqualTo("kubernetes.io/no-provisioner")
        assertThat(sc.metadata.labels)
            .containsEntry(Constants.K8s.MANAGED_BY_LABEL, Constants.K8s.MANAGED_BY_VALUE)
            .containsEntry(Constants.K8s.CLUSTER_LABEL, "cid-1")
    }

    @Test
    fun `ensureKitLocalStorageClass is idempotent when the class already exists`() {
        storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")
        val result = storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")

        assertThat(result.isSuccess).isTrue()
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .filter { it.metadata.name == "edl-local-kafka" }
        assertThat(classes).hasSize(1)
    }

    @Test
    fun `deleteKitLocalStorageClass removes only that kit's class and tolerates a missing one`() {
        storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")
        storageOps.ensureKitLocalStorageClass("edl-local-tidb", "cid-1")

        assertThat(storageOps.deleteKitLocalStorageClass("edl-local-kafka").isSuccess).isTrue()
        assertThat(storageOps.deleteKitLocalStorageClass("edl-local-kafka").isSuccess).isTrue()

        val names =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .map { it.metadata.name }
        assertThat(names).contains("edl-local-tidb").doesNotContain("edl-local-kafka")
    }

    @Test
    fun `defaultStorageClass names the annotated class, or null when none is default`() {
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("plain")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()
        assertThat(storageOps.defaultStorageClass().getOrThrow()).isNull()

        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("gp3")
                    .addToAnnotations("storageclass.kubernetes.io/is-default-class", "true")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()
        assertThat(storageOps.defaultStorageClass().getOrThrow()).isEqualTo("gp3")
    }

    @Test
    fun `a StorageClass's reclaim policy is read, a missing one reads as Delete, and an absent class as null`() {
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName(
                        "kept",
                    ).endMetadata()
                    .withProvisioner("p")
                    .withReclaimPolicy("Retain")
                    .build(),
            ).create()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("defaulted")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()

        assertThat(storageOps.storageClassReclaimPolicy("kept").getOrThrow()).isEqualTo("Retain")
        assertThat(storageOps.storageClassReclaimPolicy("defaulted").getOrThrow()).isEqualTo("Delete")
        assertThat(storageOps.storageClassReclaimPolicy("absent").getOrThrow()).isNull()
    }
}
