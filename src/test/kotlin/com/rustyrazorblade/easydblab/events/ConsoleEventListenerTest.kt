package com.rustyrazorblade.easydblab.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ConsoleEventListenerTest {
    private val listener = ConsoleEventListener()

    @Test
    fun `normal event is written to stdout`() {
        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
        try {
            val envelope =
                EventEnvelope(
                    event = Event.Cassandra.Starting("node0"),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "start",
                )

            listener.onEvent(envelope)

            val output = capturedOut.toString().trim()
            assertThat(output).isEqualTo("Starting Cassandra on node0...")
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `error event is written to stderr`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        System.setErr(PrintStream(capturedErr))
        try {
            val envelope =
                EventEnvelope(
                    event = Event.Command.ExecutionError("Something went wrong"),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "up",
                )

            listener.onEvent(envelope)

            val output = capturedErr.toString().trim()
            assertThat(output).isEqualTo("Something went wrong")
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `error event is not written to stdout`() {
        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
        try {
            val envelope =
                EventEnvelope(
                    event = Event.Error("failure", "details"),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "up",
                )

            listener.onEvent(envelope)

            assertThat(capturedOut.toString()).isEmpty()
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun `an event that stays off the console prints nothing to either stream`() {
        val originalOut = System.out
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        System.setErr(PrintStream(captured))
        try {
            listOf(
                Event.Provision.NodeStepStarted(alias = "db0", step = "setup", attempt = 1),
                Event.Provision.NodeStepCompleted(alias = "db0", step = "setup", attempt = 1, durationMs = 1200),
            ).forEach { event ->
                listener.onEvent(EventEnvelope(event = event, timestamp = "2026-02-23T10:15:30.123Z", commandName = "up"))
            }

            assertThat(captured.toString()).isEmpty()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `a failed node step is printed to stderr`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        System.setErr(PrintStream(capturedErr))
        try {
            listener.onEvent(
                EventEnvelope(
                    event =
                        Event.Provision.NodeStepFailed(
                            alias = "db1",
                            step = "setup",
                            attempt = 1,
                            maxAttempts = 2,
                            reason = "exit 1",
                            willRetry = true,
                        ),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = "up",
                ),
            )

            assertThat(capturedErr.toString().trim()).isEqualTo("db1: setup failed (attempt 1 of 2): exit 1; retrying")
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `normal event is not written to stderr`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        System.setErr(PrintStream(capturedErr))
        try {
            val envelope =
                EventEnvelope(
                    event = Event.Message("hello"),
                    timestamp = "2026-02-23T10:15:30.123Z",
                    commandName = null,
                )

            listener.onEvent(envelope)

            assertThat(capturedErr.toString()).isEmpty()
        } finally {
            System.setErr(originalErr)
        }
    }
}
