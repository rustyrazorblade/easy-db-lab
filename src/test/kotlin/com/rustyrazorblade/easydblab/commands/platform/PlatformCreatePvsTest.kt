package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.PersistentVolumeConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlatformCreatePvsTest : BaseKoinTest() {
    private val mockK8sService: K8sService = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()

    private val controlHost =
        ClusterHost(
            publicIp = "54.0.0.1",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private val dbHosts =
        listOf(
            ClusterHost(
                publicIp = "54.0.0.2",
                privateIp = "10.0.0.2",
                alias = "db0",
                availabilityZone = "us-west-2a",
                instanceId = "i-db0",
            ),
            ClusterHost(
                publicIp = "54.0.0.3",
                privateIp = "10.0.0.3",
                alias = "db1",
                availabilityZone = "us-west-2b",
                instanceId = "i-db1",
            ),
            ClusterHost(
                publicIp = "54.0.0.4",
                privateIp = "10.0.0.4",
                alias = "db2",
                availabilityZone = "us-west-2c",
                instanceId = "i-db2",
            ),
        )

    private val appHosts =
        listOf(
            ClusterHost(
                publicIp = "54.0.0.5",
                privateIp = "10.0.0.5",
                alias = "app0",
                availabilityZone = "us-west-2a",
                instanceId = "i-app0",
            ),
            ClusterHost(
                publicIp = "54.0.0.6",
                privateIp = "10.0.0.6",
                alias = "app1",
                availabilityZone = "us-west-2b",
                instanceId = "i-app1",
            ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mockK8sService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupState() {
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mapOf(
                        ServerType.Control to listOf(controlHost),
                        ServerType.Cassandra to dbHosts,
                        ServerType.Stress to appHosts,
                    ),
            )
        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockK8sService.createLocalPersistentVolumes(any(), any())).thenReturn(Result.success(Unit))
    }

    @Test
    fun `creates PVs on db nodes using local-storage-wfc StorageClass`() {
        val command =
            PlatformCreatePvs().apply {
                kit = "clickhouse"
                size = "100Gi"
                nodeType = "db"
            }

        command.execute()

        val captor = argumentCaptor<PersistentVolumeConfig>()
        verify(mockK8sService).createLocalPersistentVolumes(any(), captor.capture())

        val config = captor.firstValue
        assertThat(config.storageClass).isEqualTo(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
        assertThat(config.dbName).isEqualTo("clickhouse")
        assertThat(config.localPath).isEqualTo("/mnt/db1/clickhouse")
        assertThat(config.storageSize).isEqualTo("100Gi")
        assertThat(config.count).isEqualTo(dbHosts.size)
    }

    @Test
    fun `creates PVs on app nodes when node-type is app`() {
        val command =
            PlatformCreatePvs().apply {
                kit = "presto"
                size = "200Gi"
                nodeType = "app"
            }

        command.execute()

        val captor = argumentCaptor<PersistentVolumeConfig>()
        verify(mockK8sService).createLocalPersistentVolumes(any(), captor.capture())

        val config = captor.firstValue
        assertThat(config.storageClass).isEqualTo(Constants.K8s.LOCAL_STORAGE_WFC_CLASS)
        assertThat(config.dbName).isEqualTo("presto")
        assertThat(config.localPath).isEqualTo("/mnt/db1/presto")
        assertThat(config.count).isEqualTo(appHosts.size)
    }

    @Test
    fun `passes control host to createLocalPersistentVolumes`() {
        val command =
            PlatformCreatePvs().apply {
                kit = "mydb"
                size = "50Gi"
                nodeType = "db"
            }

        command.execute()

        val hostCaptor = argumentCaptor<ClusterHost>()
        verify(mockK8sService).createLocalPersistentVolumes(hostCaptor.capture(), any())
        assertThat(hostCaptor.firstValue.alias).isEqualTo("control0")
    }

    @Test
    fun `rejects invalid node-type`() {
        val command =
            PlatformCreatePvs().apply {
                kit = "mydb"
                size = "50Gi"
                nodeType = "invalid"
            }

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid node-type")
    }

    @Test
    fun `fails when no control node is found`() {
        val stateWithoutControl =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mapOf(ServerType.Cassandra to dbHosts),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateWithoutControl)

        val command =
            PlatformCreatePvs().apply {
                kit = "mydb"
                size = "50Gi"
                nodeType = "db"
            }

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control node found")
    }
}
