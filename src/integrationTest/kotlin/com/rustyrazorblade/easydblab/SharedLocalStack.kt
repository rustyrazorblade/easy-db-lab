// Testcontainers 2.x deprecated `org.testcontainers.containers.localstack.LocalStackContainer`
// in favour of `org.testcontainers.localstack.LocalStackContainer`, whose API differs (the
// `Service` enum is gone in favour of `withServices(String...)`, and per-service
// `getEndpointOverride(Service)` is replaced by a single `getEndpoint()`). That migration is a
// behavioural change that can only be validated against a running LocalStack container, so it is
// deferred rather than guessed at here. Suppress the deprecation for this file until the wrapper
// is migrated deliberately.
@file:Suppress("DEPRECATION")

package com.rustyrazorblade.easydblab

import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.sts.StsClient

/**
 * Single, JVM-wide LocalStack container shared by every AWS integration test.
 *
 * Historically each AWS integration test class started its own
 * `localstack/localstack:3.0` container in a `@Container` companion field, which
 * meant up to six LocalStack startups per integration-test run — slow and wasteful.
 * This object applies the Testcontainers "singleton container" pattern: the
 * container is constructed and started exactly once (lazily, on first access) and
 * reused across all test classes in the JVM.
 *
 * It is deliberately NOT annotated with `@Testcontainers`/`@Container`. That
 * machinery stops the container at the end of the owning test class, which would
 * defeat sharing. Instead we rely on the Testcontainers Ryuk sidecar and the JVM
 * shutdown hook to reap the container when the JVM exits — so this object never
 * calls `.stop()`.
 *
 * The container enables the union of services the six tests need — S3, STS, and
 * EC2 — and exposes ready-to-use AWS SDK clients (each pointed at the matching
 * LocalStack endpoint with the container's credentials). Because one EC2 and one
 * S3 backend are now shared across classes, tests must scope their assertions to
 * the resources they create rather than asserting on global container state.
 */
object SharedLocalStack {
    private val container: LocalStackContainer by lazy {
        LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(Service.S3, Service.STS, Service.EC2)
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200))
            .apply { start() }
    }

    /**
     * Credentials provider backed by the shared container's access/secret keys.
     */
    fun credentialsProvider(): StaticCredentialsProvider =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(container.accessKey, container.secretKey),
        )

    /**
     * S3 client pointed at the shared container. Path-style access is forced so
     * bucket names resolve without DNS-style virtual hosting against LocalStack.
     */
    fun s3Client(): S3Client =
        S3Client
            .builder()
            .endpointOverride(container.getEndpointOverride(Service.S3))
            .region(Region.of(container.region))
            .credentialsProvider(credentialsProvider())
            .forcePathStyle(true)
            .build()

    /**
     * STS client pointed at the shared container.
     */
    fun stsClient(): StsClient =
        StsClient
            .builder()
            .endpointOverride(container.getEndpointOverride(Service.STS))
            .region(Region.of(container.region))
            .credentialsProvider(credentialsProvider())
            .build()

    /**
     * EC2 client pointed at the shared container.
     */
    fun ec2Client(): Ec2Client =
        Ec2Client
            .builder()
            .endpointOverride(container.getEndpointOverride(Service.EC2))
            .region(Region.of(container.region))
            .credentialsProvider(credentialsProvider())
            .build()

    /**
     * Creates the given bucket, tolerating the case where a co-tenant test class
     * already created it in the shared S3 backend. Use a class-unique bucket name
     * to avoid cross-class interference on bucket contents.
     */
    fun createBucketIfMissing(
        s3Client: S3Client,
        bucket: String,
    ) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        } catch (_: BucketAlreadyOwnedByYouException) {
            // Bucket already exists from an earlier call in this JVM; nothing to do.
        }
    }
}
