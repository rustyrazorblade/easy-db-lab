package com.rustyrazorblade.easydblab.spark;

import com.google.common.collect.ImmutableMap;
import org.apache.cassandra.spark.transports.storage.StorageAccessConfiguration;
import org.apache.cassandra.spark.transports.storage.StorageCredentials;
import org.apache.cassandra.spark.transports.storage.extensions.CoordinationSignalListener;
import org.apache.cassandra.spark.transports.storage.extensions.CredentialChangeListener;
import org.apache.cassandra.spark.transports.storage.extensions.ObjectFailureListener;
import org.apache.cassandra.spark.transports.storage.extensions.StorageTransportConfiguration;
import org.apache.cassandra.spark.transports.storage.extensions.StorageTransportExtension;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Storage transport extension for easy-db-lab S3 bulk writes.
 *
 * <p>Implements the cassandra-analytics {@link StorageTransportExtension} interface to enable
 * S3_COMPAT transport mode for bulk writing data to multiple Cassandra clusters simultaneously.
 * Each DC is a separate easy-db-lab cluster with its own S3 data bucket. S3 replication copies
 * SSTable bundles from the primary write bucket to each DC's read bucket, where each cluster's
 * Sidecar imports the data.
 *
 * <h2>Required Configuration</h2>
 * <ul>
 *   <li>{@code spark.easydblab.s3.bucket} - S3 write bucket (primary)</li>
 *   <li>{@code spark.easydblab.s3.readBuckets} - Per-DC read buckets, format: {@code dc1:bucket1,dc2:bucket2}</li>
 * </ul>
 *
 * <h2>Optional Configuration</h2>
 * <ul>
 *   <li>{@code spark.easydblab.s3.endpoint} - Custom S3 endpoint URL</li>
 * </ul>
 *
 * <h2>Credentials</h2>
 * Uses AWS SDK {@link DefaultCredentialsProvider} to auto-detect credentials from the EMR
 * instance profile via IMDS. No explicit credential configuration is required.
 */
