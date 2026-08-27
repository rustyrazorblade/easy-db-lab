package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Display-string and error-routing tests for [Event.Profiling].
 *
 * JSON round-tripping is covered for every event type by [EventSerializationTest], so it is not
 * repeated here.
 */
class ProfilingEventTest {
    @Test
    fun `started names the host, the user arguments, and the rotation interval`() {
        val event =
            Event.Profiling.Started(
                host = "db0",
                userArgs = listOf("-e", "wall", "-i", "10ms"),
                loopInterval = "1m",
            )

        assertThat(event.toDisplayString())
            .contains("db0")
            .contains("-e wall -i 10ms")
            .contains("1m")
        assertThat(event.isError()).isFalse()
    }

    @Test
    fun `stopped names the host`() {
        assertThat(Event.Profiling.Stopped("db1").toDisplayString()).contains("db1")
    }

    @Test
    fun `shipping failed is an error naming the host, the reason, and the failure count`() {
        val event =
            Event.Profiling.ShippingFailed(
                host = "db2",
                reason = "http_500",
                failures = 7,
            )

        assertThat(event.toDisplayString()).contains("db2").contains("http_500").contains("7")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `chunks rejected is a distinct error type from a shipping failure`() {
        val event = Event.Profiling.ChunksRejected(host = "db2", rejected = 3)

        assertThat(event.toDisplayString()).contains("db2").contains("3")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `attach failed is an error naming the host and the reason nothing is attached`() {
        val event =
            Event.Profiling.AttachFailed(
                host = "db3",
                reason = "Could not open /tmp/.java_pid4242",
            )

        assertThat(event.toDisplayString()).contains("db3").contains("Could not open /tmp/.java_pid4242")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `attach deferred is not an error, unlike a failing attach`() {
        // A node waiting for its database to become ready is the normal state for a pass or two
        // after any restart. Reporting it as an error would make every node start look like a
        // fault — and would tell an operator to go and fix something that is already resolving.
        val event = Event.Profiling.AttachDeferred(host = "db7")

        assertThat(event.toDisplayString()).contains("db7").contains("waiting")
        assertThat(event.isError()).isFalse()
    }

    @Test
    fun `desired state unreadable names the bounds the operator is losing`() {
        val event =
            Event.Profiling.DesiredStateUnreadable(
                host = "db4",
                retentionMinutes = 60,
                maxBytes = 2147483648,
            )

        assertThat(event.toDisplayString()).contains("db4").contains("60")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `state stale is a distinct error from a failing attach`() {
        // The two send an operator to different places: AttachFailed means the profiler will not
        // attach, StateStale means the thing that manages the profiler has stopped running, so
        // nothing else in the report can be trusted.
        val event =
            Event.Profiling.StateStale(
                host = "db5",
                ageSeconds = 7200,
                expectedIntervalSeconds = 60,
            )

        assertThat(event.toDisplayString()).contains("db5").contains("7200").contains("60")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `node config unreadable says the file is wrong, not the reconciler`() {
        // The distinction this event exists for. StateStale tells an operator the thing managing
        // the profiler has stopped; this one says that thing is running perfectly and the document
        // it reads is corrupt — opposite places to go looking.
        val event = Event.Profiling.NodeConfigUnreadable(host = "db6", reason = "config_unreadable")

        assertThat(event.toDisplayString())
            .contains("db6")
            .contains("config_unreadable")
            .contains("last known bounds")
            .contains("profile start")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `chunks lost reports profiles that can never be recovered`() {
        // Distinct from ChunksRejected: a rejected chunk is one Pyroscope refused and is still on
        // disk to look at, while these were deleted by pruning before they ever shipped.
        val event = Event.Profiling.ChunksLost(host = "db7", lost = 12)

        assertThat(event.toDisplayString())
            .contains("db7")
            .contains("12")
            .contains("cannot be recovered")
        assertThat(event.isError()).isTrue()
    }

    @Test
    fun `chunks fetched reports the count and where they landed`() {
        val event = Event.Profiling.ChunksFetched(host = "db0", chunks = 5, destination = "profiles/db0")

        assertThat(event.toDisplayString()).contains("db0").contains("5").contains("profiles/db0")
        assertThat(event.isError()).isFalse()
    }

    @Test
    fun `flamegraph created reports the local path and how many chunks went into it`() {
        val event = Event.Profiling.FlamegraphCreated(host = "db0", chunks = 5, path = "profiles/db0/flame.html")

        assertThat(event.toDisplayString()).contains("profiles/db0/flame.html").contains("5")
        assertThat(event.isError()).isFalse()
    }
}
