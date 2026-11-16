package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.File

/**
 * `down` tells the operator when a data-bucket step fails: the request-metric teardown of the
 * cluster's bucket, the discovery of data buckets for `--all`, and the delete of one bucket.
 * Each failure is a typed event naming the bucket and S3's reason, and teardown carries on.
 *
 * The S3 service and AWS teardown are mocked (external services with side effects); cluster state
 * is real, persisted to a temp file.
 */
class DownDataBucketFailureTest : BaseKoinTest() {
    private lateinit var stateManager: ClusterStateManager
    private lateinit var teardownService: AwsInfrastructureService
    private lateinit var s3BucketService: AwsS3BucketService
    private val events = mutableListOf<Event>()

    private val resources = DiscoveredResources(vpcId = "vpc-123", vpcName = "test-cluster")

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(File(tempDir, "state.json")) }
                single { mock<AwsInfrastructureService>() }
                single { mock<TailscaleService>() }
                single { mock<AwsS3BucketService>() }
            },
        )

    @BeforeEach
    fun setup() {
        stateManager = getKoin().get()
        teardownService = getKoin().get()
        s3BucketService = getKoin().get()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )

        whenever(teardownService.teardownVpc(eq("vpc-123"), any())).thenReturn(TeardownResult.success(resources))
        whenever(teardownService.teardownAllTagged(any(), any())).thenReturn(TeardownResult.success(listOf(resources)))
    }

    private fun saveStateWithDataBucket(bucket: String) =
        stateManager.save(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), vpcId = "vpc-123")
                .apply { dataBucket = bucket },
        )

    private fun runDown(): Int =
        Down()
            .apply {
                autoApprove = true
                force = true
            }.call()

    private fun runDownAll(): Int =
        Down()
            .apply {
                teardownAll = true
                autoApprove = true
                force = true
            }.call()

    private fun s3Failure(message: String): S3Exception =
        S3Exception
            .builder()
            .message(message)
            .statusCode(HTTP_FORBIDDEN)
            .build() as S3Exception

    @Test
    fun `a failed request-metric teardown is reported with the bucket and the reason, and the cluster is marked down`() {
        saveStateWithDataBucket("data-bucket-1")
        whenever(s3BucketService.teardownDataBucket(eq("data-bucket-1"), any()))
            .thenThrow(s3Failure("access denied"))

        val exitCode = runDown()

        val failures = events.filterIsInstance<Event.Teardown.DataBucketMetricsDisableFailed>()
        assertThat(failures).hasSize(1)
        assertThat(failures.single().bucket).isEqualTo("data-bucket-1")
        assertThat(failures.single().reason).contains("access denied")
        assertThat(failures.single().isError()).isTrue()
        assertThat(exitCode).isEqualTo(0)
        assertThat(events).anyMatch { it is Event.Teardown.ClusterStateMarkedDown }
    }

    @Test
    fun `a client-side failure of the request-metric teardown is reported too`() {
        saveStateWithDataBucket("data-bucket-1")
        whenever(s3BucketService.teardownDataBucket(eq("data-bucket-1"), any()))
            .thenThrow(SdkClientException.create("Unable to execute HTTP request"))

        runDown()

        val failures = events.filterIsInstance<Event.Teardown.DataBucketMetricsDisableFailed>()
        assertThat(failures).hasSize(1)
        assertThat(failures.single().reason).contains("Unable to execute HTTP request")
    }

    @Test
    fun `a failed data bucket discovery is reported with the reason`() {
        whenever(s3BucketService.findDataBuckets()).thenThrow(s3Failure("list denied"))

        runDownAll()

        val failures = events.filterIsInstance<Event.Teardown.DataBucketDiscoveryFailed>()
        assertThat(failures).hasSize(1)
        assertThat(failures.single().reason).contains("list denied")
        assertThat(failures.single().isError()).isTrue()
    }

    @Test
    fun `a delete that throws keeps that bucket, reports why, and the next bucket is still handled`() {
        whenever(s3BucketService.findDataBuckets()).thenReturn(listOf("bucket-a", "bucket-b"))
        whenever(s3BucketService.deleteEmptyBucket("bucket-a"))
            .thenThrow(SdkClientException.create("connection reset"))
        whenever(s3BucketService.deleteEmptyBucket("bucket-b")).thenReturn(Result.success(Unit))

        runDownAll()

        val kept = events.filterIsInstance<Event.S3.DataBucketKept>()
        assertThat(kept).hasSize(1)
        assertThat(kept.single().bucket).isEqualTo("bucket-a")
        assertThat(kept.single().reason).contains("connection reset")
        assertThat(events.filterIsInstance<Event.S3.DataBucketDeleted>().map { it.bucket })
            .containsExactly("bucket-b")
    }

    @Test
    fun `a state file that cannot be saved is reported and teardown still succeeds`() {
        saveStateWithDataBucket("data-bucket-1")
        // ClusterStateManager.save writes a temp file next to the state file; a read-only
        // directory makes that fail while load still works.
        assertThat(tempDir.setWritable(false)).isTrue()

        try {
            val exitCode = runDown()

            val failures = events.filterIsInstance<Event.Teardown.ClusterStateUpdateFailed>()
            assertThat(failures).hasSize(1)
            assertThat(failures.single().isError()).isTrue()
            assertThat(exitCode).isEqualTo(0)
        } finally {
            tempDir.setWritable(true)
        }
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}
