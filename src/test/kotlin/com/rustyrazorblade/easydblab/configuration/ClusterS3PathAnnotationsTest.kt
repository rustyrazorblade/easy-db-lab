package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the Grafana annotations account-level path helpers on [ClusterS3Path].
 *
 * The load-bearing invariant is that the artifact lives OUTSIDE the per-cluster `clusters/<name>-<id>/`
 * prefix that teardown expires; these tests pin that down along with the cluster-name and timestamp
 * keying so a backup remains findable after the cluster is gone.
 */
class ClusterS3PathAnnotationsTest {
    @Test
    fun `annotations artifact is keyed under the account-level grafana-annotations root, not a cluster prefix`() {
        val artifact =
            ClusterS3Path.grafanaAnnotationsArtifact(
                accountBucket = "acct-bucket",
                clusterName = "perf-test",
                timestampMillis = 1767225600000L,
            )

        assertThat(artifact.getKey()).isEqualTo("grafana-annotations/perf-test/annotations-1767225600000.json")
        assertThat(artifact.getKey()).doesNotStartWith("clusters/")
        assertThat(artifact.toUri()).isEqualTo("s3://acct-bucket/grafana-annotations/perf-test/annotations-1767225600000.json")
    }

    @Test
    fun `annotations root sits directly under the bucket`() {
        assertThat(ClusterS3Path.grafanaAnnotationsRoot("acct-bucket").getKey()).isEqualTo("grafana-annotations")
    }
}
