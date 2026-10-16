package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.services.TenantDirectory
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
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

    /** Every tenant with a directory under `mimir/` in [bucket], and [home]. */
    fun tenants(
        bucket: String,
        home: String,
    ): TenantSet
}

/**
 * [LocalStackStore] with the AWS SDK and the profile's credentials. The check is the bucket's region,
 * a `HeadBucket`, and a one-key list of `mimir/`, the tenant directories the stack's datasources are
 * built from; the tenants come from [TenantDirectory], as a cluster's do.
 *
 * @property s3 the profile's S3 client.
 * @property tenantDirectory the listing of the tenant directories.
 */
class SdkLocalStackStore(
    private val s3: S3Client,
    private val tenantDirectory: TenantDirectory,
) : LocalStackStore {
    override fun check(bucket: String): StoreReadResult =
        try {
            val region = BucketRegion(s3).of(bucket)
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            s3.listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix("${Constants.Observability.METRICS_ROOT}/")
                    .maxKeys(1)
                    .build(),
            )
            StoreReadResult.Readable(region)
        } catch (e: S3Exception) {
            StoreReadResult.Unreadable(e.awsErrorDetails()?.errorCode() ?: "HTTP ${e.statusCode()}")
        } catch (e: SdkClientException) {
            StoreReadResult.Unreadable("${e::class.simpleName}: ${e.message}")
        }

    override fun tenants(
        bucket: String,
        home: String,
    ): TenantSet = tenantDirectory.list(bucket, home)
}
