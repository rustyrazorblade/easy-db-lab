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
    fun `ensureLocalStorageWfcClass creates StorageClass with WaitForFirstConsumer and Delete`() {
        val result = storageOps.ensureLocalStorageWfcClass()

        assertThat(result.isSuccess).isTrue()

        val client = server.createClient()
        val sc =
            client
                .storage()
                .v1()
                .storageClasses()
                .withName(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
                .get()

        assertThat(sc).isNotNull()
        assertThat(sc.volumeBindingMode).isEqualTo("WaitForFirstConsumer")
        assertThat(sc.reclaimPolicy).isEqualTo("Delete")
        assertThat(sc.provisioner).isEqualTo("kubernetes.io/no-provisioner")
    }

    @Test
    fun `ensureLocalStorageWfcClass is idempotent when StorageClass already exists`() {
        storageOps.ensureLocalStorageWfcClass()
        val result = storageOps.ensureLocalStorageWfcClass()

        assertThat(result.isSuccess).isTrue()

        val client = server.createClient()
        val classes =
            client
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .filter { it.metadata.name == Constants.K8s.LOCAL_STORAGE_WFC_CLASS }

        assertThat(classes).hasSize(1)
    }

    @Test
    fun `ensureLocalStorageClass creates StorageClass with Immediate and Retain`() {
        val result = storageOps.ensureLocalStorageClass()

        assertThat(result.isSuccess).isTrue()

        val client = server.createClient()
        val sc =
            client
                .storage()
                .v1()
                .storageClasses()
                .withName(Constants.K8s.LOCAL_STORAGE_CLASS)
                .get()

        assertThat(sc).isNotNull()
        assertThat(sc.volumeBindingMode).isEqualTo("Immediate")
        assertThat(sc.reclaimPolicy).isEqualTo("Retain")
    }

    @Test
    fun `both StorageClasses can coexist`() {
        storageOps.ensureLocalStorageClass()
        storageOps.ensureLocalStorageWfcClass()

        val client = server.createClient()
        val names =
            client
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .map { it.metadata.name }

        assertThat(names).contains(Constants.K8s.LOCAL_STORAGE_CLASS, Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
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
