package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.nio.file.Path

@EnableKubernetesMockClient(crud = true)
class DefaultK8sStorageOperationsTest {
    private lateinit var storageOps: DefaultK8sStorageOperations
    private lateinit var mockEventBus: EventBus
    private lateinit var clientProvider: K8sClientProvider

    lateinit var server: KubernetesMockServer

    @BeforeEach
    fun setup() {
        mockEventBus = mock()
        // The workspace kubeconfig is a placeholder: the mock API server is not reached through one.
        val mockServerFactory =
            object : KubernetesClientFactory {
                override fun createClient(kubeconfigPath: Path): KubernetesClient = server.createClient()
            }
        clientProvider = WorkspaceK8s.provider("placeholder", mockServerFactory)
        storageOps = DefaultK8sStorageOperations(clientProvider, mockEventBus)
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
}