public class EasyDbLabStorageExtension implements StorageTransportExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(EasyDbLabStorageExtension.class);

    /**
     * Fully qualified class name for use in data_transport_extension_class option.
     */
    public static final String EXTENSION_CLASS_NAME =
        "com.rustyrazorblade.easydblab.spark.EasyDbLabStorageExtension";

    private static final String KEY_PREFIX = "bulkwrite";

    private static final String CREDENTIAL_SOURCES_HINT =
        "Ensure one of the following is configured:\n" +
        "  1. EC2 instance profile (IMDS) - recommended for EMR\n" +
        "  2. Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY\n" +
        "  3. System properties: aws.accessKeyId, aws.secretAccessKey\n" +
        "  4. AWS profile configuration (~/.aws/credentials)";

    private String jobId;
    private String writeBucket;
    private Map<String, String> readBuckets; // clusterId -> bucketName
    private String region;
    private String endpoint; // nullable
    private DefaultCredentialsProvider credentialsProvider;

    @Override
    public void initialize(String jobId, SparkConf conf, boolean isOnDriver) {
        this.jobId = jobId;

        if (!conf.contains(SparkJobConfig.PROP_S3_BUCKET)) {
            throw new IllegalArgumentException(
                "Required property not set: " + SparkJobConfig.PROP_S3_BUCKET + "\n" +
                "Usage: --conf " + SparkJobConfig.PROP_S3_BUCKET + "=<bucket-name>");
        }
        this.writeBucket = conf.get(SparkJobConfig.PROP_S3_BUCKET);
        validateBucketName(this.writeBucket);

        if (!conf.contains(SparkJobConfig.PROP_S3_READ_BUCKETS)) {
            throw new IllegalArgumentException(
                "Required property not set: " + SparkJobConfig.PROP_S3_READ_BUCKETS + "\n" +
                "Usage: --conf " + SparkJobConfig.PROP_S3_READ_BUCKETS + "=dc1:bucket1,dc2:bucket2\n" +
                "Each entry maps a cluster/DC ID to the S3 bucket that DC's Sidecar reads from.");
        }
        this.readBuckets = parseReadBuckets(conf.get(SparkJobConfig.PROP_S3_READ_BUCKETS));

        if (conf.contains(SparkJobConfig.PROP_S3_ENDPOINT)) {
            this.endpoint = conf.get(SparkJobConfig.PROP_S3_ENDPOINT);
        }

        try {
            Region detectedRegion = new DefaultAwsRegionProviderChain().getRegion();
            this.region = detectedRegion.id();
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new IllegalStateException(
                "Unable to detect AWS region. Set AWS_REGION environment variable or " +
                "aws.region system property, or ensure EC2 instance metadata is accessible.", e);
        }

        this.credentialsProvider = DefaultCredentialsProvider.create();

        try {
            credentialsProvider.resolveCredentials();
            LOGGER.debug("[{}] AWS credentials validated successfully during initialization", jobId);
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            String errorMsg = String.format(
                "[%s] Failed to resolve AWS credentials during initialization. " +
                "%s\nError: %s", jobId, CREDENTIAL_SOURCES_HINT, e.getMessage());
            throw new IllegalStateException(errorMsg, e);
        }

        LOGGER.info("[{}] Initialized EasyDbLabStorageExtension (isDriver: {})", jobId, isOnDriver);
        LOGGER.info("[{}] S3 Write Bucket: {}", jobId, writeBucket);
        LOGGER.info("[{}] S3 Read Buckets: {}", jobId, readBuckets);
        LOGGER.info("[{}] AWS Region: {}", jobId, region);
        if (endpoint != null) {
            LOGGER.info("[{}] Custom S3 Endpoint: {}", jobId, endpoint);
        }
    }

    @Override
    public StorageTransportConfiguration getStorageConfiguration() {
        StorageCredentials credentials = getAwsCredentials();

        Map<String, String> objectTags = endpoint != null
            ? ImmutableMap.of("endpoint", endpoint)
            : ImmutableMap.of();

        StorageAccessConfiguration writeConfig =
            new StorageAccessConfiguration(region, writeBucket, credentials);

        Map<String, StorageAccessConfiguration> readConfigs = new HashMap<>();
        for (Map.Entry<String, String> entry : readBuckets.entrySet()) {
            readConfigs.put(entry.getKey(),
                new StorageAccessConfiguration(region, entry.getValue(), credentials));
        }

        return new StorageTransportConfiguration(KEY_PREFIX, objectTags, writeConfig, readConfigs);
    }

    /**
     * Parses the {@code spark.easydblab.s3.readBuckets} property value into a map of
     * cluster ID to bucket name.
     *
     * @param value comma-separated {@code clusterId:bucketName} pairs
     * @return ordered map of clusterId to bucketName
     * @throws IllegalArgumentException if any entry is missing the colon separator
     */
    static Map<String, String> parseReadBuckets(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex < 0) {
                throw new IllegalArgumentException(
                    "Invalid entry in " + SparkJobConfig.PROP_S3_READ_BUCKETS + ": '" + trimmed + "'. " +
                    "Expected format: clusterId:bucketName (e.g. dc1:easy-db-lab-data-cluster1)");
            }
            String clusterId = trimmed.substring(0, colonIndex).trim();
            String bucket = trimmed.substring(colonIndex + 1).trim();
            result.put(clusterId, bucket);
        }
        return result;
    }

    /**
     * Obtain AWS credentials using the cached credentials provider.
     * On EMR, this retrieves credentials from the instance profile via IMDS.
     *
     * <p>The provider is cached (not the credentials themselves) so that session tokens
     * refresh automatically.
     */
    private StorageCredentials getAwsCredentials() {
        try {
            AwsCredentials awsCreds = credentialsProvider.resolveCredentials();

            String sessionToken = awsCreds instanceof AwsSessionCredentials
                ? ((AwsSessionCredentials) awsCreds).sessionToken()
                : null;

            LOGGER.debug("[{}] AWS credentials resolved successfully (session token: {})",
                jobId, sessionToken != null ? "present" : "none");

            return new StorageCredentials(
                awsCreds.accessKeyId(),
                awsCreds.secretAccessKey(),
                sessionToken
            );
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            String errorMsg = String.format(
                "[%s] Failed to resolve AWS credentials. %s\nError: %s",
                jobId, CREDENTIAL_SOURCES_HINT, e.getMessage());
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * Performs basic validation of S3 bucket name.
     */
    private void validateBucketName(String bucketName) {
        if (bucketName.isEmpty()) {
            throw new IllegalArgumentException(
                "S3 bucket name cannot be empty. " +
                "Specify with: --conf " + SparkJobConfig.PROP_S3_BUCKET + "=<bucket-name>");
        }

        if (bucketName.length() < 3 || bucketName.length() > 63) {
            throw new IllegalArgumentException(
                "S3 bucket name must be between 3 and 63 characters. Got: '" + bucketName +
                "' (" + bucketName.length() + " characters)");
        }
    }

    @Override
    public void onTransportStart(long elapsedMillis) {
        LOGGER.info("[{}] Bulk write transport started ({}ms)", jobId, elapsedMillis);
    }

    @Override
    public void onObjectPersisted(String bucket, String key, long sizeInBytes) {
        LOGGER.debug("[{}] Uploaded bundle: s3://{}/{} ({} bytes)",
            jobId, bucket, key, sizeInBytes);
    }

    @Override
    public void onAllObjectsPersisted(long objectsCount, long rowCount, long elapsedMillis) {
        LOGGER.info("[{}] All {} SSTable bundles uploaded ({} rows) in {}ms",
            jobId, objectsCount, rowCount, elapsedMillis);
    }

    @Override
    public void onStageSucceeded(String clusterId, long elapsedMillis) {
        LOGGER.info("[{}] Cluster '{}' staging succeeded in {}ms",
            jobId, clusterId, elapsedMillis);
    }

    @Override
    public void onStageFailed(String clusterId, Throwable cause) {
        LOGGER.error("[{}] Cluster '{}' staging failed",
            jobId, clusterId, cause);
    }

    @Override
    public void onImportSucceeded(String clusterId, long elapsedMillis) {
        LOGGER.info("[{}] Cluster '{}' import succeeded in {}ms",
            jobId, clusterId, elapsedMillis);
    }

    @Override
    public void onImportFailed(String clusterId, Throwable cause) {
        LOGGER.error("[{}] Cluster '{}' import failed",
            jobId, clusterId, cause);
    }

    @Override
    public void onJobSucceeded(long elapsedMillis) {
        LOGGER.info("[{}] Bulk write job SUCCEEDED (total: {}ms)", jobId, elapsedMillis);
        closeCredentialsProvider();
    }

    @Override
    public void onJobFailed(long elapsedMillis, Throwable throwable) {
        LOGGER.error("[{}] Bulk write job FAILED (total: {}ms)", jobId, elapsedMillis, throwable);
        closeCredentialsProvider();
    }

    private void closeCredentialsProvider() {
        if (credentialsProvider != null) {
            try {
                credentialsProvider.close();
                LOGGER.debug("[{}] Credentials provider closed", jobId);
            } catch (Exception e) {
                LOGGER.warn("[{}] Error closing credentials provider", jobId, e);
            }
        }
    }

    @Override
    public void setCoordinationSignalListener(CoordinationSignalListener listener) {
        // No-op: coordination signals not needed for easy-db-lab bulk write
    }

    @Override
    public void setCredentialChangeListener(CredentialChangeListener listener) {
        // No-op: credentials are static from instance profile
    }

    @Override
    public void setObjectFailureListener(ObjectFailureListener listener) {
        // No-op: object failures are handled by cassandra-analytics retry logic
    }

    @Override
    public void onObjectApplied(String bucket, String key, long sizeInBytes, long elapsedMillis) {
        // No-op: not tracking individual object application
    }
}
