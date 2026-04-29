package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.services.ClickHouseBackupService
import com.rustyrazorblade.easydblab.services.K8sService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for ClickHouseStatus command following TDD principles.
 *
 * These tests verify ClickHouse cluster status retrieval including
 * namespace status queries and K8sService integration.
 */
class ClickHouseStatusTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClusterStateManager: ClusterStateManager

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testDbHost =
        ClusterHost(
            publicIp = "54.123.45.68",
            privateIp = "10.0.1.6",
            alias = "db0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test124",
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<K8sService>().also {
                        mockK8sService = it
                    }
                }
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single<ClickHouseBackupService> {
                    mock<ClickHouseBackupService>().also {
                        whenever(it.getBackupOperations(any())).thenReturn(Result.success(emptyList()))
                    }
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = getKoin().get()
        mockClusterStateManager = getKoin().get()
    }

    @Test
    fun `execute should fail when no control nodes exist`() {
        // Given - cluster state with no control nodes
        val emptyState =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts = mutableMapOf(),
            )

        whenever(mockClusterStateManager.load()).thenReturn(emptyState)

        val command = ClickHouseStatus()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No control nodes found")
    }

    @Test
    fun `execute should get namespace status successfully`() {
        // Given - cluster state with control node and db nodes
        val stateWithControlAndDb =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
            )

        val statusOutput =
            """
            NAME                              READY   STATUS    RESTARTS   AGE
            clickhouse-keeper-0               1/1     Running   0          5m
            clickhouse-keeper-1               1/1     Running   0          5m
            clickhouse-keeper-2               1/1     Running   0          5m
            clickhouse-server-0               1/1     Running   0          5m
            clickhouse-server-1               1/1     Running   0          5m
            """.trimIndent()

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControlAndDb)
        whenever(mockK8sService.getNamespaceStatus(any(), eq(Constants.ClickHouse.NAMESPACE)))
            .thenReturn(Result.success(statusOutput))

        val command = ClickHouseStatus()

        // When
        command.execute()

        // Then
        verify(mockK8sService).getNamespaceStatus(eq(testControlHost), eq(Constants.ClickHouse.NAMESPACE))
    }

    @Test
    fun `execute should fail when getNamespaceStatus fails`() {
        // Given - cluster state with control node and db nodes
        val stateWithControlAndDb =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControlAndDb)
        whenever(mockK8sService.getNamespaceStatus(any(), eq(Constants.ClickHouse.NAMESPACE)))
            .thenReturn(Result.failure(RuntimeException("Namespace not found")))

        val command = ClickHouseStatus()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Namespace not found")
    }

    @Test
    fun `execute should use correct ClickHouse namespace`() {
        // Given - cluster state with control node and db nodes
        val stateWithControlAndDb =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                        ServerType.Cassandra to listOf(testDbHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControlAndDb)
        whenever(mockK8sService.getNamespaceStatus(any(), eq("default")))
            .thenReturn(Result.success("Running"))

        val command = ClickHouseStatus()

        // When
        command.execute()

        // Then - verify the default namespace is used
        verify(mockK8sService).getNamespaceStatus(any(), eq("default"))
    }

    @Test
    fun `execute should fail when no db nodes exist`() {
        // Given - cluster state with control node but no db nodes
        val stateWithControlOnly =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                hosts =
                    mutableMapOf(
                        ServerType.Control to listOf(testControlHost),
                    ),
            )

        whenever(mockClusterStateManager.load()).thenReturn(stateWithControlOnly)
        whenever(mockK8sService.getNamespaceStatus(any(), eq(Constants.ClickHouse.NAMESPACE)))
            .thenReturn(Result.success("Running"))

        val command = ClickHouseStatus()

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No db nodes found")
    }
}
