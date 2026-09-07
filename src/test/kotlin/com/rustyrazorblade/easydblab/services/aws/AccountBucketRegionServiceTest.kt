package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Verifies how the account bucket's region is resolved, persisted and reported.
 *
 * The account bucket is one per account, while a cluster can be brought up in any region, so a
 * region taken from the cluster points at the wrong endpoint. A `state.json` written before the
 * field existed carries no region at all, which is why resolution has to be lazy — and why it must
 * fail rather than guess.
 *
 * Uses a real [ClusterStateManager] over a temporary `state.json`, so persistence is observed on
 * disk rather than asserted against a mock.
 */
class AccountBucketRegionServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var stateFile: File
    private lateinit var stateManager: ClusterStateManager
    private lateinit var s3BucketService: AwsS3BucketService

    private fun writeState(
        accountBucket: String? = "easy-db-lab-account",
        accountBucketRegion: String? = null,
        clusterRegion: String = "us-east-2",
    ) {
        stateManager.save(
            ClusterState(
                name = "test-cluster",
                clusterId = "test-id",
                versions = mutableMapOf(),
                s3Bucket = accountBucket,
                accountBucketRegion = accountBucketRegion,
                initConfig = InitConfig(region = clusterRegion, name = "test-cluster"),
            ),
        )
    }

    private fun service() = AccountBucketRegionService(stateManager, s3BucketService)

    @BeforeEach
    fun setUp() {
        stateFile = File(tempDir, "state.json")
        stateManager = ClusterStateManager(stateFile)
        s3BucketService = mock()
    }

    @Test
    fun `a stored region is returned without calling GetBucketLocation`() {
        writeState(accountBucketRegion = "eu-west-1")

        assertThat(service().resolve()).isEqualTo("eu-west-1")

        verify(s3BucketService, never()).getBucketRegion(any())
    }

    @Test
    fun `an absent region is resolved from the bucket and persisted`() {
        writeState(accountBucketRegion = null)
        whenever(s3BucketService.getBucketRegion("easy-db-lab-account")).thenReturn("eu-west-1")

        assertThat(service().resolve()).isEqualTo("eu-west-1")

        // Persisted, so the next command reads it instead of calling AWS again.
        assertThat(stateManager.load().accountBucketRegion).isEqualTo("eu-west-1")
        assertThat(stateFile.readText()).contains("eu-west-1")
    }

    @Test
    fun `the resolution happens once, not once per command`() {
        writeState(accountBucketRegion = null)
        whenever(s3BucketService.getBucketRegion("easy-db-lab-account")).thenReturn("eu-west-1")

        val first = service().resolve()
        val second = service().resolve()

        assertThat(first).isEqualTo("eu-west-1")
        assertThat(second).isEqualTo("eu-west-1")
        verify(s3BucketService, times(1)).getBucketRegion("easy-db-lab-account")
    }

    @Test
    fun `an absent region never falls back to the cluster's region`() {
        // The cluster is in us-east-2; the account bucket is in eu-west-1. A fallback would work on
        // a cluster inside the bucket's own region and silently point elsewhere outside it.
        writeState(accountBucketRegion = null, clusterRegion = "us-east-2")
        whenever(s3BucketService.getBucketRegion("easy-db-lab-account")).thenReturn("eu-west-1")

        assertThat(service().resolve()).isEqualTo("eu-west-1")
    }

    @Test
    fun `a failed resolution names the bucket and the call it attempted`() {
        writeState(accountBucketRegion = null)
        whenever(s3BucketService.getBucketRegion("easy-db-lab-account"))
            .thenThrow(IllegalStateException("access denied"))

        assertThatThrownBy { service().resolve() }
            .hasMessageContaining("easy-db-lab-account")
            .hasMessageContaining("GetBucketLocation")
    }

    @Test
    fun `a failed resolution substitutes no other region and persists nothing`() {
        writeState(accountBucketRegion = null, clusterRegion = "us-east-2")
        whenever(s3BucketService.getBucketRegion("easy-db-lab-account"))
            .thenThrow(IllegalStateException("access denied"))

        assertThatThrownBy { service().resolve() }.isInstanceOf(IllegalStateException::class.java)

        assertThat(stateManager.load().accountBucketRegion).isNull()
    }

    @Test
    fun `a missing account bucket fails rather than resolving an empty bucket name`() {
        writeState(accountBucket = null, accountBucketRegion = null)

        assertThatThrownBy { service().resolve() }
            .hasMessageContaining("account S3 bucket")

        verify(s3BucketService, never()).getBucketRegion(any())
    }
}
