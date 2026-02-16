package com.rustyrazorblade.easydblab.commands.logs

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class LogsLsTest : BaseKoinTest() {
    private lateinit var mockObjectStore: ObjectStore
    private lateinit var mockClusterStateManager: ClusterStateManager
    private lateinit var outputHandler: BufferedOutputHandler

    private val testControlHost =
        ClusterHost(
            publicIp = "54.123.45.67",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-test123",
        )

    private val testClusterState =
        ClusterState(
            name = "test-cluster",
            versions = mutableMapOf(),
            s3Bucket = "easy-db-lab-test-bucket",
            clusterId = "test-id",
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Control to listOf(testControlHost)),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ObjectStore> { mockObjectStore }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockObjectStore = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        File(tempDir, "state.json").writeText(
            """
            {
                "name": "test-cluster",
                "versions": {},
                "s3Bucket": "easy-db-lab-test-bucket",
                "clusterId": "test-id",
                "hosts": {
                    "Control": [
                        {
                            "publicIp": "54.123.45.67",
                            "privateIp": "10.0.1.5",
                            "alias": "control0",
                            "availabilityZone": "us-west-2a",
                            "instanceId": "i-test123"
                        }
                    ]
                },
                "initConfig": { "region": "us-west-2" }
            }
            """.trimIndent(),
        )

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `displays empty message when no backups found`() {
        whenever(mockObjectStore.listFiles(any(), eq(true))).thenReturn(emptyList())

        val command = LogsLs()
        command.call()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("No VictoriaLogs backups found")
    }

    @Test
    fun `groups files by timestamp directory`() {
        val basePath =
            ClusterS3Path
                .root("easy-db-lab-test-bucket")
                .resolve("clusters/test-cluster-test-id/victorialogs")

        val files =
            listOf(
                ObjectStore.FileInfo(
                    path = basePath.resolve("20240101-120000/data/file1.bin"),
                    size = 1024,
                    lastModified = "2024-01-01T12:00:00Z",
                ),
                ObjectStore.FileInfo(
                    path = basePath.resolve("20240101-120000/data/file2.bin"),
                    size = 2048,
                    lastModified = "2024-01-01T12:00:00Z",
                ),
                ObjectStore.FileInfo(
                    path = basePath.resolve("20240102-130000/data/file3.bin"),
                    size = 4096,
                    lastModified = "2024-01-02T13:00:00Z",
                ),
            )

        whenever(mockObjectStore.listFiles(any(), eq(true))).thenReturn(files)

        val command = LogsLs()
        command.call()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("VictoriaLogs backups:")
        assertThat(output).contains("20240101-120000")
        assertThat(output).contains("20240102-130000")
    }
}
