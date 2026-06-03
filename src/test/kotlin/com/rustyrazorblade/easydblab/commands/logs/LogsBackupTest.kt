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
import com.rustyrazorblade.easydblab.services.VictoriaBackupResult
import com.rustyrazorblade.easydblab.services.VictoriaBackupService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Tests for the LogsBackup command.
 */
class LogsBackupTest : BaseKoinTest() {
    private lateinit var mockVictoriaBackupService: VictoriaBackupService
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
            hosts =
                mapOf(
                    ServerType.Control to listOf(testControlHost),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<VictoriaBackupService> { mockVictoriaBackupService }
                single<ClusterStateManager> { mockClusterStateManager }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockVictoriaBackupService = mock()
        mockClusterStateManager = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler

        // Write state file for ClusterStateManager
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
                "initConfig": {
                    "region": "us-west-2"
                }
            }
            """.trimIndent(),
        )

        whenever(mockClusterStateManager.load()).thenReturn(testClusterState)
    }

    @Test
    fun `execute calls backup service with control host`() {
        // Given
        val backupResult =
            VictoriaBackupResult(
                s3Path = ClusterS3Path.root("test-bucket").victoriaLogs().resolve("20240101-120000"),
                timestamp = "20240101-120000",
            )
        whenever(mockVictoriaBackupService.backupLogs(any(), any(), anyOrNull()))
            .thenReturn(Result.success(backupResult))

        val command = LogsBackup()

        // When
        command.call()

        // Then
        verify(mockVictoriaBackupService).backupLogs(any(), any(), anyOrNull())
    }

    @Test
    fun `execute outputs success message on completion`() {
        // Given
        val backupResult =
            VictoriaBackupResult(
                s3Path = ClusterS3Path.root("test-bucket").victoriaLogs().resolve("20240101-120000"),
                timestamp = "20240101-120000",
            )
        whenever(mockVictoriaBackupService.backupLogs(any(), any(), anyOrNull()))
            .thenReturn(Result.success(backupResult))

        val command = LogsBackup()

        // When
        command.call()

        // Then
        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("backup completed successfully")
        assertThat(output).contains("s3://test-bucket/victorialogs/20240101-120000")
    }

    @Test
    fun `execute handles backup failure`() {
        // Given
        whenever(mockVictoriaBackupService.backupLogs(any(), any(), anyOrNull()))
            .thenReturn(Result.failure(RuntimeException("Backup failed: S3 error")))

        val command = LogsBackup()

        // When
        command.call()

        // Then
        val allOutput = outputHandler.messages.joinToString("\n") + outputHandler.errors.joinToString("\n") { it.first }
        assertThat(allOutput).contains("backup failed")
        assertThat(allOutput).contains("S3 error")
    }

    @Test
    fun `execute handles missing control node`() {
        // Given
        val stateWithoutControl =
            testClusterState.copy(
                hosts = emptyMap(),
            )
        whenever(mockClusterStateManager.load()).thenReturn(stateWithoutControl)

        val command = LogsBackup()

        // When - Should handle error gracefully (not throw)
        command.call()

        // Then - Should output an error message
        val errors = outputHandler.errors.joinToString("\n") { it.first }
        assertThat(errors).contains("No control node found")
    }
}
