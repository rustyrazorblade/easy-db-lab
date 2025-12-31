package com.rustyrazorblade.easydblab.resources

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests to ensure k8s manifest resources have correct configuration.
 */
class K8sResourceSyncTest {
    companion object {
        private const val VECTOR_S3_CONFIG_RESOURCE =
            "/com/rustyrazorblade/easydblab/commands/k8s/core/53-vector-s3-configmap.yaml"
    }

    @Test
    fun `vector-s3-configmap should reference correct EMR log path structure`() {
        // This test ensures the Vector S3 configmap parses the correct S3 path structure
        // Path format: spark/emr-logs/{cluster-id}/steps/{step-id}/{stdout|stderr}.gz
        // Array indexes: [0]=spark, [1]=emr-logs, [2]=cluster-id, [3]=steps, [4]=step-id, [5]=log_file

        val vectorS3Config =
            this::class.java
                .getResourceAsStream(VECTOR_S3_CONFIG_RESOURCE)
                ?.bufferedReader()
                ?.readText()
                ?: error("Could not load resource: $VECTOR_S3_CONFIG_RESOURCE")

        // Verify the comment documents the correct path structure matching Constants.EMR.S3_LOG_PREFIX
        val expectedPrefix = Constants.EMR.S3_LOG_PREFIX.trimEnd('/')
        assertThat(vectorS3Config)
            .withFailMessage("Vector S3 config should document path starting with $expectedPrefix")
            .contains("$expectedPrefix/{cluster-id}/steps/{step-id}")

        // Verify the array indexes are correct for spark/emr-logs/ prefix
        // parts[2] = cluster-id (after spark, emr-logs)
        assertThat(vectorS3Config)
            .withFailMessage("emr_cluster_id should use parts[2] for $expectedPrefix prefix")
            .contains("get(parts, [2])")

        // parts[4] = step-id (after spark, emr-logs, cluster-id, steps)
        assertThat(vectorS3Config)
            .withFailMessage("step_id should use parts[4] for $expectedPrefix prefix")
            .contains("get(parts, [4])")

        // parts[5] = log file (stdout.gz or stderr.gz)
        assertThat(vectorS3Config)
            .withFailMessage("log_file should use parts[5] for $expectedPrefix prefix")
            .contains("get(parts, [5])")
    }

    @Test
    fun `EMR S3 log prefix constant should match expected format`() {
        // Verify the constant has the expected format
        assertThat(Constants.EMR.S3_LOG_PREFIX)
            .isEqualTo("spark/emr-logs/")
            .endsWith("/")
    }
}
