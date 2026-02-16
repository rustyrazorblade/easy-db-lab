package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.StreamResult
import com.rustyrazorblade.easydblab.services.VictoriaStreamService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class MetricsImportTest : BaseKoinTest() {
    private lateinit var mockVictoriaStreamService: VictoriaStreamService
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
            initConfig = InitConfig(region = "us-west-2"),
            hosts = mapOf(ServerType.Control to listOf(testControlHost)),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<VictoriaStreamService> { mockVictoriaStreamService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockVictoriaStreamService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        File(tempDir, "state.json").writeText(
            """
            {
                "name": "test-cluster",
                "versions": {},
                "s3Bucket": "easy-db-lab-test-bucket",
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
    fun `execute calls stream service with correct arguments`() {
        whenever(mockVictoriaStreamService.streamMetrics(any(), any(), any()))
            .thenReturn(Result.success(StreamResult(bytesTransferred = 12345)))

        val command = MetricsImport()
        command.target = "http://victoria:8428"
        command.call()

        verify(mockVictoriaStreamService).streamMetrics(any(), eq("http://victoria:8428"), any())
    }

    @Test
    fun `execute outputs success message with bytes transferred`() {
        whenever(mockVictoriaStreamService.streamMetrics(any(), any(), any()))
            .thenReturn(Result.success(StreamResult(bytesTransferred = 12345)))

        val command = MetricsImport()
        command.target = "http://victoria:8428"
        command.call()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("completed successfully")
        assertThat(output).contains("12345")
    }

    @Test
    fun `execute handles import failure`() {
        whenever(mockVictoriaStreamService.streamMetrics(any(), any(), any()))
            .thenReturn(Result.failure(RuntimeException("Connection refused")))

        val command = MetricsImport()
        command.target = "http://victoria:8428"
        command.call()

        val errors = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errors).contains("import failed")
        assertThat(errors).contains("Connection refused")
    }

    @Test
    fun `execute handles missing control node`() {
        val stateWithoutControl = testClusterState.copy(hosts = emptyMap())
        whenever(mockClusterStateManager.load()).thenReturn(stateWithoutControl)

        val command = MetricsImport()
        command.target = "http://victoria:8428"
        command.call()

        val errors = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errors).contains("No control node found")
    }
}
