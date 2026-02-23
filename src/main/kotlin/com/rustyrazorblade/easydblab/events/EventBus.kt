package com.rustyrazorblade.easydblab.events

import java.time.Instant

/**
 * Central event dispatcher that receives domain events and fans them out to registered listeners.
 *
 * Usage:
 * ```kotlin
 * eventBus.emit(Event.Cassandra.Starting(host = "cassandra0"))
 * ```
 *
 * The bus reads [EventContext.current] for the command name, creates an [EventEnvelope]
 * with the current timestamp, and dispatches it to all registered [EventListener] instances.
 *
 * Thread-safe: the listener list is synchronized.
 */
class EventBus {
    private val listeners = mutableListOf<EventListener>()

    /**
     * Emit a domain event. The bus wraps it in an [EventEnvelope] with metadata
     * from [EventContext] and the current timestamp, then dispatches to all listeners.
     */
    fun emit(event: Event) {
        val envelope =
            EventEnvelope(
                event = event,
                timestamp = Instant.now().toString(),
                commandName = EventContext.current(),
            )
        val currentListeners = synchronized(listeners) { listeners.toList() }
        currentListeners.forEach { it.onEvent(envelope) }
    }

    /**
     * Register a listener to receive events.
     */
    fun addListener(listener: EventListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeListener(listener: EventListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Close all listeners and clear the list.
     */
    fun close() {
        val currentListeners =
            synchronized(listeners) {
                val copy = listeners.toList()
                listeners.clear()
                copy
            }
        currentListeners.forEach { it.close() }
    }
}
