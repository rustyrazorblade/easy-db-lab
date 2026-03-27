package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.transports.storage.extensions.StorageTransportConfiguration;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for EasyDbLabStorageExtension using LocalStack.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Extension initialization with LocalStack S3</li>
 *   <li>Configuration validation (required properties)</li>
 *   <li>Multi-DC coordinated write storage configuration</li>
 *   <li>Lifecycle event handling (no exceptions thrown)</li>
 *   <li>Error scenarios (missing/malformed config)</li>
 * </ul>
 *
 * <p><b>Note:</b> This uses TestContainers with LocalStack to avoid AWS costs
 * and provide a hermetic testing environment.
 */
@Testcontainers
class EasyDbLabStorageExtensionIntegrationTest {

    private static final String WRITE_BUCKET = "test-bulk-write-bucket";
    private static final String READ_BUCKET_DC1 = "test-read-bucket-dc1";
    private static final String READ_BUCKET_DC2 = "test-read-bucket-dc2";
    private static final String TEST_JOB_ID = "test-job-123";

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.0"))
        .withServices(Service.S3)
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).withStartupTimeout(Duration.ofMinutes(2)));

    private static S3Client s3Client;

    @BeforeAll
    static void setUp() {
        s3Client = S3Client.builder()
            .endpointOverride(localstack.getEndpoint())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(),
                        localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .build();

        for (String bucket : new String[]{WRITE_BUCKET, READ_BUCKET_DC1, READ_BUCKET_DC2}) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        }

        System.setProperty("aws.accessKeyId", localstack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localstack.getSecretKey());
        System.setProperty("aws.region", localstack.getRegion());
    }

    @AfterAll
    static void tearDown() {
        if (s3Client != null) {
            s3Client.close();
        }
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        System.clearProperty("aws.region");
    }

    @Test
    void initialize_withValidConfiguration_succeeds() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());
        assertThat(extension.getStorageConfiguration()).isNotNull();
    }

    @Test
    void initialize_withoutBucket_throwsIllegalArgumentException() {
        SparkConf conf = new SparkConf(false);
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();

        assertThatThrownBy(() -> extension.initialize(TEST_JOB_ID, conf, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_S3_BUCKET)
            .hasMessageContaining("Required property not set");
    }

    @Test
    void initialize_withoutReadBuckets_throwsIllegalArgumentException() {
        SparkConf conf = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, WRITE_BUCKET);
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();

        assertThatThrownBy(() -> extension.initialize(TEST_JOB_ID, conf, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_S3_READ_BUCKETS)
            .hasMessageContaining("Required property not set");
    }

    @Test
    void initialize_withCustomEndpoint_storesEndpoint() {
        String customEndpoint = "https://custom.s3.endpoint";
        EasyDbLabStorageExtension extension = createAndInitializeExtension(
            createConfigWithCustomEndpoint(customEndpoint));

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.getObjectTags()).containsEntry("endpoint", customEndpoint);
    }

    @Test
    void getStorageConfiguration_returnsValidConfiguration() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.getPrefix()).isEqualTo("bulkwrite");
        assertThat(config.writeAccessConfiguration()).isNotNull();
        assertThat(config.writeAccessConfiguration().bucket()).isEqualTo(WRITE_BUCKET);
    }

    @Test
    void getStorageConfiguration_multiDc_hasReadConfigPerDc() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.readAccessConfiguration("dc1")).isNotNull();
        assertThat(config.readAccessConfiguration("dc1").bucket()).isEqualTo(READ_BUCKET_DC1);
        assertThat(config.readAccessConfiguration("dc2")).isNotNull();
        assertThat(config.readAccessConfiguration("dc2").bucket()).isEqualTo(READ_BUCKET_DC2);
    }

    @Test
    void getStorageConfiguration_writeBucketIsSeparateFromReadBuckets() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.writeAccessConfiguration().bucket()).isEqualTo(WRITE_BUCKET);
        assertThat(config.readAccessConfiguration("dc1").bucket()).isEqualTo(READ_BUCKET_DC1);
        assertThat(config.readAccessConfiguration("dc2").bucket()).isEqualTo(READ_BUCKET_DC2);
    }

    @Test
    void getStorageConfiguration_withoutCustomEndpoint_hasNoObjectTags() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createMinimalConfig(), false);

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.getObjectTags()).isEmpty();
    }

    @Test
    void lifecycleEvents_executeWithoutErrors() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());

        extension.onTransportStart(100L);
        extension.onObjectPersisted(WRITE_BUCKET, "test-key", 1024L);
        extension.onAllObjectsPersisted(10L, 1000L, 5000L);
        extension.onStageSucceeded("dc1", 3000L);
        extension.onStageSucceeded("dc2", 3100L);
        extension.onImportSucceeded("dc1", 2000L);
        extension.onImportSucceeded("dc2", 2100L);
        extension.onJobSucceeded(10000L);

        extension.onStageFailed("dc1", new RuntimeException("Test failure"));
        extension.onImportFailed("dc1", new RuntimeException("Test import failure"));
        extension.onJobFailed(5000L, new RuntimeException("Test job failure"));
    }

    @Test
    void initialize_withEmptyBucketName_throwsIllegalArgumentException() {
        SparkConf conf = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, "");
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();

        assertThatThrownBy(() -> extension.initialize(TEST_JOB_ID, conf, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be empty");
    }

    @Test
    void initialize_withShortBucketName_throwsIllegalArgumentException() {
        SparkConf conf = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, "ab");
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();

        assertThatThrownBy(() -> extension.initialize(TEST_JOB_ID, conf, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 3 and 63 characters")
            .hasMessageContaining("2 characters");
    }

    @Test
    void initialize_withSixtyFourCharBucketName_throwsIllegalArgumentException() {
        String longBucketName = "a".repeat(64);
        SparkConf conf = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, longBucketName);
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();

        assertThatThrownBy(() -> extension.initialize(TEST_JOB_ID, conf, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 3 and 63 characters")
            .hasMessageContaining("64 characters");
    }

    @Test
    void initialize_detectsRegionFromSystemProperty() {
        EasyDbLabStorageExtension extension = createAndInitializeExtension(createConfigWithEndpoint());

        StorageTransportConfiguration config = extension.getStorageConfiguration();

        assertThat(config.writeAccessConfiguration().region()).isEqualTo(localstack.getRegion());
    }

    @Test
    void multipleExtensionInstances_canCoexist() {
        String altWriteBucket = WRITE_BUCKET + "-alt";
        s3Client.createBucket(CreateBucketRequest.builder().bucket(altWriteBucket).build());

        SparkConf conf1 = createConfigWithEndpoint();
        SparkConf conf2 = new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, altWriteBucket)
            .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:" + READ_BUCKET_DC1)
            .set(SparkJobConfig.PROP_S3_ENDPOINT, localstack.getEndpoint().toString());

        EasyDbLabStorageExtension extension1 = new EasyDbLabStorageExtension();
        EasyDbLabStorageExtension extension2 = new EasyDbLabStorageExtension();

        extension1.initialize("job-1", conf1, true);
        extension2.initialize("job-2", conf2, false);

        assertThat(extension1.getStorageConfiguration()).isNotNull();
        assertThat(extension2.getStorageConfiguration()).isNotNull();
    }

    // --- parseReadBuckets unit tests (no LocalStack needed) ---

    @Test
    void parseReadBuckets_singleEntry_returnsOneMapping() {
        Map<String, String> result = EasyDbLabStorageExtension.parseReadBuckets("dc1:bucket-dc1");

        assertThat(result).hasSize(1).containsEntry("dc1", "bucket-dc1");
    }

    @Test
    void parseReadBuckets_multipleEntries_returnsAllMappings() {
        Map<String, String> result = EasyDbLabStorageExtension.parseReadBuckets(
            "dc1:bucket-dc1,dc2:bucket-dc2");

        assertThat(result)
            .hasSize(2)
            .containsEntry("dc1", "bucket-dc1")
            .containsEntry("dc2", "bucket-dc2");
    }

    @Test
    void parseReadBuckets_withWhitespace_trimsEntries() {
        Map<String, String> result = EasyDbLabStorageExtension.parseReadBuckets(
            " dc1 : bucket-dc1 , dc2 : bucket-dc2 ");

        assertThat(result)
            .containsEntry("dc1", "bucket-dc1")
            .containsEntry("dc2", "bucket-dc2");
    }

    @Test
    void parseReadBuckets_missingColon_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> EasyDbLabStorageExtension.parseReadBuckets("dc1bucket1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_S3_READ_BUCKETS)
            .hasMessageContaining("dc1bucket1")
            .hasMessageContaining("clusterId:bucketName");
    }

    @Test
    void parseReadBuckets_secondEntryMalformed_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> EasyDbLabStorageExtension.parseReadBuckets("dc1:bucket1,dc2bucket2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dc2bucket2");
    }

    // Helper methods

    private SparkConf createMinimalConfig() {
        return new SparkConf(false)
            .set(SparkJobConfig.PROP_S3_BUCKET, WRITE_BUCKET)
            .set(SparkJobConfig.PROP_S3_READ_BUCKETS,
                "dc1:" + READ_BUCKET_DC1 + ",dc2:" + READ_BUCKET_DC2);
    }

    private SparkConf createConfigWithEndpoint() {
        return createMinimalConfig()
            .set(SparkJobConfig.PROP_S3_ENDPOINT, localstack.getEndpoint().toString());
    }

    private SparkConf createConfigWithCustomEndpoint(String endpoint) {
        return createMinimalConfig()
            .set(SparkJobConfig.PROP_S3_ENDPOINT, endpoint);
    }

    private EasyDbLabStorageExtension createAndInitializeExtension(SparkConf conf) {
        return createAndInitializeExtension(conf, true);
    }

    private EasyDbLabStorageExtension createAndInitializeExtension(SparkConf conf, boolean isDriver) {
        EasyDbLabStorageExtension extension = new EasyDbLabStorageExtension();
        extension.initialize(TEST_JOB_ID, conf, isDriver);
        return extension;
    }
}
