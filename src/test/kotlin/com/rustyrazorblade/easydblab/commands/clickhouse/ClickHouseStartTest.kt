package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.ClickHouseConfigService
import com.rustyrazorblade.easydblab.services.K8sService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ClickHouseStartTest : BaseKoinTest() {
    private lateinit var mockK8sService: K8sService
    private lateinit var mockClickHouseConfigService: ClickHouseConfigService
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testControlHost =
        ClusterHost(
            publicIp = "54.1.2.5",
            privateIp = "10.0.1.3",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control0",
        )

    private fun makeDbHosts(count: Int): List<ClusterHost> =
        (0 until count).map { i ->
            ClusterHost(
                publicIp = "54.1.2.${10 + i}",
                privateIp = "10.0.1.${10 + i}",
                alias = "db$i",
                availabilityZone = "us-west-2a",
                instanceId = "i-db$i",
            )
        }

    private fun makeClusterState(
        dbCount: Int = 3,
        s3Bucket: String? = "test-bucket",
    ): ClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = s3Bucket,
            initConfig = InitConfig(region = "us-west-2"),
            hosts =
                mapOf(
                    ServerType.Control to listOf(testControlHost),
                    ServerType.Cassandra to makeDbHosts(dbCount),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mockK8sService }
                single<ClickHouseConfigService> { mockClickHouseConfigService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockK8sService = mock()
        mockClickHouseConfigService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        // Default: 3 db nodes with S3 bucket
        whenever(mockClusterStateManager.load()).thenReturn(makeClusterState())

        // Default successful K8s operations
        whenever(mockK8sService.createLocalPersistentVolumes(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.createClickHouseS3Secret(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.applyManifests(any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.createConfigMap(any(), any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.scaleStatefulSet(any(), any(), any(), any()))
            .thenReturn(Result.success(Unit))
        whenever(mockK8sService.waitForPodsReady(any(), any(), any()))
            .thenReturn(Result.success(Unit))

        whenever(mockClickHouseConfigService.createDynamicConfigMap(any(), any(), any()))
            .thenReturn(mapOf("config.xml" to "<clickhouse/>"))
    }

    @Nested
    inner class Validation {
        @Test
        fun `execute fails when no control nodes`() {
            val state =
                ClusterState(
                    name = "test",
                    versions = mutableMapOf(),
                    hosts = mapOf(ServerType.Cassandra to makeDbHosts(3)),
                )
            whenever(mockClusterStateManager.load()).thenReturn(state)

            val command = ClickHouseStart()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No control nodes found")
        }

        @Test
        fun `execute fails when no db nodes`() {
            val state =
                ClusterState(
                    name = "test",
                    versions = mutableMapOf(),
                    hosts = mapOf(ServerType.Control to listOf(testControlHost)),
                )
            whenever(mockClusterStateManager.load()).thenReturn(state)

            val command = ClickHouseStart()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("No db nodes found")
        }

        @Test
        fun `execute fails when too few db nodes`() {
            whenever(mockClusterStateManager.load()).thenReturn(makeClusterState(dbCount = 2))

            val command = ClickHouseStart()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("at least")
        }

        @Test
        fun `execute fails when replicas not divisible by replicas-per-shard`() {
            whenever(mockClusterStateManager.load()).thenReturn(makeClusterState(dbCount = 4))

            val command = ClickHouseStart()
            command.replicasPerShard = 3 // 4 not divisible by 3

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("divisible")
        }
    }

    @Nested
    inner class SuccessfulDeployment {
        @Test
        fun `execute creates PVs, applies manifests, and scales`() {
            val command = ClickHouseStart()
            command.execute()

            verify(mockK8sService).createLocalPersistentVolumes(
                any(),
                eq("clickhouse"),
                any(),
                eq(3),
                any(),
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                any(),
            )
            verify(mockK8sService).applyManifests(any(), any())
            verify(mockK8sService).scaleStatefulSet(
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                eq("clickhouse"),
                eq(3),
            )
        }

        @Test
        fun `execute creates S3 secret when bucket configured`() {
            val command = ClickHouseStart()
            command.execute()

            verify(mockK8sService).createClickHouseS3Secret(
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                any(),
                eq("test-bucket"),
            )
        }

        @Test
        fun `execute skips S3 when no bucket configured`() {
            whenever(mockClusterStateManager.load()).thenReturn(makeClusterState(s3Bucket = null))

            val command = ClickHouseStart()
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("S3 bucket not configured")
        }

        @Test
        fun `execute outputs deployment summary`() {
            val command = ClickHouseStart()
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).contains("Deploying ClickHouse")
            assertThat(output).contains("deployed successfully")
        }

        @Test
        fun `execute creates cluster config and dynamic config`() {
            val command = ClickHouseStart()
            command.execute()

            verify(mockK8sService).createConfigMap(
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                eq("clickhouse-cluster-config"),
                any(),
                any(),
            )
            verify(mockK8sService).createConfigMap(
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                eq("clickhouse-server-config"),
                any(),
                any(),
            )
        }

        @Test
        fun `execute waits for pods by default`() {
            val command = ClickHouseStart()
            command.execute()

            verify(mockK8sService).waitForPodsReady(any(), any(), eq(Constants.ClickHouse.NAMESPACE))
        }
    }

    @Nested
    inner class Options {
        @Test
        fun `execute skips wait when skip-wait is set`() {
            val command = ClickHouseStart()
            command.skipWait = true
            command.execute()

            val output = outputHandler.messages.joinToString("\n")
            assertThat(output).doesNotContain("Waiting for ClickHouse pods")
        }

        @Test
        fun `execute uses custom replica count`() {
            whenever(mockClusterStateManager.load()).thenReturn(makeClusterState(dbCount = 6))

            val command = ClickHouseStart()
            command.replicas = 6
            command.replicasPerShard = 3
            command.execute()

            verify(mockK8sService).scaleStatefulSet(
                any(),
                eq(Constants.ClickHouse.NAMESPACE),
                eq("clickhouse"),
                eq(6),
            )
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `execute fails when PV creation fails`() {
            whenever(mockK8sService.createLocalPersistentVolumes(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Result.failure(RuntimeException("PV creation failed")))

            val command = ClickHouseStart()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Failed to create Local PVs")
        }

        @Test
        fun `execute fails when manifest application fails`() {
            whenever(mockK8sService.applyManifests(any(), any()))
                .thenReturn(Result.failure(RuntimeException("K8s error")))

            val command = ClickHouseStart()

            assertThatThrownBy { command.execute() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Failed to apply ClickHouse manifests")
        }
    }
}
