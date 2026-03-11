package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SparkJobConfig property parsing, defaults, and validation.
 */
class SparkJobConfigTest {

    private SparkConf baseConf() {
        return new SparkConf(false)
            .set("spark.master", "local[1]")
            .set("spark.app.name", "test")
            .set(SparkJobConfig.PROP_CONTACT_POINTS, "host1,host2")
            .set(SparkJobConfig.PROP_KEYSPACE, "test_ks")
            .set(SparkJobConfig.PROP_LOCAL_DC, "dc1");
    }

    @Test
    void load_withRequiredPropertiesOnly_usesDefaults() {
        SparkJobConfig config = SparkJobConfig.load(baseConf());

        assertThat(config.getContactPoints()).isEqualTo("host1,host2");
        assertThat(config.getKeyspace()).isEqualTo("test_ks");
        assertThat(config.getLocalDc()).isEqualTo("dc1");
        assertThat(config.getRowCount()).isEqualTo(1_000_000L);
        assertThat(config.getParallelism()).isEqualTo(10);
        assertThat(config.getPartitionCount()).isEqualTo(10_000L);
        assertThat(config.getReplicationFactor()).isEqualTo(3);
        assertThat(config.isSkipDdl()).isFalse();
        assertThat(config.getCompaction()).isNull();
        assertThat(config.getS3Bucket()).isNull();
        assertThat(config.getS3Endpoint()).isNull();
    }

    @Test
    void load_withAllProperties_parsesCorrectly() {
        SparkConf conf = baseConf()
            .set(SparkJobConfig.PROP_TABLE, "my_table")
            .set(SparkJobConfig.PROP_ROW_COUNT, "500")
            .set(SparkJobConfig.PROP_PARALLELISM, "4")
            .set(SparkJobConfig.PROP_PARTITION_COUNT, "50")
            .set(SparkJobConfig.PROP_REPLICATION_FACTOR, "2")
            .set(SparkJobConfig.PROP_SKIP_DDL, "true")
            .set(SparkJobConfig.PROP_COMPACTION, "LeveledCompactionStrategy")
            .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket")
            .set(SparkJobConfig.PROP_S3_ENDPOINT, "http://localhost:4566");

        SparkJobConfig config = SparkJobConfig.load(conf);

        assertThat(config.getTable()).isEqualTo("my_table");
        assertThat(config.getRowCount()).isEqualTo(500L);
        assertThat(config.getParallelism()).isEqualTo(4);
        assertThat(config.getPartitionCount()).isEqualTo(50L);
        assertThat(config.getReplicationFactor()).isEqualTo(2);
        assertThat(config.isSkipDdl()).isTrue();
        assertThat(config.getCompaction()).isEqualTo("LeveledCompactionStrategy");
        assertThat(config.getS3Bucket()).isEqualTo("my-bucket");
        assertThat(config.getS3Endpoint()).isEqualTo("http://localhost:4566");
    }

    @Test
    void load_withoutTable_generatesTimestampTable() {
        SparkJobConfig config = SparkJobConfig.load(baseConf());

        assertThat(config.getTable()).startsWith("data_");
    }

    @Test
    void load_missingContactPoints_throwsWithUsage() {
        SparkConf conf = new SparkConf(false)
            .set("spark.master", "local[1]")
            .set("spark.app.name", "test")
            .set(SparkJobConfig.PROP_KEYSPACE, "test_ks")
            .set(SparkJobConfig.PROP_LOCAL_DC, "dc1");

        assertThatThrownBy(() -> SparkJobConfig.load(conf))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_CONTACT_POINTS)
            .hasMessageContaining("Required properties");
    }

    @Test
    void load_missingKeyspace_throwsWithUsage() {
        SparkConf conf = new SparkConf(false)
            .set("spark.master", "local[1]")
            .set("spark.app.name", "test")
            .set(SparkJobConfig.PROP_CONTACT_POINTS, "host1")
            .set(SparkJobConfig.PROP_LOCAL_DC, "dc1");

        assertThatThrownBy(() -> SparkJobConfig.load(conf))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_KEYSPACE);
    }

    @Test
    void load_missingLocalDc_throwsWithUsage() {
        SparkConf conf = new SparkConf(false)
            .set("spark.master", "local[1]")
            .set("spark.app.name", "test")
            .set(SparkJobConfig.PROP_CONTACT_POINTS, "host1")
            .set(SparkJobConfig.PROP_KEYSPACE, "test_ks");

        assertThatThrownBy(() -> SparkJobConfig.load(conf))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_LOCAL_DC);
    }

    @Test
    void buildBulkWriteOptions_containsExpectedKeys() {
        SparkJobConfig config = SparkJobConfig.load(baseConf());

        Map<String, String> options = config.buildBulkWriteOptions();

        assertThat(options).containsEntry(SparkJobConfig.OPT_SIDECAR_CONTACT_POINTS, "host1,host2");
        assertThat(options).containsEntry(SparkJobConfig.OPT_KEYSPACE, "test_ks");
        assertThat(options).containsEntry(SparkJobConfig.OPT_LOCAL_DC, "dc1");
        assertThat(options).containsEntry(SparkJobConfig.OPT_BULK_WRITER_CL, "LOCAL_QUORUM");
        assertThat(options).containsEntry(SparkJobConfig.OPT_NUMBER_SPLITS, "-1");
        assertThat(options).containsKey(SparkJobConfig.OPT_TABLE);
    }
}
