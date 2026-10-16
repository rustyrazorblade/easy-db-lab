package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.TenantDirectory
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.S3Exception

/** What [LocalStackStore.check] found out about the account bucket. */
sealed interface StoreReadResult {
    /**
     * The profile can read the bucket and list its metrics root.
     *
     * @property region the bucket's region, which the stack's signer signs for.
     */
    data class Readable(
        val region: String,
    ) : StoreReadResult

    /**
     * The profile cannot read the bucket.
     *
     * @property awsErrorCode S3's error code (`AccessDenied`, `NoSuchBucket`, `HTTP 403` for a HEAD,
     *   which carries none), or the SDK's own reason when it could not load credentials at all.
     */
    data class Unreadable(
        val awsErrorCode: String,
    ) : StoreReadResult
}

/**
 * What `local create` reads from the account bucket with the operator's profile, before it writes
 * a stack: whether the bucket is readable at all, and the tenants Grafana gets datasources for. Both
 * only read.
 */
interface LocalStackStore {
    /**
     * Whether [bucket] is readable, and its region when it is, so missing or expired credentials
     * fail `local create` loudly instead of writing a stack that would show an empty store.
     */
    fun check(bucket: String): StoreReadResult

    /** Every tenant with a directory under `mimir/` in [bucket], read in its [region], and [home]. */
    fun tenants(
        bucket: String,
        region: String,
        home: String,
    ): TenantSet
}

/** An S3 client for one region, so a bucket is read in its own region and not the profile's. */
fun interface S3ClientForRegion {
    /** A new client for [region]; the caller closes it. */
    fun forRegion(region: String): S3Client
}

/**
 * [LocalStackStore] with the AWS SDK and the profile's credentials. The check is the bucket's region
 * ([BucketRegion], with the profile-region client), then a `HeadBucket` and a one-key list of `mimir/`
 * with a client for that region: the account bucket need not be in the profile's region, and S3
 * answers a `HeadBucket` sent to another region with a 301. The tenants come from [TenantDirectory],
 * as a cluster's do, with a client for the bucket's region too.
 *
 * @property s3 the profile's S3 client, used only to find the bucket's region.
 * @property clients makes the client for the bucket's region.
 * @property eventBus the event bus of the [S3ObjectStore] the tenant listing reads through.
 */
class SdkLocalStackStore(
    private val s3: S3Client,
    private val clients: S3ClientForRegion,
    private val eventBus: EventBus,
) : LocalStackStore {
    override fun check(bucket: String): StoreReadResult =
        try {
            val region = BucketRegion(s3).of(bucket)
            clients.forRegion(region).use { regional ->
                regional.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                regional.listObjectsV2(
                    ListObjectsV2Request
                        .builder()
                        .bucket(bucket)
                        .prefix("${Constants.Observability.METRICS_ROOT}/")
                        .maxKeys(1)
                        .build(),
                )
            }
            StoreReadResult.Readable(region)
        } catch (e: S3Exception) {
            StoreReadResult.Unreadable(e.awsErrorDetails()?.errorCode() ?: "HTTP ${e.statusCode()}")
        } catch (e: SdkClientException) {
            StoreReadResult.Unreadable("${e::class.simpleName}: ${e.message}")
        }

    override fun tenants(
        bucket: String,
        region: String,
        home: String,
    ): TenantSet =
        clients.forRegion(region).use { regional ->
            TenantDirectory(S3ObjectStore(regional, eventBus)).list(bucket, home)
        }
}
