package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.transports.storage.extensions.CoordinationSignalListener;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@Testcontainers
class EasyDbLabIamStorageExtensionIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String WRITE_BUCKET = "write-bucket";
    // Separate read bucket so the polling path is always exercised (same bucket → skip poll).
    private static final String READ_BUCKET = "read-bucket";

    @Container
    static final LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(S3);

    private static S3Client s3;

    @BeforeAll
    static void setUp() throws Exception {
        // AWS SDK v2 picks these up via SystemPropertyCredentialsProvider /
        // SystemSettingsRegionProvider — LocalStack accepts any credentials.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        System.setProperty("aws.region", REGION);

        s3 = S3Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(localstack.getEndpointOverride(S3))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();

        s3.createBucket(CreateBucketRequest.builder().bucket(WRITE_BUCKET).build());
        s3.createBucket(CreateBucketRequest.builder().bucket(READ_BUCKET).build());
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        System.clearProperty("aws.region");
        if (s3 != null) s3.close();
    }

    private EasyDbLabIamStorageExtension initializedExtension() {
        EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
        SparkConf conf = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, WRITE_BUCKET)
            .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:" + READ_BUCKET)
            .set(SparkJobConfig.PROP_S3_ENDPOINT,
                localstack.getEndpointOverride(S3).toString());
        ext.initialize("test-job", conf, true);
        return ext;
    }

    @Test
    void onAllObjectsPersisted_keyAlreadyInReadBucket_signalsImmediately()
            throws InterruptedException {
        String key = "bulkwrite/bundle-present-" + System.currentTimeMillis() + ".db";

        s3.putObject(
            PutObjectRequest.builder().bucket(READ_BUCKET).key(key).build(),
            RequestBody.fromString("probe"));

        EasyDbLabIamStorageExtension ext = initializedExtension();
        CountDownLatch latch = new CountDownLatch(1);
        List<String> signaled = new CopyOnWriteArrayList<>();
        ext.setCoordinationSignalListener(new CoordinationSignalListener() {
            public void onStageReady(String dcId) { signaled.add(dcId); latch.countDown(); }
            public void onImportReady(String dcId) {}
        });

        ext.onObjectPersisted(WRITE_BUCKET, key, 100);
        ext.onAllObjectsPersisted(1, 100, 1000);

        assertThat(latch.await(10, TimeUnit.SECONDS))
            .as("onStageReady should be called within 10s when key is already present")
            .isTrue();
        assertThat(signaled).containsExactly("test-job");
    }

    @Test
    void onAllObjectsPersisted_keyAbsent_pollsUntilKeyAppears()
            throws InterruptedException {
        String key = "bulkwrite/bundle-delayed-" + System.currentTimeMillis() + ".db";

        EasyDbLabIamStorageExtension ext = initializedExtension();
        CountDownLatch latch = new CountDownLatch(1);
        List<String> signaled = new CopyOnWriteArrayList<>();
        ext.setCoordinationSignalListener(new CoordinationSignalListener() {
            public void onStageReady(String dcId) { signaled.add(dcId); latch.countDown(); }
            public void onImportReady(String dcId) {}
        });

        ext.onObjectPersisted(WRITE_BUCKET, key, 100);
        ext.onAllObjectsPersisted(1, 100, 1000);

        // Polling thread does HeadObject → 404, sleeps 5s, then retries.
        // Upload the key after 2s so it's present on the next poll.
        Thread.sleep(2_000);
        s3.putObject(
            PutObjectRequest.builder().bucket(READ_BUCKET).key(key).build(),
            RequestBody.fromString("probe"));

        assertThat(latch.await(20, TimeUnit.SECONDS))
            .as("onStageReady should be called within 20s after key appears in read bucket")
            .isTrue();
        assertThat(signaled).containsExactly("test-job");
    }
}
