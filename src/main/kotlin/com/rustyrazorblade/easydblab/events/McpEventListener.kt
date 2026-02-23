package com.rustyrazorblade.easydblab.events

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Collections

/**
 * EventListener implementation for the MCP server.
 *
 * Buffers EventEnvelopes for retrieval by the get_server_status tool.
 * Thread-safe for concurrent access from multiple emitting threads.
 */
class McpEventListener : EventListener {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val buffer = Collections.synchronizedList(mutableListOf<EventEnvelope>())

    override fun onEvent(envelope: EventEnvelope) {
        buffer.add(envelope)
        log.debug { "MCP buffered event: ${envelope.event::class.simpleName}" }
    }

    override fun close() {
        buffer.clear()
    }

    /**
     * Returns all buffered envelopes and clears the buffer atomically.
     */
    fun getAndClearEnvelopes(): List<EventEnvelope> {
        synchronized(buffer) {
            val envelopes = buffer.toList()
            buffer.clear()
            return envelopes
        }
    }

    /**
     * Returns the number of buffered envelopes.
     */
    fun size(): Int = buffer.size
}
