package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import io.fabric8.kubernetes.api.model.batch.v1.Job
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Test suite for VictoriaBackupService.
 *
 * Tests backup operations for VictoriaMetrics and VictoriaLogs using mocked K8sService.
 */
class VictoriaBackupServiceTest : BaseKoinTest() {
    // Initialize mock before Koin modules are loaded - this instance is shared
    private val mockK8sService: K8sService = mock()
    private lateinit var outputHandler: BufferedOutputHandler
    private lateinit var victoriaBackupService: VictoriaBackupService

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
            initConfig =
                InitConfig(
                    region = "us-west-2",
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<K8sService> { mockK8sService }
                factory<VictoriaBackupService> {
                    DefaultVictoriaBackupService(get(), get())
                }
            },
        )

    @BeforeEach
    fun setupMocks() {
        // Reset mock expectations for each test
        reset(mockK8sService)
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        victoriaBackupService = getKoin().get()
    }

    // ========== METRICS BACKUP TESTS ==========

    @Test
    fun `backupMetrics creates K8s Job with correct spec`() {
        // Given - mock createJob to fail early so we don't need job completion mocking
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenReturn(Result.failure(RuntimeException("Test: stop after createJob")))

        // When
        victoriaBackupService.backupMetrics(testControlHost, testClusterState)

        // Then - Verify job was created with vmbackup image, correct args, and AWS_REGION env var
        verify(mockK8sService).createJob(
            eq(testControlHost),
            eq("default"),
            argThat<Job> { job ->
                val container =
                    job.spec.template.spec.containers
                        .first()
                container.image == "victoriametrics/vmbackup:latest" &&
                    container.args.any { it.contains("-storageDataPath=/mnt/db1/victoriametrics") } &&
                    container.args.any { it.contains("-snapshot.createURL=http://localhost:8428/snapshot/create") } &&
                    container.args.any { it.contains("s3://easy-db-lab-test-bucket/") } &&
                    container.env.any { it.name == "AWS_REGION" && it.value == "us-west-2" }
            },
        )
    }

    @Test
    fun `backupMetrics handles createJob failure`() {
        // Given
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenReturn(Result.failure(RuntimeException("K8s API error")))

        // When
        val result = victoriaBackupService.backupMetrics(testControlHost, testClusterState)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("K8s API error")
    }

    @Test
    fun `backupMetrics generates correct S3 path in job`() {
        // Given - capture the Job to verify the S3 path
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupMetrics(testControlHost, testClusterState)

        // Then - verify S3 path in the job args
        val args =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .args
        val dstArg = args.first { it.startsWith("-dst=") }
        assertThat(dstArg).contains("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/victoriametrics/")
        // Verify timestamp format in job name (YYYYMMDD-HHMMSS)
        assertThat(capturedJob!!.metadata.name).matches("vmbackup-\\d{8}-\\d{6}")
    }

    @Test
    fun `backupMetrics fails when S3 bucket not configured`() {
        // Given
        val stateWithoutBucket = testClusterState.copy(s3Bucket = null)

        // When
        val result = victoriaBackupService.backupMetrics(testControlHost, stateWithoutBucket)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("S3 bucket not configured")
    }

    // ========== LOGS BACKUP TESTS ==========

    @Test
    fun `backupLogs creates K8s Job with aws-cli for S3 sync`() {
        // Given - mock createJob to fail early so we don't need job completion mocking
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenReturn(Result.failure(RuntimeException("Test: stop after createJob")))

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then - Verify job uses aws-cli with snapshot-based backup
        verify(mockK8sService).createJob(
            eq(testControlHost),
            eq("default"),
            argThat<Job> { job ->
                val container =
                    job.spec.template.spec.containers
                        .first()
                val script = container.command.last()
                container.image == "amazon/aws-cli:latest" &&
                    script.contains("aws s3 sync") &&
                    script.contains("/mnt/db1/victorialogs") &&
                    script.contains("s3://easy-db-lab-test-bucket/") &&
                    script.contains("/internal/partition/snapshot/create") &&
                    script.contains("/internal/partition/snapshot/delete")
            },
        )
    }

    @Test
    fun `backupLogs generates correct S3 path in job`() {
        // Given - capture the Job to verify the S3 path
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then - verify S3 path in the job script
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/victorialogs/")
        // Verify timestamp format in job name (YYYYMMDD-HHMMSS)
        assertThat(capturedJob!!.metadata.name).matches("vlbackup-\\d{8}-\\d{6}")
    }

    @Test
    fun `backupLogs handles API errors gracefully`() {
        // Given
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenReturn(Result.failure(RuntimeException("K8s API error")))

        // When
        val result = victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("K8s API error")
    }

    @Test
    fun `backupLogs fails when S3 bucket not configured`() {
        // Given
        val stateWithoutBucket = testClusterState.copy(s3Bucket = null)

        // When
        val result = victoriaBackupService.backupLogs(testControlHost, stateWithoutBucket)

        // Then
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("S3 bucket not configured")
    }

    // ========== CUSTOM DESTINATION TESTS ==========

    @Test
    fun `backupMetrics uses custom destination when provided`() {
        // Given - capture the Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }
        val customDest = "s3://custom-backup-bucket/my-backups"

        // When
        victoriaBackupService.backupMetrics(testControlHost, testClusterState, customDest)

        // Then - verify the Job contains the custom destination
        val args =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .args
        val dstArg = args.first { it.startsWith("-dst=") }
        assertThat(dstArg).contains("s3://custom-backup-bucket/my-backups/")
    }

    @Test
    fun `backupMetrics uses cluster bucket when dest is null`() {
        // Given - capture the Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupMetrics(testControlHost, testClusterState, null)

        // Then - verify the Job contains the cluster bucket
        val args =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .args
        val dstArg = args.first { it.startsWith("-dst=") }
        assertThat(dstArg).contains("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/victoriametrics/")
    }

    @Test
    fun `backupLogs uses custom destination when provided`() {
        // Given - capture the Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }
        val customDest = "s3://other-bucket/logs-backup"

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState, customDest)

        // Then - verify the Job contains the custom destination
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("s3://other-bucket/logs-backup/")
    }

    @Test
    fun `backupLogs uses cluster bucket when dest is null`() {
        // Given - capture the Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState, null)

        // Then - verify the Job contains the cluster bucket
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("s3://easy-db-lab-test-bucket/clusters/test-cluster-test-id/victorialogs/")
    }

    // ========== PARSE S3 URI TESTS ==========

    @Test
    fun `parseS3Uri handles bucket-only URI`() {
        // Given
        val service = victoriaBackupService as DefaultVictoriaBackupService
        val uri = "s3://my-bucket"
        val timestamp = "20240101-120000"

        // When
        val (bucket, s3Path) = service.parseS3Uri(uri, timestamp)

        // Then
        assertThat(bucket).isEqualTo("my-bucket")
        assertThat(s3Path.toString()).isEqualTo("s3://my-bucket/$timestamp")
    }

    @Test
    fun `parseS3Uri handles bucket with path`() {
        // Given
        val service = victoriaBackupService as DefaultVictoriaBackupService
        val uri = "s3://my-bucket/some/path"
        val timestamp = "20240101-120000"

        // When
        val (bucket, s3Path) = service.parseS3Uri(uri, timestamp)

        // Then
        assertThat(bucket).isEqualTo("my-bucket")
        assertThat(s3Path.toString()).isEqualTo("s3://my-bucket/some/path/$timestamp")
    }

    @Test
    fun `parseS3Uri handles trailing slash in path`() {
        // Given
        val service = victoriaBackupService as DefaultVictoriaBackupService
        val uri = "s3://my-bucket/some/path/"
        val timestamp = "20240101-120000"

        // When
        val (bucket, s3Path) = service.parseS3Uri(uri, timestamp)

        // Then
        assertThat(bucket).isEqualTo("my-bucket")
        assertThat(s3Path.toString()).isEqualTo("s3://my-bucket/some/path/$timestamp")
    }

    @Test
    fun `parseS3Uri rejects non-S3 URI`() {
        // Given
        val service = victoriaBackupService as DefaultVictoriaBackupService
        val uri = "https://example.com/bucket"
        val timestamp = "20240101-120000"

        // When/Then
        val exception =
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                service.parseS3Uri(uri, timestamp)
            }
        assertThat(exception.message).contains("Destination must be an S3 URI")
    }

    // ========== SNAPSHOT LIFECYCLE TESTS ==========

    @Test
    fun `backupLogs job includes snapshot lifecycle`() {
        // Given - capture Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then - verify all three steps: create, sync, delete
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("/internal/partition/snapshot/create")
        assertThat(script).contains("aws s3 sync")
        assertThat(script).contains("/internal/partition/snapshot/delete")
    }

    @Test
    fun `backupLogs job translates VL internal path to host mount`() {
        // Given - capture Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then - verify sed substitution translates VL's internal path to the host mount path
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("sed \"s|/victoria-logs-data|/mnt/db1/victorialogs|\"")
    }

    @Test
    fun `backupLogs job includes snapshot cleanup on failure`() {
        // Given - capture Job
        var capturedJob: Job? = null
        whenever(mockK8sService.createJob(any(), any(), any<Job>()))
            .thenAnswer { invocation ->
                capturedJob = invocation.getArgument(2)
                Result.failure<String>(RuntimeException("Test: stop after createJob"))
            }

        // When
        victoriaBackupService.backupLogs(testControlHost, testClusterState)

        // Then - verify SYNC_FAILED pattern ensures cleanup runs even on failure
        val script =
            capturedJob!!
                .spec.template.spec.containers
                .first()
                .command
                .last()
        assertThat(script).contains("SYNC_FAILED=0")
        assertThat(script).contains("|| SYNC_FAILED=1")
        // Verify snapshot delete loop comes after sync loop
        val syncIndex = script.indexOf("aws s3 sync")
        val deleteIndex = script.indexOf("/internal/partition/snapshot/delete")
        assertThat(deleteIndex).isGreaterThan(syncIndex)
    }
}
