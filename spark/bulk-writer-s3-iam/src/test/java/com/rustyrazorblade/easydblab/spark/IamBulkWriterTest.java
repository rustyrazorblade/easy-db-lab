package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class IamBulkWriterTest {

    private SparkConf baseConf() {
        return new SparkConf(false)
            .set(SparkJobConfig.PROP_CONTACT_POINTS, "host1")
            .set(SparkJobConfig.PROP_KEYSPACE, "test_ks")
            .set(SparkJobConfig.PROP_LOCAL_DC, "us-west-2")
            .set(SparkJobConfig.PROP_S3_BUCKET, "my-bucket");
    }

    private Map<String, List<String>> twoRegionTopology() {
        Map<String, List<String>> topo = new LinkedHashMap<>();
        topo.put("us-west-2", List.of("10.0.0.1:9043", "10.0.0.2:9043"));
        topo.put("us-east-1", List.of("10.1.0.1:9043"));
        return topo;
    }

    @Test
    void buildReadBuckets_defaultsToBucket() {
        SparkConf conf = baseConf();
        SparkJobConfig config = SparkJobConfig.load(conf);

        String result = IamBulkWriter.buildReadBuckets(twoRegionTopology(), config, conf);

        assertThat(result).isEqualTo("us-west-2:my-bucket,us-east-1:my-bucket");
    }

    @Test
    void buildReadBuckets_withPerDcOverride() {
        SparkConf conf = baseConf()
            .set(String.format(SparkJobConfig.PROP_DC_S3_READ_BUCKET, "us-east-1"), "east-bucket");
        SparkJobConfig config = SparkJobConfig.load(conf);

        String result = IamBulkWriter.buildReadBuckets(twoRegionTopology(), config, conf);

        assertThat(result).isEqualTo("us-west-2:my-bucket,us-east-1:east-bucket");
    }

    @Test
    void buildCoordinatedWriteConfig_singleDc() {
        Map<String, List<String>> topo = new LinkedHashMap<>();
        topo.put("us-west-2", List.of("10.0.0.1:9043"));

        String json = IamBulkWriter.buildCoordinatedWriteConfig(topo);

        assertThat(json).isEqualTo(
            "{\"us-west-2\":{\"sidecarContactPoints\":[\"10.0.0.1:9043\"]," +
            "\"localDc\":\"us-west-2\",\"writeToLocalDcOnly\":true}}");
    }

    @Test
    void buildCoordinatedWriteConfig_multiDc() {
        String json = IamBulkWriter.buildCoordinatedWriteConfig(twoRegionTopology());

        assertThat(json).contains("\"us-west-2\":");
        assertThat(json).contains("\"us-east-1\":");
        assertThat(json).contains("\"10.0.0.1:9043\"");
        assertThat(json).contains("\"10.0.0.2:9043\"");
        assertThat(json).contains("\"writeToLocalDcOnly\":true");
    }

    @Test
    void validateS3Bucket_emptyString_throwsIllegalArgument() {
        assertThatThrownBy(() -> IamBulkWriter.validateS3Bucket(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SparkJobConfig.PROP_S3_BUCKET);
    }

    @Test
    void validateS3Bucket_validName_doesNotThrow() {
        assertThatCode(() -> IamBulkWriter.validateS3Bucket("my-bucket"))
            .doesNotThrowAnyException();
    }

    @Test
    void validateS3Bucket_nameWithPath_throwsWithBucketOnlyInMessage() {
        assertThatThrownBy(() -> IamBulkWriter.validateS3Bucket("my-bucket/spark"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("my-bucket");
    }

    @Test
    void buildCoordinatedWriteConfig_escapesQuotes() {
        Map<String, List<String>> topo = new LinkedHashMap<>();
        topo.put("dc\"quoted", List.of("10.0.0.1:9043"));

        String json = IamBulkWriter.buildCoordinatedWriteConfig(topo);

        assertThat(json).contains("\"dc\\\"quoted\":");
    }
}
