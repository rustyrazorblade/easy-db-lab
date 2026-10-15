package com.rustyrazorblade.easydblab

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.URI

/**
 * One JVM-wide MinIO container for the tests that need S3's conditional writes.
 *
 * LocalStack 3.0, which [SharedLocalStack] runs, accepts a PUT with `If-None-Match: *` or a
 * stale `If-Match` and overwrites the object, so a test of a conditional write passes against it
 * whether or not the write is conditional. MinIO honours both headers as S3 does, answering 412.
 * Like [SharedLocalStack], the container starts lazily on first use and is reaped with the JVM;
 * tests share it, so each one uses its own bucket or keys.
 */
object SharedMinio {
    private const val IMAGE = "minio/minio:RELEASE.2024-12-18T13-15-44Z"
    private const val PORT = 9000
    private const val USER = "minioadmin"
    private const val PASSWORD = "minioadmin"

    private val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(PORT).forStatusCode(200))
            .apply { start() }
    }

    /** An S3 client for the shared container, with path-style access. */
    fun s3Client(): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create("http://${container.host}:${container.getMappedPort(PORT)}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
            .forcePathStyle(true)
            .build()

    /** Creates [bucket], tolerating a bucket another test class already made. */
    fun createBucketIfMissing(
        s3Client: S3Client,
        bucket: String,
    ) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        } catch (_: BucketAlreadyOwnedByYouException) {
            // Made earlier in this JVM.
        }
    }
}
