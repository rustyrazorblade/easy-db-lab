package com.rustyrazorblade.easydblab.commands.platform

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.K8sService
import com.rustyrazorblade.easydblab.services.KitStorageProvisioner
import com.rustyrazorblade.easydblab.services.PersistentVolumeConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlatformCreatePvsTest : BaseKoinTest() {
    private val mockK8sService: K8sService = mock()
    private val mockClusterStateManager: ClusterStateManager = mock()
    private val events = mutableListOf<Event>()

    private fun emittedEvents(): List<Event> = events.toList()

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
                factory { KitStorageProvisioner(get(), get(), get()) }
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
        whenever(mockK8sService.createLocalPersistentVolumes(any())).thenReturn(Result.success(Unit))
        whenever(mockK8sService.ensureKitLocalStorageClass(any(), any())).thenReturn(Result.success(Unit))
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    @Test
    fun `creates PVs on db nodes of the kit's own StorageClass`() {
        val command =
            PlatformCreatePvs().apply {
                kit = "clickhouse"
                size = "100Gi"
                nodeType = "db"
            }

        command.execute()

        val captor = argumentCaptor<PersistentVolumeConfig>()
        verify(mockK8sService).createLocalPersistentVolumes(captor.capture())

        val config = captor.firstValue
        assertThat(config.storageClass).isEqualTo("edl-local-clickhouse")
        assertThat(config.dbName).isEqualTo("clickhouse")
        assertThat(config.localPath).isEqualTo("/mnt/db1/clickhouse/data")
        verify(mockK8sService).ensureKitLocalStorageClass(eq("edl-local-clickhouse"), any())
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
        verify(mockK8sService).createLocalPersistentVolumes(captor.capture())

        val config = captor.firstValue
        assertThat(config.storageClass).isEqualTo("edl-local-presto")
        assertThat(config.dbName).isEqualTo("presto")
        assertThat(config.localPath).isEqualTo("/mnt/db1/presto/data")
        assertThat(config.count).isEqualTo(appHosts.size)
    }

    @Test
    fun `on a provisioned class that retains it checks the class and creates nothing`() {
        useProvisionedClass("retain-x", reclaimPolicy = "Retain")
        val command =
            PlatformCreatePvs().apply {
                kit = "mydb"
                size = "50Gi"
            }

        command.execute()

        verify(mockK8sService).storageClassReclaimPolicy("retain-x")
        verify(mockK8sService, never()).createLocalPersistentVolumes(any())
        verify(mockK8sService, never()).ensureKitLocalStorageClass(any(), any())
        assertThat(emittedEvents())
            .noneMatch { it is Event.Platform.CreatingPvs || it is Event.Platform.PvsCreated }
            .contains(Event.Platform.KitStorageClassChecked(kit = "mydb", storageClass = "retain-x"))
    }

    @Test
    fun `on a provisioned class that deletes it refuses, naming the class`() {
        useProvisionedClass("delete-x", reclaimPolicy = "Delete")
        val command =
            PlatformCreatePvs().apply {
                kit = "mydb"
                size = "50Gi"
            }

        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("delete-x")
        verify(mockK8sService, never()).createLocalPersistentVolumes(any())
    }

    private fun useProvisionedClass(
        storageClass: String,
        reclaimPolicy: String,
    ) {
        val state = mockClusterStateManager.load()
        whenever(mockClusterStateManager.load()).thenReturn(state.copy(initConfig = InitConfig(kitStorageClass = storageClass)))
        whenever(mockK8sService.storageClassReclaimPolicy(storageClass)).thenReturn(Result.success(reclaimPolicy))
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
