package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.TenantDirectory
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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
     *   which carries none), or, when the SDK could not load credentials or another service refused
     *   them (an expired SSO session), that exception's code or class and its message.
     */
    data class Unreadable(
        val awsErrorCode: String,
    ) : StoreReadResult

    /**
     * S3 could not be reached at all: a read or connect timeout, a refused connection, a name that
     * did not resolve, or a TLS failure. Logging in again would not help, so it is not [Unreadable].
     *
     * @property reason the I/O error's class and message.
     */
    data class Unreachable(
        val reason: String,
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

/**
 * Picks the network failure out of an SDK exception, as the stack's store check (`store-check.sh`)
 * tells a `dial tcp`, `i/o timeout`, `no such host` or TLS failure apart from a credentials one. The
 * SDK wraps the HTTP client's I/O error in an `SdkClientException`; a credentials failure has no such
 * cause (a missing SSO token file is an `IOException`, but not one of these).
 */
internal object NetworkFailure {
    /**
     * What the HTTP client throws when S3 cannot be reached: a refused or reset connection
     * ([SocketException]), a name that did not resolve, a read or connect timeout (an
     * [InterruptedIOException], as `SocketTimeoutException` and Apache's `ConnectTimeoutException` are),
     * or TLS.
     */
    private val NETWORK: List<Class<out Throwable>> =
        listOf(
            SocketException::class.java,
            UnknownHostException::class.java,
            InterruptedIOException::class.java,
            SSLException::class.java,
        )

    /** The first network failure in [e]'s cause chain, [e] included; null when there is none. */
    fun causeOf(e: Throwable): Throwable? =
        generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSES)
            .firstOrNull { cause -> NETWORK.any { it.isInstance(cause) } }

    /** Enough for any SDK wrapping, and an end to a cycle of causes. */
    private const val MAX_CAUSES = 16
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
        } catch (e: SdkException) {
            when (val network = NetworkFailure.causeOf(e)) {
                null -> {
                    // The SDK could not load credentials (SdkClientException), or another service refused them:
                    // an expired SSO session is the SSO OIDC service's ExpiredTokenException.
                    val code = (e as? AwsServiceException)?.awsErrorDetails()?.errorCode() ?: e::class.simpleName
                    StoreReadResult.Unreadable("$code: ${e.message}")
                }
                else -> StoreReadResult.Unreachable("${network::class.simpleName}: ${network.message}")
            }
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
