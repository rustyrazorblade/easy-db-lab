package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.services.ConditionalObjectStore
import com.rustyrazorblade.easydblab.services.ObjectStore
import com.rustyrazorblade.easydblab.services.aws.S3ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.nio.file.Path

/**
 * Unit tests for S3ObjectStore error handling behavior that can't be tested with LocalStack.
 *
 * For core CRUD operations, see S3ObjectStoreIntegrationTest which uses LocalStack.
 */
class S3ObjectStoreTest : BaseKoinTest() {
    private lateinit var mockS3Client: S3Client
    private lateinit var objectStore: ObjectStore

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<S3Client>().also {
                        mockS3Client = it
                    }
                }
                single<ObjectStore> { S3ObjectStore(get(), get()) }
            },
        )

    @Test
    fun `uploadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        val testFile = tempDir.resolve("test.jar").toFile()
        testFile.writeText("test content")
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<Path>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Access Denied")
                    .statusCode(403)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.uploadFile(testFile, s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `fileExists throws exception on S3 server error`() {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Service Unavailable")
                    .statusCode(503)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.fileExists(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `getFileInfo throws exception on S3 server error`() {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")

        objectStore = get()

        whenever(mockS3Client.headObject(any<HeadObjectRequest>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Internal Server Error")
                    .statusCode(500)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.getFileInfo(s3Path)
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `downloadFile rethrows SdkClientException`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("downloaded.jar")

        objectStore = get()

        val sdkException =
            SdkClientException
                .builder()
                .message("Some other error")
                .build()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenThrow(sdkException)

        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(SdkClientException::class.java)
    }

    @Test
    fun `downloadFile throws exception on S3 error`(
        @TempDir tempDir: Path,
    ) {
        val s3Path = ClusterS3Path.root("test-bucket").spark().resolve("test.jar")
        val localPath = tempDir.resolve("downloaded.jar")

        objectStore = get()

        whenever(mockS3Client.getObject(any<GetObjectRequest>(), any<Path>()))
            .thenThrow(
                S3Exception
                    .builder()
                    .message("Internal Server Error")
                    .statusCode(500)
                    .build(),
            )

        assertThatThrownBy {
            objectStore.downloadFile(s3Path, localPath)
        }.isInstanceOf(S3Exception::class.java)
    }

    private fun s3Error(
        status: Int,
        code: String? = null,
        sdkAttempts: Int? = null,
    ): S3Exception =
        S3Exception
            .builder()
            .message("status $status")
            .statusCode(status)
            .numAttempts(sdkAttempts)
            .apply {
                code?.let {
                    awsErrorDetails(
                        AwsErrorDetails
                            .builder()
                            .errorCode(it)
                            .serviceName("S3")
                            .build(),
                    )
                }
            }.build() as S3Exception

    private val path = ClusterS3Path.fromKey("b", "k")

    private fun putFails(vararg errors: S3Exception) {
        objectStore = get()
        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<RequestBody>())).thenThrow(*errors)
    }

    private fun putIfAbsent() = objectStore.putIfAbsent("x".toByteArray(), path, "text/plain")

    private fun putIfMatch() = objectStore.putIfMatch("x".toByteArray(), path, "\"e\"", "text/html")

    private fun putCalls(): Int = mockingDetails(mockS3Client).invocations.count { it.method.name == "putObject" }

    @Test
    fun `putIfAbsent refused with 412 is AlreadyExists after one request`() {
        putFails(s3Error(412, "PreconditionFailed"))

        assertThat(putIfAbsent()).isEqualTo(ConditionalObjectStore.AlreadyExists)
        assertThat(putCalls()).isEqualTo(1)
    }

    @Test
    fun `putIfAbsent losing to a concurrent conditional write is AlreadyExists after one request`() {
        putFails(s3Error(409, "ConditionalRequestConflict"))

        assertThat(putIfAbsent()).isEqualTo(ConditionalObjectStore.AlreadyExists)
        assertThat(putCalls()).isEqualTo(1)
    }

    @Test
    fun `putIfMatch refused with 412 is PreconditionFailed after one request`() {
        putFails(s3Error(412, "PreconditionFailed"))

        assertThat(putIfMatch()).isEqualTo(ConditionalObjectStore.PreconditionFailed)
        assertThat(putCalls()).isEqualTo(1)
    }

    @Test
    fun `putIfMatch reports a removed object as PreconditionFailed`() {
        putFails(s3Error(404, "NoSuchKey"))

        assertThat(putIfMatch()).isEqualTo(ConditionalObjectStore.PreconditionFailed)
        assertThat(putCalls()).isEqualTo(1)
    }

    @Test
    fun `putIfMatch on a 404 with no error code is an error naming the key and status`() {
        putFails(s3Error(404))

        assertThatThrownBy { putIfMatch() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("s3://b/k")
            .hasMessageContaining("unknown")
            .hasMessageContaining("HTTP 404")
    }

    @Test
    fun `putIfMatch on a 404 with an unknown error code is an error naming the key, code and status`() {
        putFails(s3Error(404, "NoSuchUpload"))

        assertThatThrownBy { putIfMatch() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("s3://b/k")
            .hasMessageContaining("NoSuchUpload")
            .hasMessageContaining("HTTP 404")
    }

    @Test
    fun `putIfAbsent on a missing bucket is an error naming the S3 error code`() {
        putFails(s3Error(404, "NoSuchBucket"))

        assertThatThrownBy { putIfAbsent() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("NoSuchBucket")
            .hasMessageContaining("s3://b/k")
    }

    @Test
    fun `putIfMatch on a missing bucket is an error, not a removed object`() {
        putFails(s3Error(404, "NoSuchBucket"))

        assertThatThrownBy { putIfMatch() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("NoSuchBucket")
    }

    @Test
    fun `a 409 that is not a conditional conflict is an error naming the S3 error code`() {
        putFails(s3Error(409, "OperationAborted"))

        assertThatThrownBy { putIfAbsent() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("OperationAborted")
        assertThatThrownBy { putIfMatch() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("OperationAborted")
    }

    @Test
    fun `a conditional write that S3 denies is an error naming the S3 error code`() {
        putFails(s3Error(403, "AccessDenied"))

        assertThatThrownBy { putIfAbsent() }
            .isInstanceOf(S3Exception::class.java)
            .hasMessageContaining("AccessDenied")
            .satisfies({ assertThat((it as S3Exception).statusCode()).isEqualTo(403) })
    }

    @Test
    fun `an error on the first attempt says one attempt was made`() {
        putFails(s3Error(403, "AccessDenied"))

        assertThatThrownBy { putIfAbsent() }.hasMessageContaining("attempts: 1")
    }

    @Test
    fun `an error after a server-error retry says how many attempts were made`() {
        objectStore = get()
        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()))
            .thenThrow(s3Error(500, "InternalError"))
            .thenThrow(s3Error(403, "AccessDenied"))

        assertThatThrownBy { putIfAbsent() }.hasMessageContaining("attempts: 2")
    }

    @Test
    fun `an error on an attempt the SDK retried itself counts the SDK's attempts`() {
        putFails(s3Error(403, "AccessDenied", sdkAttempts = 3))

        assertThatThrownBy { putIfMatch() }.hasMessageContaining("attempts: 3")
    }

    @Test
    fun `a conditional write that fails with a server error is retried and written`() {
        objectStore = get()
        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()))
            .thenThrow(s3Error(500, "InternalError"))
            .thenReturn(PutObjectResponse.builder().build())

        assertThat(putIfAbsent()).isEqualTo(ConditionalObjectStore.Written)
        assertThat(putCalls()).isEqualTo(2)
    }

    @Test
    fun `a refusal after a server-error retry is the same refusal as on a first attempt`() {
        objectStore = get()
        whenever(mockS3Client.putObject(any<PutObjectRequest>(), any<RequestBody>()))
            .thenThrow(s3Error(500, "InternalError"))
            .thenThrow(s3Error(412, "PreconditionFailed"))
            .thenThrow(s3Error(503, "SlowDown"))
            .thenThrow(s3Error(409, "ConditionalRequestConflict"))

        assertThat(putIfAbsent()).isEqualTo(ConditionalObjectStore.AlreadyExists)
        assertThat(putIfMatch()).isEqualTo(ConditionalObjectStore.PreconditionFailed)
        assertThat(putCalls()).isEqualTo(4)
    }

    @Test
    fun `a refusal on an attempt the SDK retried itself is the same refusal and is not retried again`() {
        putFails(s3Error(409, "ConditionalRequestConflict", sdkAttempts = 2))

        assertThat(putIfAbsent()).isEqualTo(ConditionalObjectStore.AlreadyExists)
        assertThat(putIfMatch()).isEqualTo(ConditionalObjectStore.PreconditionFailed)
        assertThat(putCalls()).isEqualTo(2)
    }
}
