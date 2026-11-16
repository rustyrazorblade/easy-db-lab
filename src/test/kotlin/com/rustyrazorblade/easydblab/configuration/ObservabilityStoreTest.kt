package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class ObservabilityStoreTest {
    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "0f1e2d3c-aaaa-bbbb-cccc-123456789abc",
            s3Bucket = "acct-bucket",
            initConfig = InitConfig(tenant = "acme"),
        )

    private val at = Instant.parse("2026-09-24T13:04:05Z")

    @Test
    fun `backend prefixes are fixed and carry no tenant`() {
        val store = ObservabilityStore.from(state)

        assertThat(store.bucket).isEqualTo("acct-bucket")
        assertThat(store.tracesPrefix()).isEqualTo("tempo")
        assertThat(store.profilesPrefix()).isEqualTo("pyroscope")
        assertThat(store.logsPrefix()).isEqualTo("loki")
        // Mimir accepts only letters and digits in its storage prefix.
        assertThat(store.metricsPrefix()).isEqualTo("mimir")
    }

    @Test
    fun `the annotations root carries the tenant`() {
        val store = ObservabilityStore.from(state)

        assertThat(store.annotationsRoot().toString()).isEqualTo("s3://acct-bucket/grafana/annotations/acme")
    }

    @Test
    fun `a test's documents live under reports, the tenant and the cluster label`() {
        val store = ObservabilityStore.from(state)
        val cluster = state.clusterLabelName()

        assertThat(store.documentsRoot(cluster).getKey()).isEqualTo("reports/acme/lab-0f1e2d3c-aaaa-bbbb-cccc-123456789abc")
        assertThat(store.document(cluster, "results.md").getKey())
            .isEqualTo("reports/acme/lab-0f1e2d3c-aaaa-bbbb-cccc-123456789abc/results.md")
    }

    @Test
    fun `snapshot locations are named by time and cluster`() {
        val store = ObservabilityStore.from(state)
        val name = SnapshotName.of(state, at)

        assertThat(store.annotationsArtifact(name).getKey())
            .isEqualTo("grafana/annotations/acme/20260924-130405_lab-0f1e2d3c-aaaa-bbbb-cccc-123456789abc.json")
    }

    @Test
    fun `a cluster without a tenant uses the default tenant`() {
        val store = ObservabilityStore.from(state.copy(initConfig = null))

        assertThat(store.annotationsRoot().getKey()).isEqualTo("grafana/annotations/default")
    }

    @Test
    fun `a cluster without a bucket fails fast with the run-up-first message`() {
        assertThatThrownBy { ObservabilityStore.from(state.copy(s3Bucket = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Run 'easy-db-lab up' first")
    }

    @Test
    fun `the first free snapshot name is the one for the given second when nothing holds it`() {
        val name = SnapshotName.firstFree(state, at) { false }

        assertThat(name).isEqualTo(SnapshotName.of(state, at))
    }

    /** A second backup in the same second must never write over the first. */
    @Test
    fun `the first free snapshot name moves past every second already taken`() {
        val taken = setOf(SnapshotName.of(state, at), SnapshotName.of(state, at.plusSeconds(1)))

        val name = SnapshotName.firstFree(state, at) { it in taken }

        assertThat(name.toString()).isEqualTo("20260924-130407_${state.clusterLabelName()}")
    }
}
