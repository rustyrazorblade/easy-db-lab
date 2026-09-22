package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests the record-then-post contract of [CiliumInstallAnnotator]: the timestamps are taken when
 * the install events happen, not when Grafana finally receives them.
 */
class CiliumInstallAnnotatorTest {
    private val controlHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.0.1",
            alias = "control0",
            availabilityZone = "us-west-2a",
        )

    /** A clock that advances by [step] on every read, so consecutive records get distinct times. */
    private class SteppingClock(
        private var now: Instant,
        private val step: Duration,
    ) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = now.also { now = now.plus(step) }
    }

    private val t0 = Instant.parse("2026-09-21T10:00:00Z")

    @Test
    fun `post sends started then finished with the times they were recorded, tagged cilium and global`() {
        val grafana = mock<GrafanaDashboardService>()
        whenever(grafana.createAnnotation(any(), any()))
            .doReturn(GrafanaAnnotationResponse(id = 1), GrafanaAnnotationResponse(id = 2))
        val annotator = CiliumInstallAnnotator(grafana, SteppingClock(t0, Duration.ofSeconds(90)))

        annotator.installStarted()
        annotator.installFinished()
        val posted = annotator.post(controlHost).getOrThrow()

        val captor = argumentCaptor<GrafanaAnnotationRequest>()
        verify(grafana, times(2)).createAnnotation(eq(controlHost), captor.capture())
        val requests = captor.allValues
        assertThat(requests[0].text).isEqualTo("Cilium install started")
        assertThat(requests[0].time).isEqualTo(t0.toEpochMilli())
        assertThat(requests[1].text).isEqualTo("Cilium install finished")
        assertThat(requests[1].time).isEqualTo(t0.plusSeconds(90).toEpochMilli())
        assertThat(requests).allSatisfy {
            assertThat(it.tags).containsExactly(Constants.Cilium.ANNOTATION_TAG, Constants.Grafana.GLOBAL_ANNOTATION_TAG)
            assertThat(it.timeEnd).isNull()
            assertThat(it.dashboardUID).isNull()
        }
        assertThat(posted.map { it.response.id }).containsExactly(1L, 2L)
        assertThat(posted.map { it.request.text }).containsExactly("Cilium install started", "Cilium install finished")
        assertThat(annotator.pending).isEmpty()
    }

    @Test
    fun `installFailed puts the error in the annotation body`() {
        val annotator = CiliumInstallAnnotator(mock(), Clock.fixed(t0, ZoneOffset.UTC))

        annotator.installStarted()
        annotator.installFailed("agent panicked: egress masquerading interfaces cannot be empty")

        assertThat(annotator.pending.map { it.text }).containsExactly(
            "Cilium install started",
            "Cilium install failed: agent panicked: egress masquerading interfaces cannot be empty",
        )
    }

    @Test
    fun `post with nothing recorded calls Grafana zero times`() {
        val grafana = mock<GrafanaDashboardService>()
        val annotator = CiliumInstallAnnotator(grafana)

        val posted = annotator.post(controlHost).getOrThrow()

        assertThat(posted).isEmpty()
        verify(grafana, never()).createAnnotation(any(), any())
    }

    @Test
    fun `a failed post keeps the unposted annotations pending and drops the posted one`() {
        val grafana = mock<GrafanaDashboardService>()
        whenever(grafana.createAnnotation(any(), any()))
            .doReturn(GrafanaAnnotationResponse(id = 1))
            .doThrow(IllegalStateException("Grafana annotation API at http://10.0.0.1:3000 returned 502"))
        val annotator = CiliumInstallAnnotator(grafana, Clock.fixed(t0, ZoneOffset.UTC))
        annotator.installStarted()
        annotator.installFinished()

        val result = annotator.post(controlHost)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageContaining("returned 502")
        assertThat(annotator.pending.map { it.text }).containsExactly("Cilium install finished")
    }
}
