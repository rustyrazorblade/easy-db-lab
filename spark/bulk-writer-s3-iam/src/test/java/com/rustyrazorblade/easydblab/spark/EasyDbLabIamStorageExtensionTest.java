package com.rustyrazorblade.easydblab.spark;

import org.apache.cassandra.spark.transports.storage.extensions.CoordinationSignalListener;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EasyDbLabIamStorageExtensionTest {

    @Test
    void parseReadBuckets_basicParsing() {
        SparkConf conf = new SparkConf(false);
        Map<String, EasyDbLabIamStorageExtension.DcReadConfig> result =
            EasyDbLabIamStorageExtension.parseReadBuckets("dc1:bucket1,dc2:bucket2", conf, "us-west-2");

        assertThat(result).containsOnlyKeys("dc1", "dc2");
        assertThat(result.get("dc1").bucket).isEqualTo("bucket1");
        assertThat(result.get("dc1").region).isEqualTo("us-west-2");
        assertThat(result.get("dc2").bucket).isEqualTo("bucket2");
        assertThat(result.get("dc2").region).isEqualTo("us-west-2");
    }

    @Test
    void parseReadBuckets_withRegionOverride() {
        SparkConf conf = new SparkConf(false)
            .set(String.format(SparkJobConfig.PROP_DC_S3_REGION, "dc1"), "us-east-1");

        Map<String, EasyDbLabIamStorageExtension.DcReadConfig> result =
            EasyDbLabIamStorageExtension.parseReadBuckets("dc1:bucket1,dc2:bucket2", conf, "us-west-2");

        assertThat(result.get("dc1").region).isEqualTo("us-east-1");
        assertThat(result.get("dc2").region).isEqualTo("us-west-2");
    }

    @Test
    void parseReadBuckets_invalidEntry_throwsIllegalArgument() {
        SparkConf conf = new SparkConf(false);

        assertThatThrownBy(() ->
            EasyDbLabIamStorageExtension.parseReadBuckets("dc1-no-colon", conf, "us-west-2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dc1-no-colon");
    }

    @Test
    void parseReadBuckets_trimsWhitespace() {
        SparkConf conf = new SparkConf(false);
        Map<String, EasyDbLabIamStorageExtension.DcReadConfig> result =
            EasyDbLabIamStorageExtension.parseReadBuckets(" dc1 : bucket1 ", conf, "us-west-2");

        assertThat(result).containsOnlyKeys("dc1");
        assertThat(result.get("dc1").bucket).isEqualTo("bucket1");
    }

    @Test
    void initialize_missingBucket_throwsIllegalArgument() {
        System.setProperty("aws.region", "us-east-1");
        try {
            EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
            SparkConf conf = new SparkConf(false)
                .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:bucket1");
            assertThatThrownBy(() -> ext.initialize("job", conf, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SparkJobConfig.PROP_S3_BUCKET);
        } finally {
            System.clearProperty("aws.region");
        }
    }

    @Test
    void initialize_missingReadBuckets_throwsIllegalArgument() {
        System.setProperty("aws.region", "us-east-1");
        try {
            EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
            SparkConf conf = new SparkConf(false)
                .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket");
            assertThatThrownBy(() -> ext.initialize("job", conf, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SparkJobConfig.PROP_S3_READ_BUCKETS);
        } finally {
            System.clearProperty("aws.region");
        }
    }

    @Test
    void initialize_regionDetectable_succeeds() {
        System.setProperty("aws.region", "us-east-1");
        try {
            EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
            SparkConf conf = new SparkConf(false)
                .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket")
                .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:my-bucket");
            assertThatCode(() -> ext.initialize("job", conf, true))
                .doesNotThrowAnyException();
        } finally {
            System.clearProperty("aws.region");
        }
    }

    @Test
    void onStageSucceeded_allDcsStaged_callsOnImportReadyOnce() {
        System.setProperty("aws.region", "us-east-1");
        try {
            EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
            SparkConf conf = new SparkConf(false)
                .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket")
                .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:my-bucket,dc2:my-bucket");
            ext.initialize("test-job", conf, true);

            List<String> importReadyJobIds = new ArrayList<>();
            ext.setCoordinationSignalListener(new CoordinationSignalListener() {
                @Override
                public void onStageReady(String jobId) {}

                @Override
                public void onImportReady(String jobId) {
                    importReadyJobIds.add(jobId);
                }
            });

            ext.onStageSucceeded("dc1", 100L);
            assertThat(importReadyJobIds).isEmpty();

            ext.onStageSucceeded("dc2", 200L);
            assertThat(importReadyJobIds).containsExactly("test-job");
        } finally {
            System.clearProperty("aws.region");
        }
    }

    @Test
    void onStageFailed_anyDcFails_doesNotCallOnImportReady() {
        System.setProperty("aws.region", "us-east-1");
        try {
            EasyDbLabIamStorageExtension ext = new EasyDbLabIamStorageExtension();
            SparkConf conf = new SparkConf(false)
                .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket")
                .set(SparkJobConfig.PROP_S3_READ_BUCKETS, "dc1:my-bucket,dc2:my-bucket");
            ext.initialize("test-job", conf, true);

            List<String> importReadyJobIds = new ArrayList<>();
            ext.setCoordinationSignalListener(new CoordinationSignalListener() {
                @Override
                public void onStageReady(String jobId) {}

                @Override
                public void onImportReady(String jobId) {
                    importReadyJobIds.add(jobId);
                }
            });

            ext.onStageSucceeded("dc1", 100L);
            ext.onStageFailed("dc2", new RuntimeException("staging error"));

            assertThat(importReadyJobIds).isEmpty();
        } finally {
            System.clearProperty("aws.region");
        }
    }
}
