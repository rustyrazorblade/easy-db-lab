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
