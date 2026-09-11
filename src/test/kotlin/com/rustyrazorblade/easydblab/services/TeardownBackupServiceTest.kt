package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.configuration.ClusterState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [DefaultTeardownBackupService].
 *
 * The load-bearing behavior is the coupling: the metrics backup and the annotations backup are
 * always attempted together, each is retried, and a failure in either one fails the whole operation
 * so `down` can abort. Hand-written fakes drive the success and failure modes and count invocations.
 *
 * Fakes are used rather than Mockito here on purpose: both backup methods return Kotlin's value class
 * [Result], which Mockito cannot reliably stub — a stubbed `Result.failure(...)` comes back as a
 * default success and the coupling under test never sees the failure. The fakes return real [Result]
 * values, so the retry and the failure propagation are exercised for real.
 */
class TeardownBackupServiceTest {
    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-west-2a",
            instanceId = "i-control",
        )

    private val clusterState =
        ClusterState(
            name = "perf-test",
            versions = mutableMapOf(),
            s3Bucket = "acct-bucket",
        )

    private val metricsResult = VictoriaBackupResult(ClusterS3Path.root("acct-bucket").resolve("m"), "ts")
    private val annotationsResult =
        GrafanaAnnotationBackupResult(ClusterS3Path.root("acct-bucket").resolve("a"), 3)

    /**
     * Returns the queued metrics results in order, clamping to the last once exhausted so a retry
     * can move from a failure to a success. Counts invocations for retry assertions.
     */
    private class FakeVictoriaBackupService(
        private val results: List<Result<VictoriaBackupResult>>,
    ) : VictoriaBackupService {
        var metricsCalls = 0
            private set

        override fun backupMetrics(
            controlHost: ClusterHost,
            clusterState: ClusterState,
            destinationUri: String?,
        ): Result<VictoriaBackupResult> {
            val result = results.getOrElse(metricsCalls) { results.last() }
            metricsCalls++
            return result
        }

        override fun backupLogs(
            controlHost: ClusterHost,
            clusterState: ClusterState,
            destinationUri: String?,
        ): Result<VictoriaBackupResult> = error("not used")
    }

    private class FakeAnnotationBackupService(
        private val result: Result<GrafanaAnnotationBackupResult>,
    ) : GrafanaAnnotationBackupService {
        var calls = 0
            private set

        override fun backup(
            controlHost: ClusterHost,
            clusterState: ClusterState,
        ): Result<GrafanaAnnotationBackupResult> {
            calls++
            return result
        }
    }

    @Test
    fun `both backups succeed - success and each attempted once`() {
        val metrics = FakeVictoriaBackupService(listOf(Result.success(metricsResult)))
        val annotations = FakeAnnotationBackupService(Result.success(annotationsResult))
        val service = DefaultTeardownBackupService(metrics, annotations)

        val result = service.backupBeforeTeardown(controlHost, clusterState)

        assertThat(result.isSuccess).isTrue()
        assertThat(metrics.metricsCalls).isEqualTo(1)
        assertThat(annotations.calls).isEqualTo(1)
    }

    @Test
    fun `metrics backup failing still attempts annotations and fails the whole operation`() {
        val metrics = FakeVictoriaBackupService(listOf(Result.failure(IllegalStateException("vmbackup exploded"))))
        val annotations = FakeAnnotationBackupService(Result.success(annotationsResult))
        val service = DefaultTeardownBackupService(metrics, annotations)

        val result = service.backupBeforeTeardown(controlHost, clusterState)

        // The whole operation fails, but annotations were still attempted (never one without the other).
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("vmbackup exploded")
        assertThat(annotations.calls).isEqualTo(1)
    }

    @Test
    fun `annotations backup failing fails the whole operation even when metrics succeeded`() {
        val metrics = FakeVictoriaBackupService(listOf(Result.success(metricsResult)))
        val annotations = FakeAnnotationBackupService(Result.failure(IllegalStateException("grafana unreachable")))
        val service = DefaultTeardownBackupService(metrics, annotations)

        val result = service.backupBeforeTeardown(controlHost, clusterState)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("grafana unreachable")
    }

    @Test
    fun `a transient metrics failure is retried and then succeeds`() {
        val metrics =
            FakeVictoriaBackupService(
                listOf(Result.failure(IllegalStateException("transient")), Result.success(metricsResult)),
            )
        val annotations = FakeAnnotationBackupService(Result.success(annotationsResult))
        val service = DefaultTeardownBackupService(metrics, annotations)

        val result = service.backupBeforeTeardown(controlHost, clusterState)

        assertThat(result.isSuccess).isTrue()
        // The first attempt failed, the retry succeeded: proves the retry is real, not decorative.
        assertThat(metrics.metricsCalls).isGreaterThanOrEqualTo(2)
    }
}
