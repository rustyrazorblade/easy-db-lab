package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SDK never retries a conditional PUT. A retry of a write that landed would get 412 and report
 * a lost race for a write that won, so the holder would give up a lease it holds.
 *
 * The client runs its full request pipeline, retries included, against an in-process HTTP client that
 * answers every request with 500, a status the SDK's standard strategy retries. The GET case shows
 * the stub sees those retries, so one PUT means the PUT was not retried.
 */
class S3ConditionalObjectsRetryTest {
    /** Answers every request with 500 and records each method it was sent. */
    private class FailingHttp : SdkHttpClient {
        val methods = CopyOnWriteArrayList<SdkHttpMethod>()

        override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
            methods += request.httpRequest().method()
            return object : ExecutableHttpRequest {
                override fun call(): HttpExecuteResponse =
                    HttpExecuteResponse
                        .builder()
                        .response(SdkHttpResponse.builder().statusCode(INTERNAL_ERROR).build())
                        .responseBody(AbortableInputStream.create(ByteArrayInputStream(ERROR_BODY.toByteArray())))
                        .build()

                override fun abort() = Unit
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val INTERNAL_ERROR = 500
        const val ERROR_BODY = "<Error><Code>InternalError</Code><Message>try again</Message></Error>"
    }

    private val http = FailingHttp()
    private val s3 =
        S3Client
            .builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI("http://s3.test"))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("id", "secret")))
            .httpClient(http)
            .build()
    private val store = S3ConditionalObjects(s3, "bucket")

    @AfterEach
    fun close() = s3.close()

    private fun puts() = http.methods.count { it == SdkHttpMethod.PUT }

    @Test
    fun `a create that S3 answers with 500 is sent exactly once`() {
        assertThatThrownBy { store.createIfAbsent("k", "c") }.isInstanceOf(S3Exception::class.java)

        assertThat(puts()).isEqualTo(1)
    }

    @Test
    fun `a replace that S3 answers with 500 is sent exactly once`() {
        assertThatThrownBy { store.replaceIfVersionMatches("k", "c", ObjectVersion("\"etag\"")) }
            .isInstanceOf(S3Exception::class.java)

        assertThat(puts()).isEqualTo(1)
    }

    @Test
    fun `a read that S3 answers with 500 is retried, so the stub sees the SDK's retries`() {
        assertThatThrownBy { store.read("k") }.isInstanceOf(S3Exception::class.java)

        assertThat(http.methods.count { it == SdkHttpMethod.GET }).isGreaterThan(1)
    }
}
