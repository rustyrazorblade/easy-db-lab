package com.rustyrazorblade.easydblab.compactorlease

import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.SdkPlugin
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * [ConditionalObjects] on S3, with conditional PUT: `If-None-Match: *` to create and
 * `If-Match: <ETag>` to replace. The version is the object's ETag.
 *
 * S3 answers a lost race with 412 (precondition failed) or 409 (a conflicting conditional request
 * in flight), and a replace of a missing key with 404. Those are the lost results; every other
 * failure throws. The SDK's own retries are turned off for both PUTs, because a retry of a write
 * that landed gets 412 and would report a lost race for a write that won.
 *
 * @property s3 a client for the bucket's region.
 * @property bucket the bucket holding every key.
 */
class S3ConditionalObjects(
    private val s3: S3Client,
    private val bucket: String,
) : ConditionalObjects {
    internal companion object {
        const val PRECONDITION_FAILED = 412
        const val CONFLICT = 409
        const val NOT_FOUND = 404
        const val ANY_OBJECT = "*"

        /** Turns the SDK's retries off for the request it is added to. */
        val NO_RETRIES =
            SdkPlugin { config ->
                config.overrideConfiguration { it.retryStrategy(AwsRetryStrategy.doNotRetry()) }
            }

        /** Whether [e] is S3 refusing a conditional write because the condition did not hold. */
        fun isLostRace(e: S3Exception): Boolean = e.statusCode() == PRECONDITION_FAILED || e.statusCode() == CONFLICT
    }

    override fun read(key: String): VersionedObject? =
        try {
            val response =
                s3.getObject(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                    ResponseTransformer.toBytes(),
                )
            VersionedObject(response.asUtf8String(), ObjectVersion(response.response().eTag()))
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun createIfAbsent(
        key: String,
        content: String,
    ): CreateResult =
        try {
            CreateResult.Created(put(key, content) { it.ifNoneMatch(ANY_OBJECT) })
        } catch (e: S3Exception) {
            if (!isLostRace(e)) throw e
            CreateResult.AlreadyExists
        }

    override fun replaceIfVersionMatches(
        key: String,
        content: String,
        expected: ObjectVersion,
    ): ReplaceResult =
        try {
            ReplaceResult.Replaced(put(key, content) { it.ifMatch(expected.token) })
        } catch (e: S3Exception) {
            if (!isLostRace(e) && e.statusCode() != NOT_FOUND) throw e
            ReplaceResult.VersionMismatch
        }

    private fun put(
        key: String,
        content: String,
        condition: (PutObjectRequest.Builder) -> PutObjectRequest.Builder,
    ): ObjectVersion {
        val request =
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/json")
                .overrideConfiguration { it.addPlugin(NO_RETRIES) }
                .let(condition)
                .build()
        return ObjectVersion(s3.putObject(request, RequestBody.fromString(content)).eTag())
    }
}
