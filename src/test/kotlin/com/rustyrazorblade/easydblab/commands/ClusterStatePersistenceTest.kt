package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for ClusterState persistence — verifies that fields like s3Bucket,
 * clusterId, and infrastructure status survive save/load cycles via ClusterStateManager.
 */
class ClusterStatePersistenceTest : BaseKoinTest() {
    @Test
    fun `S3 bucket environment variable constant should be correctly defined`() {
        assertThat(Constants.Environment.S3_BUCKET).isEqualTo("EASY_DB_LAB_S3_BUCKET")
    }

    @Test
    fun `S3 bucket prefix constant should be defined correctly`() {
        assertThat(Constants.S3.BUCKET_PREFIX).isEqualTo("easy-db-lab-")
    }

    @Test
    fun `ClusterState s3Bucket field should persist across saves`(
        @TempDir tempDir: File,
    ) {
        // Given
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val bucketName = "test-bucket-12345678"

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = bucketName
        manager.save(state)

        // When
        val loadedState = manager.load()

        // Then
        assertThat(loadedState.s3Bucket).isEqualTo(bucketName)
    }

    @Test
    fun `ClusterState s3Bucket field should allow updates`(
        @TempDir tempDir: File,
    ) {
        // Given
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = "original-bucket"
        manager.save(state)

        // When
        val loadedState = manager.load()
        loadedState.s3Bucket = "different-bucket"
        manager.save(loadedState)

        // Then
        val reloadedState = manager.load()
        assertThat(reloadedState.s3Bucket).isEqualTo("different-bucket")
    }

    @Test
    fun `ClusterState s3Bucket field should be null by default`(
        @TempDir tempDir: File,
    ) {
        // Given
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        manager.save(state)

        // When
        val loadedState = manager.load()

        // Then
        assertThat(loadedState.s3Bucket).isNull()
    }

    @Test
    fun `bucket selection logic should use existing bucket when s3Bucket is set`(
        @TempDir tempDir: File,
    ) {
        // Given - state has existing bucket configured
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val existingBucket = "already-configured-bucket"

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = existingBucket
        state.initConfig =
            InitConfig(
                name = "test",
                region = "us-west-2",
            )
        manager.save(state)

        // When - simulating bucket selection logic
        val loadedState = manager.load()
        val shouldSkipCreation = !loadedState.s3Bucket.isNullOrBlank()

        // Then
        assertThat(shouldSkipCreation).isTrue()
        assertThat(loadedState.s3Bucket).isEqualTo(existingBucket)
    }

    @Test
    fun `bucket selection logic should create new bucket when s3Bucket is null`(
        @TempDir tempDir: File,
    ) {
        // Given - state has no bucket configured
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.initConfig =
            InitConfig(
                name = "test",
                region = "us-west-2",
            )
        manager.save(state)

        // When - simulating bucket selection logic
        val loadedState = manager.load()
        val shouldCreateBucket = loadedState.s3Bucket.isNullOrBlank()

        // Then
        assertThat(shouldCreateBucket).isTrue()
    }

    @Test
    fun `bucket selection logic should create new bucket when s3Bucket is empty`(
        @TempDir tempDir: File,
    ) {
        // Given - state has empty bucket string
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = ""
        state.initConfig =
            InitConfig(
                name = "test",
                region = "us-west-2",
            )
        manager.save(state)

        // When - simulating bucket selection logic
        val loadedState = manager.load()
        val shouldCreateBucket = loadedState.s3Bucket.isNullOrBlank()

        // Then
        assertThat(shouldCreateBucket).isTrue()
    }

    @Test
    fun `environment variable bucket should be stored directly in state`(
        @TempDir tempDir: File,
    ) {
        // Given - simulating environment variable being set to existing bucket
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val envBucket = "user-provided-s3-bucket"

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.initConfig =
            InitConfig(
                name = "test",
                region = "us-west-2",
            )
        manager.save(state)

        // When - simulating Up command storing env bucket
        val loadedState = manager.load()
        // In the real Up command, this comes from System.getenv(Constants.Environment.S3_BUCKET)
        loadedState.s3Bucket = envBucket
        manager.save(loadedState)

        // Then
        val finalState = manager.load()
        assertThat(finalState.s3Bucket).isEqualTo(envBucket)
    }

    @Test
    fun `cluster state should preserve s3Bucket when other fields change`(
        @TempDir tempDir: File,
    ) {
        // Given
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val bucketName = "persistent-bucket"

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = bucketName
        state.initConfig =
            InitConfig(
                name = "test",
                region = "us-west-2",
            )
        manager.save(state)

        // When - modify other fields
        val loadedState = manager.load()
        loadedState.markInfrastructureUp()
        manager.save(loadedState)

        // Then - s3Bucket should be preserved
        val finalState = manager.load()
        assertThat(finalState.s3Bucket).isEqualTo(bucketName)
        assertThat(finalState.isInfrastructureUp()).isTrue()
    }

    @Test
    fun `cluster ID should be stable across loads`(
        @TempDir tempDir: File,
    ) {
        // Given
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        manager.save(state)

        // When
        val loadedState1 = manager.load()
        val loadedState2 = manager.load()

        // Then - same cluster ID used for bucket naming
        assertThat(loadedState1.clusterId).isEqualTo(loadedState2.clusterId)
    }

    @Test
    fun `cluster state should use account bucket from user config`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        val manager = ClusterStateManager(stateFile)
        val accountBucket = "easy-db-lab-account-uuid"

        val state = ClusterState(name = "test-cluster", versions = mutableMapOf())
        state.s3Bucket = accountBucket
        manager.save(state)

        val loadedState = manager.load()
        assertThat(loadedState.s3Bucket).isEqualTo(accountBucket)
    }
}
