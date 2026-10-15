package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.SharedMinio
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.ObjectStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import java.util.UUID

/**
 * The conditional writes of [S3ObjectStore] against a real S3-compatible store, MinIO, which
 * honours `If-None-Match` and `If-Match` the way S3 does. Each test writes under its own key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ObjectStoreConditionalWriteIntegrationTest {
    private companion object {
        private const val BUCKET = "conditional-writes"
    }

    private lateinit var s3Client: S3Client
    private lateinit var objectStore: S3ObjectStore

    @BeforeAll
    fun setup() {
        s3Client = SharedMinio.s3Client()
        SharedMinio.createBucketIfMissing(s3Client, BUCKET)
        objectStore = S3ObjectStore(s3Client, EventBus())
    }

    private fun freshPath(): ClusterS3Path = ClusterS3Path.fromKey(BUCKET, "cond/${UUID.randomUUID()}/obj")

    private fun bytesAt(path: ClusterS3Path): ByteArray =
        s3Client
            .getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(path.getKey())
                    .build(),
            ).asByteArray()

    private fun contentTypeAt(path: ClusterS3Path): String =
        s3Client
            .headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(path.getKey())
                    .build(),
            ).contentType()

    @Test
    fun `putIfAbsent writes a missing object with its bytes and content type`() {
        val path = freshPath()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x7f)

        val result = objectStore.putIfAbsent(bytes, path, "application/zip")

        assertThat(result).isEqualTo(ObjectStore.Written)
        assertThat(bytesAt(path)).isEqualTo(bytes)
        assertThat(contentTypeAt(path)).isEqualTo("application/zip")
    }

    @Test
    fun `putIfAbsent never overwrites an existing object`() {
        val path = freshPath()
        objectStore.putIfAbsent("first".toByteArray(), path, "text/plain")

        val result = objectStore.putIfAbsent("second".toByteArray(), path, "text/plain")

        assertThat(result).isEqualTo(ObjectStore.AlreadyExists)
        assertThat(String(bytesAt(path))).isEqualTo("first")
    }

    @Test
    fun `readVersioned returns the content and an etag, or null for a missing object`() {
        val path = freshPath()
        assertThat(objectStore.readVersioned(path)).isNull()

        objectStore.putIfAbsent("pointer".toByteArray(), path, "text/html")

        val versioned = objectStore.readVersioned(path)
        assertThat(versioned?.content).isEqualTo("pointer")
        assertThat(versioned?.etag).isNotBlank()
    }

    @Test
    fun `putIfMatch replaces the object when the etag is current`() {
        val path = freshPath()
        objectStore.putIfAbsent("old".toByteArray(), path, "text/html")
        val etag = checkNotNull(objectStore.readVersioned(path)).etag

        val result = objectStore.putIfMatch("new".toByteArray(), path, etag, "text/html")

        assertThat(result).isEqualTo(ObjectStore.Written)
        assertThat(String(bytesAt(path))).isEqualTo("new")
        assertThat(contentTypeAt(path)).isEqualTo("text/html")
    }

    @Test
    fun `putIfMatch refuses a stale etag and keeps the other writer's content`() {
        val path = freshPath()
        objectStore.putIfAbsent("v1".toByteArray(), path, "text/html")
        val stale = checkNotNull(objectStore.readVersioned(path)).etag
        objectStore.putIfMatch("v2".toByteArray(), path, stale, "text/html")

        val result = objectStore.putIfMatch("v3".toByteArray(), path, stale, "text/html")

        assertThat(result).isEqualTo(ObjectStore.PreconditionFailed)
        assertThat(String(bytesAt(path))).isEqualTo("v2")
    }
}
