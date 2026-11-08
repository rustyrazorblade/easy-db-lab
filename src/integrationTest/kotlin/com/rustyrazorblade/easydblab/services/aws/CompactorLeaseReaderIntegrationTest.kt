package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.compactorlease.LeaseRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * `observability compactor status` reads the real lease document from S3 (LocalStack) in the
 * bucket's region: an absent lease is [CompactorLease.None], a present one is parsed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactorLeaseReaderIntegrationTest {
    private val s3 = SharedLocalStack.s3Client()
    private val empty = "lease-it-empty-${UUID.randomUUID().toString().take(8)}"
    private val held = "lease-it-held-${UUID.randomUUID().toString().take(8)}"
    private val renewedAt = Instant.parse("2026-11-07T18:00:00Z")

    private val reader =
        CompactorLeaseReader(
            regionOf = { SharedLocalStack.region() },
            leaseIn = CompactorLeaseReader.fromS3 { SharedLocalStack.s3Client() },
            clock = Clock.fixed(renewedAt.plusSeconds(5), ZoneOffset.UTC),
        )

    @BeforeAll
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, empty)
        SharedLocalStack.createBucketIfMissing(s3, held)
        s3.putObject(
            PutObjectRequest.builder().bucket(held).key(LeaseRecord.KEY).build(),
            RequestBody.fromString(LeaseRecord("lab-a/pod-1", 7, 120, renewedAt).toJson()),
        )
    }

    @Test
    fun `a bucket with no lease reads as none`() {
        assertThat(reader.read(empty)).isEqualTo(CompactorLease.None(SharedLocalStack.region()))
    }

    @Test
    fun `a bucket with a lease reads its holder, sequence and age`() {
        val lease = reader.read(held)

        assertThat(lease).isInstanceOf(CompactorLease.Held::class.java)
        lease as CompactorLease.Held
        assertThat(lease.record.holder).isEqualTo("lab-a/pod-1")
        assertThat(lease.record.sequence).isEqualTo(7)
        assertThat(lease.age.seconds).isEqualTo(5)
    }
}
