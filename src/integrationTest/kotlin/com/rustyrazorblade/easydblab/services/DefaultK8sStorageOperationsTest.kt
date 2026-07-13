package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.EventBus
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

    private val controlHost =
        ClusterHost(
            publicIp = "1.2.3.4",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test",
        )

    lateinit var server: KubernetesMockServer

    @BeforeEach
    fun setup() {
        mockEventBus = mock()
        mockClientProvider = mock()
        whenever(mockClientProvider.createClient(controlHost)).thenAnswer { server.createClient() }
        storageOps = DefaultK8sStorageOperations(mockClientProvider, mockEventBus)
    }

    @Test
    fun `ensureLocalStorageWfcClass creates StorageClass with WaitForFirstConsumer and Delete`() {
        val result = storageOps.ensureLocalStorageWfcClass(controlHost)

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
        storageOps.ensureLocalStorageWfcClass(controlHost)
        val result = storageOps.ensureLocalStorageWfcClass(controlHost)

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
        val result = storageOps.ensureLocalStorageClass(controlHost)

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
        storageOps.ensureLocalStorageClass(controlHost)
        storageOps.ensureLocalStorageWfcClass(controlHost)

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
