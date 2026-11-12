package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.compactorlease.LeaseRecord
import com.rustyrazorblade.easydblab.compactorlease.S3ConditionalObjects
import software.amazon.awssdk.services.s3.S3Client
import kotlin.time.Clock
import kotlin.time.Duration

/** What the account bucket's compactor lease says, as `observability compactor status` prints it. */
sealed interface CompactorLease {
    /** The bucket's region, which the lease was read with. */
    val region: String

    /** No compactor has ever taken the lease. */
    data class None(
        override val region: String,
    ) : CompactorLease

    /** The lease [record], last renewed [age] ago by this workstation's clock. */
    data class Held(
        override val region: String,
        val record: LeaseRecord,
        val age: Duration,
    ) : CompactorLease

    /**
     * A lease object whose body is not a lease. No agent can renew or take it over, so no pod
     * compacts until someone deletes it.
     *
     * @property content the body as stored.
     */
    data class Unreadable(
        override val region: String,
        val content: String,
    ) : CompactorLease
}

/**
 * Reads the account compactor's lease from the account bucket. It reads only: it never writes,
 * renews or breaks the lease. The bucket's region can differ from the profile's, so the lease is
 * read with a client for the bucket's own region.
 *
 * The age compares the lease's `renewedAt`, the holder's wall clock, with this workstation's; it is
 * for a person to read, and no decision is made on it. The lease agents themselves never compare
 * wall clocks.
 *
 * @param regionOf the region of a bucket.
 * @param leaseIn the lease document in a bucket, read in the given region; null when there is none.
 */
class CompactorLeaseReader(
    private val regionOf: (bucket: String) -> String,
    private val leaseIn: (region: String, bucket: String) -> String?,
    private val clock: Clock = Clock.System,
) {
    /**
     * The lease in [bucket]: [CompactorLease.None] when there is none, [CompactorLease.Unreadable]
     * when its body is not a lease.
     */
    fun read(bucket: String): CompactorLease {
        val region = regionOf(bucket)
        val lease = leaseIn(region, bucket) ?: return CompactorLease.None(region)
        // A body that is not JSON, not a lease, or a lease that breaks its own invariants all throw
        // IllegalArgumentException (SerializationException is one).
        val record =
            try {
                LeaseRecord.fromJson(lease)
            } catch (_: IllegalArgumentException) {
                return CompactorLease.Unreadable(region, lease)
            }
        return CompactorLease.Held(region, record, clock.now() - record.renewedAt)
    }

    companion object {
        /** Reads the lease from S3 with a client [clientIn] builds for the region, and closes the client. */
        fun fromS3(clientIn: (region: String) -> S3Client): (String, String) -> String? =
            { region, bucket -> clientIn(region).use { S3ConditionalObjects(it, bucket).read(LeaseRecord.KEY)?.content } }
    }
}
