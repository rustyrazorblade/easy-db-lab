package com.rustyrazorblade.easydblab.events

/**
 * Interface for components that receive event envelopes from the [EventBus].
 *
 * Implementations handle events in domain-specific ways:
 * - [ConsoleEventListener] renders events to stdout/stderr
 * - McpEventListener streams structured events to MCP clients
 * - RedisEventListener publishes events to Redis pub/sub
 */
interface EventListener {
    /**
     * Called when an event is dispatched by the [EventBus].
     *
     * @param envelope The event envelope containing the event and metadata
     */
    fun onEvent(envelope: EventEnvelope)

    /**
     * Called when the listener should release resources.
     */
    fun close()
}
