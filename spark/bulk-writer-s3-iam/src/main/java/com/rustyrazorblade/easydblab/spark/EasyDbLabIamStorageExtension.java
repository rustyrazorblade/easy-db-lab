package com.rustyrazorblade.easydblab.spark;

import com.google.common.collect.ImmutableMap;
import org.apache.cassandra.spark.transports.storage.StorageAccessConfiguration;
import org.apache.cassandra.spark.transports.storage.StorageCredentialPair;
import org.apache.cassandra.spark.transports.storage.extensions.StorageTransportConfiguration;
import org.apache.cassandra.spark.transports.storage.extensions.CoordinationSignalListener;
import org.apache.cassandra.spark.transports.storage.extensions.CredentialChangeListener;
import org.apache.cassandra.spark.transports.storage.extensions.ObjectFailureListener;
import org.apache.cassandra.spark.transports.storage.extensions.StorageTransportExtension;
import org.apache.spark.SparkConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Storage transport extension for easy-db-lab S3 IAM bulk writes with multi-DC coordination.
 *
 * <p>Uses {@code STORAGE_CREDENTIAL_TYPE=IAM} — no credentials are passed to the sidecar.
 * The Spark executor authenticates to AWS (S3 uploads and CRR polling) via its own EMR instance
 * profile. The sidecar authenticates independently via its own attached IAM role.
 *
 * <p>Coordination protocol (two-phase):
 * <ol>
 *   <li>After all SSTable bundles are uploaded, polls each DC's read bucket until S3 CRR is
 *       confirmed for all DCs, then calls {@link CoordinationSignalListener#onStageReady(String)}
 *       once with the job ID to signal all sidecars to begin staging.</li>
 *   <li>Tracks {@link #onStageSucceeded(String, long)} callbacks per DC; once all DCs have
 *       staged, calls {@link CoordinationSignalListener#onImportReady(String)} once with the
 *       job ID to trigger the import phase.</li>
 * </ol>
 *
 * <h2>Required Configuration</h2>
 * <ul>
 *   <li>{@code spark.easydblab.s3.bucket} - S3 write bucket</li>
 *   <li>{@code spark.easydblab.s3.readBuckets} - Per-DC read buckets: {@code dc1:bucket1,dc2:bucket2}
 *       (keys must match {@code coordinated_write_config} keys)</li>
 * </ul>
 *
 * <h2>Optional Configuration</h2>
 * <ul>
 *   <li>{@code spark.easydblab.dc.<dc>.s3.region} - AWS region for DC's read bucket
 *       (defaults to write-bucket region)</li>
 * </ul>
 */
public class EasyDbLabIamStorageExtension implements StorageTransportExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(EasyDbLabIamStorageExtension.class);

    public static final String EXTENSION_CLASS_NAME =
        "com.rustyrazorblade.easydblab.spark.EasyDbLabIamStorageExtension";

    private static final String KEY_PREFIX = "bulkwrite";
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final int REPLICATION_CHECK_THREADS = 32;

    static final class DcReadConfig {
        final String bucket;
        final String region;

        DcReadConfig(String bucket, String region) {
            this.bucket = bucket;
            this.region = region;
        }
    }

    private String jobId;
    private String writeBucket;
    private String writeRegion;
    private String endpoint;
    private Map<String, DcReadConfig> dcReadConfigs;
    private int dcCount;

    private CoordinationSignalListener coordinationSignalListener;
    private final ConcurrentLinkedQueue<String> uploadedKeys = new ConcurrentLinkedQueue<>();
    private final AtomicInteger stagedDcCount = new AtomicInteger(0);
    private final AtomicInteger failedStagingDcCount = new AtomicInteger(0);

    @Override
    public void initialize(String jobId, SparkConf conf, boolean isOnDriver) {
        this.jobId = jobId;
        uploadedKeys.clear();
        stagedDcCount.set(0);
        failedStagingDcCount.set(0);

        if (!conf.contains(SparkJobConfig.PROP_S3_BUCKET)) {
            throw new IllegalArgumentException(
                "Required property not set: " + SparkJobConfig.PROP_S3_BUCKET);
        }
        this.writeBucket = conf.get(SparkJobConfig.PROP_S3_BUCKET);

        if (!conf.contains(SparkJobConfig.PROP_S3_READ_BUCKETS)) {
            throw new IllegalArgumentException(
                "Required property not set: " + SparkJobConfig.PROP_S3_READ_BUCKETS + "\n" +
                "Usage: --conf " + SparkJobConfig.PROP_S3_READ_BUCKETS + "=dc1:bucket1,dc2:bucket2\n" +
                "Keys must match the coordinated_write_config DC keys.");
        }

        if (conf.contains(SparkJobConfig.PROP_S3_ENDPOINT)) {
            this.endpoint = conf.get(SparkJobConfig.PROP_S3_ENDPOINT);
        }

        try {
            this.writeRegion = new DefaultAwsRegionProviderChain().getRegion().id();
        } catch (software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new IllegalStateException(
                "Unable to detect AWS region. Set AWS_REGION or ensure EC2 IMDS is accessible.", e);
        }

        this.dcReadConfigs = parseReadBuckets(conf.get(SparkJobConfig.PROP_S3_READ_BUCKETS), conf, writeRegion);
        this.dcCount = dcReadConfigs.size();

        LOGGER.info("[{}] Initialized EasyDbLabIamStorageExtension (isDriver: {})", jobId, isOnDriver);
        LOGGER.info("[{}] S3 Write Bucket: {} (region: {})", jobId, writeBucket, writeRegion);
        for (Map.Entry<String, DcReadConfig> e : dcReadConfigs.entrySet()) {
            LOGGER.info("[{}] DC '{}' read bucket: {} (region: {})",
                jobId, e.getKey(), e.getValue().bucket, e.getValue().region);
        }
        if (endpoint != null) {
            LOGGER.info("[{}] Custom S3 Endpoint: {}", jobId, endpoint);
        }
    }

    @Override
    public StorageTransportConfiguration getStorageConfiguration() {
        Map<String, String> objectTags = endpoint != null
            ? ImmutableMap.of("endpoint", endpoint)
            : ImmutableMap.of();

        StorageCredentialPair writeCredentials = StorageCredentialPair.iamPair(writeRegion, writeRegion);
        StorageAccessConfiguration writeConfig =
            new StorageAccessConfiguration(writeRegion, writeBucket, writeCredentials.writeAuth());

        Map<String, StorageAccessConfiguration> readConfigs = new HashMap<>();
        for (Map.Entry<String, DcReadConfig> entry : dcReadConfigs.entrySet()) {
            DcReadConfig cfg = entry.getValue();
            StorageCredentialPair readCredentials = StorageCredentialPair.iamPair(cfg.region, cfg.region);
            readConfigs.put(entry.getKey(),
                new StorageAccessConfiguration(cfg.region, cfg.bucket, readCredentials.readAuth()));
        }

        return new StorageTransportConfiguration(KEY_PREFIX, objectTags, writeConfig, readConfigs);
    }

    static Map<String, DcReadConfig> parseReadBuckets(String value, SparkConf conf, String defaultRegion) {
        Map<String, DcReadConfig> result = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                SparkJobConfig.PROP_S3_READ_BUCKETS + " must not be empty. " +
                "Usage: --conf " + SparkJobConfig.PROP_S3_READ_BUCKETS + "=dc1:bucket1,dc2:bucket2");
        }
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex < 0) {
                throw new IllegalArgumentException(
                    "Invalid entry in " + SparkJobConfig.PROP_S3_READ_BUCKETS + ": '" + trimmed + "'. " +
                    "Expected format: dcName:bucketName");
            }
            String dcName = trimmed.substring(0, colonIndex).trim();
            String bucket = trimmed.substring(colonIndex + 1).trim();
            String regionKey = String.format(SparkJobConfig.PROP_DC_S3_REGION, dcName);
            String region = conf.contains(regionKey) ? conf.get(regionKey) : defaultRegion;
            result.put(dcName, new DcReadConfig(bucket, region));
        }
        return result;
    }

    @Override
    public void setCoordinationSignalListener(CoordinationSignalListener listener) {
        this.coordinationSignalListener = listener;
    }

    @Override
    public void onTransportStart(long elapsedMillis) {
        LOGGER.info("[{}] Bulk write transport started ({}ms)", jobId, elapsedMillis);
    }

    @Override
    public void onObjectPersisted(String bucket, String key, long sizeInBytes) {
        uploadedKeys.add(key);
        LOGGER.debug("[{}] Uploaded bundle: s3://{}/{} ({} bytes)", jobId, bucket, key, sizeInBytes);
    }

    @Override
    public void onAllObjectsPersisted(long objectsCount, long rowCount, long elapsedMillis) {
        LOGGER.info("[{}] All {} SSTable bundles uploaded ({} rows) in {}ms",
            jobId, objectsCount, rowCount, elapsedMillis);

        if (coordinationSignalListener == null) {
            LOGGER.warn("[{}] No CoordinationSignalListener set — skipping replication check", jobId);
            return;
        }
        if (dcCount == 0) {
            // Calling framework callbacks synchronously here is intentional — the framework
            // spec allows callbacks from within onAllObjectsPersisted when there is nothing to wait for.
            LOGGER.warn("[{}] No DC read configs — signalling stage and import ready immediately", jobId);
            coordinationSignalListener.onStageReady(jobId);
            coordinationSignalListener.onImportReady(jobId);
            return;
        }

        final List<String> keys = new ArrayList<>(uploadedKeys);
        final CoordinationSignalListener listener = coordinationSignalListener;

        if (keys.isEmpty()) {
            LOGGER.warn("[{}] No bundles uploaded — signalling stage and import ready immediately", jobId);
            listener.onStageReady(jobId);
            listener.onImportReady(jobId);
            return;
        }

        // Build one S3Client per DC that needs cross-region checking. DCs that share the write
        // bucket skip polling entirely and don't need a client.
        final Map<String, S3Client> dcClients = new LinkedHashMap<>();
        for (Map.Entry<String, DcReadConfig> entry : dcReadConfigs.entrySet()) {
            DcReadConfig cfg = entry.getValue();
            if (!cfg.bucket.equals(writeBucket) || !cfg.region.equals(writeRegion)) {
                dcClients.put(entry.getKey(), buildS3Client(cfg.region));
            }
        }

        int totalTasks = keys.size() * dcCount;
        final CountDownLatch latch = new CountDownLatch(totalTasks);
        final AtomicBoolean anyFailed = new AtomicBoolean(false);

        LOGGER.info("[{}] Checking replication of {} bundles across {} DCs ({} tasks, {} threads)",
            jobId, keys.size(), dcCount, totalTasks, REPLICATION_CHECK_THREADS);

        int poolSize = Math.min(totalTasks, REPLICATION_CHECK_THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize + 1, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        try {
            for (Map.Entry<String, DcReadConfig> entry : dcReadConfigs.entrySet()) {
                final String dcName = entry.getKey();
                final DcReadConfig cfg = entry.getValue();
                final S3Client s3 = dcClients.get(dcName);

                for (final String key : keys) {
                    pool.execute(() -> {
                        Thread.currentThread().setName("s3-crr-" + jobId + "-" + dcName);
                        try {
                            if (s3 == null) {
                                LOGGER.debug("[{}] DC '{}' shares write bucket — key '{}' ready immediately",
                                    jobId, dcName, key);
                            } else {
                                pollUntilReplicated(dcName, cfg, s3, key);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            anyFailed.set(true);
                            LOGGER.error("[{}] Replication check interrupted for DC '{}' key '{}'",
                                jobId, dcName, key, e);
                        } catch (Exception e) {
                            anyFailed.set(true);
                            LOGGER.error("[{}] Replication check failed for DC '{}' key '{}'",
                                jobId, dcName, key, e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            // Coordinator: wait for all tasks, close clients, then signal or abort.
            pool.execute(() -> {
                Thread.currentThread().setName("s3-crr-coordinator-" + jobId);
                try {
                    latch.await();
                    if (anyFailed.get()) {
                        LOGGER.error("[{}] One or more replication checks failed — not calling onStageReady", jobId);
                    } else {
                        LOGGER.info("[{}] All {} bundles confirmed in all {} DCs — calling onStageReady",
                            jobId, keys.size(), dcCount);
                        listener.onStageReady(jobId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("[{}] Coordinator interrupted waiting for replication checks", jobId, e);
                } finally {
                    dcClients.values().forEach(S3Client::close);
                }
            });
        } finally {
            pool.shutdown();
        }
    }

    private void pollUntilReplicated(String dcName, DcReadConfig cfg, S3Client s3, String key)
            throws InterruptedException {
        LOGGER.debug("[{}] Waiting for DC '{}' bucket '{}' key '{}'", jobId, dcName, cfg.bucket, key);

        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(cfg.bucket)
            .key(key)
            .build();

        while (true) {
            try {
                s3.headObject(request);
                LOGGER.debug("[{}] DC '{}' confirmed key '{}'", jobId, dcName, key);
                return;
            } catch (NoSuchKeyException e) {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
    }

    private S3Client buildS3Client(String region) {
        software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null) {
            builder.endpointOverride(java.net.URI.create(endpoint));
        }
        return builder.build();
    }

    @Override
    public void onStageSucceeded(String clusterId, long elapsedMillis) {
        LOGGER.info("[{}] Cluster '{}' staging succeeded in {}ms", jobId, clusterId, elapsedMillis);
        int succeeded = stagedDcCount.incrementAndGet();
        int failed = failedStagingDcCount.get();
        if (succeeded + failed == dcCount) {
            if (failed == 0) {
                LOGGER.info("[{}] All {} DCs staged — calling onImportReady", jobId, dcCount);
                if (coordinationSignalListener != null) {
                    coordinationSignalListener.onImportReady(jobId);
                }
            } else {
                LOGGER.error("[{}] {} DC(s) failed staging — skipping onImportReady", jobId, failed);
            }
        }
    }

    @Override
    public void onStageFailed(String clusterId, Throwable cause) {
        LOGGER.error("[{}] Cluster '{}' staging failed", jobId, clusterId, cause);
        int failed = failedStagingDcCount.incrementAndGet();
        int succeeded = stagedDcCount.get();
        if (succeeded + failed == dcCount) {
            LOGGER.error("[{}] All {} DCs accounted for ({} succeeded, {} failed) — skipping onImportReady",
                jobId, dcCount, succeeded, failed);
        }
    }

    @Override
    public void onImportSucceeded(String clusterId, long elapsedMillis) {
        LOGGER.info("[{}] Cluster '{}' import succeeded in {}ms", jobId, clusterId, elapsedMillis);
    }

    @Override
    public void onImportFailed(String clusterId, Throwable cause) {
        LOGGER.error("[{}] Cluster '{}' import failed", jobId, clusterId, cause);
    }

    @Override
    public void onJobSucceeded(long elapsedMillis) {
        LOGGER.info("[{}] Bulk write job SUCCEEDED (total: {}ms)", jobId, elapsedMillis);
    }

    @Override
    public void onJobFailed(long elapsedMillis, Throwable throwable) {
        LOGGER.error("[{}] Bulk write job FAILED (total: {}ms)", jobId, elapsedMillis, throwable);
    }

    @Override
    public void setCredentialChangeListener(CredentialChangeListener listener) {
        // No-op: IAM credentials are resolved by the runtime, no rotation callbacks needed
    }

    @Override
    public void setObjectFailureListener(ObjectFailureListener listener) {
        // No-op: object failures handled by cassandra-analytics retry logic
    }

    @Override
    public void onObjectApplied(String bucket, String key, long sizeInBytes, long elapsedMillis) {
        // No-op: not tracking individual object application
    }
}
