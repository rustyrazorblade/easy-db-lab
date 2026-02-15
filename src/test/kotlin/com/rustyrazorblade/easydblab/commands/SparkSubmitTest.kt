package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.EMRClusterInfo
import com.rustyrazorblade.easydblab.configuration.s3Path
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.SparkService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

class SparkSubmitTest : BaseKoinTest() {
    private lateinit var mockSparkService: SparkService
    private lateinit var mockObjectStore: ObjectStore
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                // Mock SparkService
                single {
                    mock<SparkService>().also {
                        mockSparkService = it
                    }
                }

                // Mock ObjectStore
                single {
                    mock<ObjectStore>().also {
                        mockObjectStore = it
                    }
                }

                // Mock ClusterStateManager
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
            },
        )

    @Test
    fun `command validates required parameters`() {
        val command = SparkSubmit()

        // Verify optional parameters have defaults and required lateinit vars are not initialized
        assertThat(command.jobArgs).isEmpty()
        assertThat(command.wait).isFalse()
        // jarPath and mainClass are lateinit vars that will be set by PicoCLI
        // Attempting to access them before initialization would throw UninitializedPropertyAccessException
    }

    @Test
    fun `local JAR upload should use cluster-specific S3 path`(
        @TempDir tempDir: File,
    ) {
        // Set up ClusterState
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val testBucket = "easy-db-lab-test-12345678"
        val state =
            ClusterState(
                name = "test-cluster",
                versions = mutableMapOf(),
                s3Bucket = testBucket,
                clusterId = "test-id",
            )
        manager.save(state)

        // Verify the S3 path format by examining what would be constructed
        val s3Path = state.s3Path()
        val jarPath = s3Path.spark().resolve("test-app.jar")

        // Verify the full URI follows per-environment bucket format with cluster prefix
        assertThat(jarPath.toString()).isEqualTo("s3://$testBucket/clusters/test-cluster-test-id/spark/test-app.jar")

        // Verify the key (for S3 SDK) follows the correct format
        assertThat(jarPath.getKey()).isEqualTo("clusters/test-cluster-test-id/spark/test-app.jar")

        // Verify bucket is correct
        assertThat(jarPath.bucket).isEqualTo(testBucket)
    }

    // Helper to initialize mocks by triggering Koin injection
    private fun initMocks() {
        get<SparkService>()
        get<ObjectStore>()
        get<ClusterStateManager>()
    }

    // ========== EXECUTE TESTS ==========

    @Test
    fun `execute with S3 JAR path should not upload`(
        @TempDir tempDir: File,
    ) {
        // Given - set up cluster state
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        manager.save(state)

        val testClusterId = "j-TEST123"
        val validClusterInfo =
            EMRClusterInfo(
                clusterId = testClusterId,
                name = "test-cluster",
                masterPublicDns = "master.example.com",
                state = "WAITING",
            )

        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = "s3://test-bucket/jars/app.jar"
        command.mainClass = "com.example.Main"

        // Set up mocks
        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockSparkService.submitJob(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success("s-STEPID"))

        // When
        command.execute()

        // Then - should not upload since it's already an S3 path
        verify(mockObjectStore, never()).uploadFile(any(), any(), any())
        verify(mockSparkService).submitJob(
            testClusterId,
            "s3://test-bucket/jars/app.jar",
            "com.example.Main",
            emptyList(),
            null,
            emptyMap(),
            emptyMap(),
        )
    }

    @Test
    fun `execute with local JAR should upload to S3`(
        @TempDir tempDir: File,
    ) {
        // Given - set up cluster state and local JAR
        val state = ClusterState(name = "test-cluster", versions = mutableMapOf(), s3Bucket = "easy-db-lab-test-12345678")

        val localJar = File(tempDir, "app.jar")
        localJar.writeText("test jar content")

        val testClusterId = "j-TEST123"
        val validClusterInfo =
            EMRClusterInfo(
                clusterId = testClusterId,
                name = "test-cluster",
                masterPublicDns = "master.example.com",
                state = "WAITING",
            )

        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = localJar.absolutePath
        command.mainClass = "com.example.Main"

        // Set up mocks - need to capture the S3 path from upload
        val expectedS3Path = state.s3Path().spark().resolve("app.jar")

        whenever(mockClusterStateManager.load()).thenReturn(state)
        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(mockObjectStore.uploadFile(any(), any(), any()))
            .thenReturn(ObjectStore.UploadResult(expectedS3Path, localJar.length()))
        whenever(mockSparkService.submitJob(any(), any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success("s-STEPID"))

        // When
        command.execute()

        // Then - should upload since it's a local path
        verify(mockObjectStore).uploadFile(any(), any(), any())
        verify(mockSparkService).submitJob(
            testClusterId,
            expectedS3Path.toString(),
            "com.example.Main",
            emptyList(),
            null,
            emptyMap(),
            emptyMap(),
        )
    }

    @Test
    fun `execute should fail when cluster validation fails`() {
        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = "s3://test-bucket/jars/app.jar"
        command.mainClass = "com.example.Main"

        whenever(mockSparkService.validateCluster())
            .thenReturn(Result.failure(IllegalStateException("No EMR cluster found")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No EMR cluster found")
    }

    @Test
    fun `execute should fail when job submission fails`() {
        val validClusterInfo =
            EMRClusterInfo(
                clusterId = "j-TEST123",
                name = "test-cluster",
                masterPublicDns = "master.example.com",
                state = "WAITING",
            )

        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = "s3://test-bucket/jars/app.jar"
        command.mainClass = "com.example.Main"

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))
        whenever(
            mockSparkService.submitJob(
                any(),
                any(),
                any(),
                any<List<String>>(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(Result.failure(RuntimeException("EMR API error")))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("EMR API error")
    }

    @Test
    fun `execute should fail when local JAR file does not exist`() {
        val validClusterInfo =
            EMRClusterInfo(
                clusterId = "j-TEST123",
                name = "test-cluster",
                masterPublicDns = "master.example.com",
                state = "WAITING",
            )

        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = "/nonexistent/path/app.jar"
        command.mainClass = "com.example.Main"

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not exist")
    }

    @Test
    fun `execute should fail when local file is not a JAR`(
        @TempDir tempDir: File,
    ) {
        val notAJar = File(tempDir, "app.txt")
        notAJar.writeText("not a jar")

        val validClusterInfo =
            EMRClusterInfo(
                clusterId = "j-TEST123",
                name = "test-cluster",
                masterPublicDns = "master.example.com",
                state = "WAITING",
            )

        // Initialize mocks before use
        initMocks()

        val command = SparkSubmit()
        command.jarPath = notAJar.absolutePath
        command.mainClass = "com.example.Main"

        whenever(mockSparkService.validateCluster()).thenReturn(Result.success(validClusterInfo))

        // When/Then
        assertThatThrownBy { command.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(".jar extension")
    }
}
