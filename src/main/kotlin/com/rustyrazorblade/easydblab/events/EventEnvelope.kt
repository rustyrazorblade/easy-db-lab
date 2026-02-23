package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Envelope wrapping a domain event with metadata.
 *
 * Created by [EventBus] at dispatch time. Callers never construct envelopes directly —
 * they emit events via `eventBus.emit(event)` and the bus reads [EventContext] for
 * the command name and adds the timestamp.
 *
 * @property event The domain event
 * @property timestamp ISO-8601 timestamp of when the event was emitted
 * @property commandName The innermost executing CLI command, or null for system events
 */
@Serializable
data class EventEnvelope(
    val event: Event,
    val timestamp: String,
    val commandName: String? = null,
) {
    companion object {
        private val json =
            Json {
                classDiscriminator = "type"
                encodeDefaults = true
            }

        fun toJson(envelope: EventEnvelope): String = json.encodeToString(serializer(), envelope)

        fun fromJson(jsonString: String): EventEnvelope = json.decodeFromString(serializer(), jsonString)
    }
}
