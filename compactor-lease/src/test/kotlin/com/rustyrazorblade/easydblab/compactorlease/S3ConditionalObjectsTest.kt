package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * How [S3ConditionalObjects] reads S3's answers to a conditional PUT. S3 answers a lost race with
 * 412 or 409, and a replace of a missing key with 404; LocalStack never answers 409, so each
 * status is simulated here. The requests themselves run against LocalStack in the root module's
 * contract test.
 */
class S3ConditionalObjectsTest {
    private val s3 = mock<S3Client>()
    private val store = S3ConditionalObjects(s3, "bucket")
    private val version = ObjectVersion("\"etag\"")

    private fun s3Fails(
        status: Int,
        errorCode: String? = null,
    ) {
        val failure =
            S3Exception
                .builder()
                .statusCode(status)
                .message("status $status")
                .apply { errorCode?.let { awsErrorDetails(AwsErrorDetails.builder().errorCode(it).build()) } }
                .build() as S3Exception
        whenever(s3.putObject(any<PutObjectRequest>(), any<RequestBody>())).thenThrow(failure)
    }

    @Test
    fun `a create that S3 refuses with 412 already exists`() {
        s3Fails(412)

        assertThat(store.createIfAbsent("k", "c")).isEqualTo(CreateResult.AlreadyExists)
    }

    @Test
    fun `a create that S3 refuses with 409 already exists`() {
        s3Fails(409)

        assertThat(store.createIfAbsent("k", "c")).isEqualTo(CreateResult.AlreadyExists)
    }

    @Test
    fun `a replace that S3 refuses with 412 is a version mismatch`() {
        s3Fails(412)

        assertThat(store.replaceIfVersionMatches("k", "c", version)).isEqualTo(ReplaceResult.VersionMismatch)
    }

    @Test
    fun `a replace that S3 refuses with 409 is a version mismatch`() {
        s3Fails(409)

        assertThat(store.replaceIfVersionMatches("k", "c", version)).isEqualTo(ReplaceResult.VersionMismatch)
    }

    @Test
    fun `a replace of a missing key is a version mismatch`() {
        s3Fails(404, "NoSuchKey")

        assertThat(store.replaceIfVersionMatches("k", "c", version)).isEqualTo(ReplaceResult.VersionMismatch)
    }

    @Test
    fun `a create that fails any other way throws`() {
        s3Fails(503)

        assertThatThrownBy { store.createIfAbsent("k", "c") }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `a replace that fails any other way throws`() {
        s3Fails(403)

        assertThatThrownBy { store.replaceIfVersionMatches("k", "c", version) }.isInstanceOf(S3Exception::class.java)
    }

    /** A wrong or deleted bucket is not a lost race: the holder must not log "lease lost" for it. */
    @Test
    fun `a replace into a missing bucket throws`() {
        s3Fails(404, "NoSuchBucket")

        assertThatThrownBy { store.replaceIfVersionMatches("k", "c", version) }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `a create into a missing bucket throws`() {
        s3Fails(404, "NoSuchBucket")

        assertThatThrownBy { store.createIfAbsent("k", "c") }.isInstanceOf(S3Exception::class.java)
    }
}
