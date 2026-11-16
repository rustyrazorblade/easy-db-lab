package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * The `kit-storage` step reads the environment: on owned nodes it reserves the kit's own local
 * volumes, on a provisioned class it only checks that the class retains, and never creates or
 * deletes a volume there.
 */
class KitStorageProvisionerTest {
    private val k8sService: K8sService = mock()
    private val remoteOps: RemoteOperationsService = mock()
    private val provisioner = KitStorageProvisioner(k8sService, remoteOps)

    private fun host(alias: String) = ClusterHost("54.0.0.$alias", "10.0.0.$alias", alias, "us-west-2a", "i-$alias")

    private val state =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            clusterId = "cid-1",
            hosts =
                mapOf(
                    ServerType.Cassandra to listOf(host("db0"), host("db1")),
                    ServerType.Stress to listOf(host("app0")),
                ),
        )

    private val local = KitStorage.LocalVolumes(Quantity("10Ti"))
    private val provisioned = KitStorage.Provisioned("retain-x", Quantity("100Gi"))

    private fun volumes(
        claimTemplate: String = "data",
        nodeType: String = "db",
        count: Int? = null,
    ) = KitVolumes(kit = "tidb", nodeType = nodeType, volumeClaimTemplateName = claimTemplate, size = "10Ti", count = count)

    @BeforeEach
    fun stubs() {
        whenever(k8sService.ensureKitLocalStorageClass(any(), any())).thenReturn(Result.success(Unit))
        whenever(k8sService.deleteKitLocalStorageClass(any())).thenReturn(Result.success(Unit))
        whenever(k8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        whenever(k8sService.deleteLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
    }

    @Nested
    inner class OnLocalVolumes {
        @Test
        fun `the kit's own class is ensured, labelled with the cluster`() {
            provisioner.provision(volumes(), local, state)

            verify(k8sService).ensureKitLocalStorageClass("edl-local-tidb", "cid-1")
        }

        @Test
        fun `each node of the pool gets the claim template's directory under the kit's`() {
            provisioner.provision(volumes(claimTemplate = "tikv"), local, state)

            val hosts = argumentCaptor<Host>()
            verify(remoteOps, org.mockito.kotlin.times(2))
                .executeRemotely(hosts.capture(), eq("sudo mkdir -p /mnt/db1/tidb/tikv"), any(), any())
            assertThat(hosts.allValues.map { it.alias }).containsExactly("db0", "db1")
        }

        @Test
        fun `the PVs are of the kit's class, one per node, at the claim template's path`() {
            provisioner.provision(volumes(claimTemplate = "tikv"), local, state)

            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(config.capture())
            assertThat(config.firstValue.storageClass).isEqualTo("edl-local-tidb")
            assertThat(config.firstValue.localPath).isEqualTo("/mnt/db1/tidb/tikv")
            assertThat(config.firstValue.count).isEqualTo(2)
            assertThat(config.firstValue.volumeClaimTemplateName).isEqualTo("tikv")
            assertThat(config.firstValue.nodeType).isEqualTo("db")
            assertThat(config.firstValue.storageSize).isEqualTo("10Ti")
        }

        @Test
        fun `two claim templates of one kit on the same nodes get distinct paths`() {
            provisioner.provision(volumes(claimTemplate = "tikv"), local, state)
            provisioner.provision(volumes(claimTemplate = "tiflash"), local, state)

            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService, org.mockito.kotlin.times(2)).createLocalPersistentVolumes(config.capture())
            assertThat(config.allValues.map { it.localPath }).containsExactly("/mnt/db1/tidb/tikv", "/mnt/db1/tidb/tiflash")
        }

        @Test
        fun `a count limits the PVs to that many nodes`() {
            provisioner.provision(volumes(nodeType = "app", count = 1), local, state)

            val config = argumentCaptor<PersistentVolumeConfig>()
            verify(k8sService).createLocalPersistentVolumes(config.capture())
            assertThat(config.firstValue.count).isEqualTo(1)
            assertThat(config.firstValue.nodeType).isEqualTo("app")
        }

        @Test
        fun `a pool with no nodes fails naming it`() {
            val noApp = state.copy(hosts = state.hosts - ServerType.Stress)

            assertThatThrownBy { provisioner.provision(volumes(nodeType = "app"), local, noApp) }
                .hasMessageContaining("No app nodes")
            verify(k8sService, never()).createLocalPersistentVolumes(any())
        }

        @Test
        fun `release deletes the kit's PVs, its class and its directory on the pool`() {
            provisioner.release("tidb", "db", local, state)

            verify(k8sService).deleteLocalPersistentVolumes("tidb")
            verify(k8sService).deleteKitLocalStorageClass("edl-local-tidb")
            val hosts = argumentCaptor<Host>()
            verify(remoteOps, org.mockito.kotlin.times(2))
                .executeRemotely(hosts.capture(), eq("sudo rm -rf /mnt/db1/tidb"), any(), any())
            assertThat(hosts.allValues.map { it.alias }).containsExactly("db0", "db1")
        }
    }

    @Nested
    inner class OnAProvisionedClass {
        @Test
        fun `a retained class is checked and nothing is created`() {
            whenever(k8sService.storageClassReclaimPolicy("retain-x")).thenReturn(Result.success("Retain"))

            provisioner.provision(volumes(), provisioned, state)

            verify(k8sService).storageClassReclaimPolicy("retain-x")
            verify(k8sService, never()).createLocalPersistentVolumes(any())
            verify(k8sService, never()).ensureKitLocalStorageClass(any(), any())
            verifyNoInteractions(remoteOps)
        }

        @Test
        fun `a class swapped for one that deletes fails the step before any claim exists`() {
            whenever(k8sService.storageClassReclaimPolicy("retain-x")).thenReturn(Result.success("Delete"))

            assertThatThrownBy { provisioner.provision(volumes(), provisioned, state) }
                .hasMessageContaining("StorageClass retain-x reclaims with Delete")
            verify(k8sService, never()).createLocalPersistentVolumes(any())
        }

        @Test
        fun `release deletes nothing`() {
            provisioner.release("tidb", "db", provisioned, state)

            verify(k8sService, never()).deleteLocalPersistentVolumes(any())
            verify(k8sService, never()).deleteKitLocalStorageClass(any())
            verifyNoInteractions(remoteOps)
        }
    }
}
