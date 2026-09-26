package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InfrastructureState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import com.rustyrazorblade.easydblab.services.aws.AwsInfrastructureService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.Tag
import software.amazon.awssdk.services.s3.model.Tagging
import java.util.UUID

/**
 * Proves against a real S3 API (LocalStack) that `down` never schedules the owner's data for
 * deletion: after its S3 steps run, neither the account bucket nor any data bucket carries a
 * lifecycle configuration, and every object is still there. With `--all`, only empty data buckets
 * are deleted.
 *
 * The VPC teardown itself is mocked at the [AwsInfrastructureService] boundary; the S3 calls go to
 * LocalStack through the real `AWS` and `AwsS3BucketService`.
 */
class DownS3NoExpiryIntegrationTest : BaseKoinTest() {
    private val s3: S3Client = SharedLocalStack.s3Client()
    private val infra: AwsInfrastructureService = mock()
    private val stateManager: ClusterStateManager = mock()

    private val accountBucket = "easy-db-lab-down-noexpiry-${UUID.randomUUID().toString().take(8)}"
    private val clusterId = UUID.randomUUID().toString()
    private val dataBucket = "${Constants.S3.DATA_BUCKET_PREFIX}$clusterId"

    private val state =
        ClusterState(
            name = "noexpiry",
            versions = mutableMapOf(),
            clusterId = clusterId,
            s3Bucket = accountBucket,
            dataBucket = dataBucket,
        ).apply {
            updateInfrastructure(
                InfrastructureState(
                    vpcId = "vpc-noexpiry",
                    region = "us-east-1",
                    subnetIds = listOf("subnet-1"),
                    securityGroupId = "sg-1",
                ),
            )
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<S3Client> { s3 }
                single<AwsInfrastructureService> { infra }
                single<ClusterStateManager> { stateManager }
            },
        )

    @BeforeEach
    fun setup() {
        whenever(stateManager.exists()).thenReturn(true)
        whenever(stateManager.load()).thenReturn(state)
        val resources = TeardownResult.success(DiscoveredResources(vpcId = "vpc-noexpiry", instanceIds = listOf("i-1")))
        whenever(infra.teardownVpc(eq("vpc-noexpiry"), any())).thenReturn(resources)
        whenever(infra.teardownAllTagged(any(), any())).thenReturn(resources)
    }

    @Test
    fun `down sets no lifecycle rule and keeps every object`() {
        createBucket(accountBucket)
        createDataBucket(dataBucket)
        val accountKeys = listOf("${state.clusterPrefix()}/victoriametrics/a.bin", "observability/metrics/default/x/b.bin")
        accountKeys.forEach { put(accountBucket, it) }
        put(dataBucket, "pyroscope/block.bin")

        Down()
            .apply {
                autoApprove = true
                force = true
            }.execute()

        assertNoLifecycle(accountBucket)
        assertNoLifecycle(dataBucket)
        assertThat(keys(accountBucket)).containsExactlyInAnyOrderElementsOf(accountKeys)
        assertThat(keys(dataBucket)).containsExactly("pyroscope/block.bin")
    }

    @Test
    fun `down --all deletes only empty data buckets and sets no lifecycle rule`() {
        val empty = "${Constants.S3.DATA_BUCKET_PREFIX}${UUID.randomUUID()}"
        val full = "${Constants.S3.DATA_BUCKET_PREFIX}${UUID.randomUUID()}"
        createDataBucket(empty)
        createDataBucket(full)
        put(full, "clickhouse/part.bin")
        val emitted = recordEvents()

        Down()
            .apply {
                teardownAll = true
                autoApprove = true
            }.execute()

        assertThat(bucketExists(empty)).isFalse()
        assertThat(bucketExists(full)).isTrue()
        assertNoLifecycle(full)
        assertThat(keys(full)).containsExactly("clickhouse/part.bin")
        assertThat(emitted).contains(Event.S3.DataBucketDeleted(empty))
        // Without this, the operator reads "Deleting data bucket" for the full bucket and nothing after.
        val kept = emitted.filterIsInstance<Event.S3.DataBucketKept>()
        assertThat(kept.map { it.bucket }).contains(full).doesNotContain(empty)
        assertThat(kept.single { it.bucket == full }.reason).isNotBlank()
    }

    /** Every event emitted from now on, in order. */
    private fun recordEvents(): List<Event> {
        val emitted = mutableListOf<Event>()
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    emitted += envelope.event
                }

                override fun close() = Unit
            },
        )
        return emitted
    }

    private fun createBucket(bucket: String) = SharedLocalStack.createBucketIfMissing(s3, bucket)

    private fun createDataBucket(bucket: String) {
        createBucket(bucket)
        s3.putBucketTagging(
            PutBucketTaggingRequest
                .builder()
                .bucket(bucket)
                .tagging(
                    Tagging
                        .builder()
                        .tagSet(
                            Tag
                                .builder()
                                .key(Constants.Vpc.TAG_KEY)
                                .value(Constants.Vpc.TAG_VALUE)
                                .build(),
                        ).build(),
                ).build(),
        )
    }

    private fun put(
        bucket: String,
        key: String,
    ) {
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
            RequestBody.fromString("data"),
        )
    }

    private fun keys(bucket: String): List<String> =
        s3
            .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
            .contents()
            .map { it.key() }

    private fun bucketExists(bucket: String): Boolean =
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            true
        } catch (_: NoSuchBucketException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == Constants.HttpStatus.NOT_FOUND) false else throw e
        }

    private fun assertNoLifecycle(bucket: String) {
        assertThatThrownBy {
            s3.getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build())
        }.isInstanceOf(S3Exception::class.java)
            .satisfies({ e ->
                assertThat((e as S3Exception).awsErrorDetails().errorCode()).isEqualTo("NoSuchLifecycleConfiguration")
            })
    }
}
