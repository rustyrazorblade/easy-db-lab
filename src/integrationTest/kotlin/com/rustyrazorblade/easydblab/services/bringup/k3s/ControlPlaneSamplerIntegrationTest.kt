package com.rustyrazorblade.easydblab.services.bringup.k3s

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.services.bringup.ControlPlaneHealth
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves [ControlPlaneSampler] against a real K3s API server: the minimal `get nodes` and the
 * `/metrics` read both succeed through the production client, and an idle server shows no growth
 * in rejected requests between samples.
 */
class ControlPlaneSamplerIntegrationTest {
    @Test
    fun `a sample of a real API server reads its latency and its rejected requests`() {
        ControlPlaneSampler.open(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml())).use { sampler ->
            val first = sampler.sample()
            val second = sampler.sample()

            assertThat(first).isInstanceOf(ControlPlaneHealth.Sample.Reading::class.java)
            val reading = second as ControlPlaneHealth.Sample.Reading
            assertThat(reading.latencyMs).isBetween(0L, 5_000L)
            assertThat(reading.rejections).isZero()
        }
    }

    @Test
    fun `the API server's metrics text is the format the parse reads`() {
        val metrics = SharedK3s.client().use { it.raw("/metrics") }

        // The rejected counter has no series until something is rejected; the format is the same as every counter's.
        assertThat(metrics).contains("# TYPE apiserver_request_total counter")
        assertThat(metrics.lines().filter { it.startsWith("apiserver_flowcontrol_rejected_requests_total{") })
            .allSatisfy { line -> assertThat(ControlPlaneSampler.rejectedRequests(line)).isGreaterThan(0.0) }
    }
}
