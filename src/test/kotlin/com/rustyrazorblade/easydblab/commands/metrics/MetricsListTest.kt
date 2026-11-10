package com.rustyrazorblade.easydblab.commands.metrics

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.test.get
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MetricsListTest : BaseKoinTest() {
    @Test
    fun `prints the 14 accepted names, one per line, and emits no events`() {
        val events = mutableListOf<Event>()
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured))
        try {
            MetricsList().execute()
        } finally {
            System.setOut(original)
        }

        assertThat(captured.toString().lines().filter { it.isNotBlank() })
            .containsExactly(
                "node.cpu",
                "node.memory",
                "node.disk_read",
                "node.disk_write",
                "node.filesystem_used",
                "node.network_receive",
                "node.network_transmit",
                "cassandra.read_p99",
                "cassandra.write_p99",
                "cassandra.read_ops",
                "cassandra.write_ops",
                "cassandra.compaction_pending",
                "cassandra.compaction_completed",
                "cassandra.compaction_bytes_written",
            )
        assertThat(events).isEmpty()
    }
}
