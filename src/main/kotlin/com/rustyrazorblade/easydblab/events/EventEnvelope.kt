package com.rustyrazorblade.easydblab.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        private const val TYPE_PREFIX = "com.rustyrazorblade.easydblab.events.Event."

        private val json =
            Json {
                classDiscriminator = "type"
                encodeDefaults = true
            }

        fun toJson(envelope: EventEnvelope): String =
            json.encodeToString(serializer(), envelope).replace(TYPE_PREFIX, "")

        fun fromJson(jsonString: String): EventEnvelope {
            val tree = json.parseToJsonElement(jsonString).jsonObject
            val event = tree["event"]!!.jsonObject
            val shortType = event["type"]!!.jsonPrimitive.content
            val expandedEvent = JsonObject(event + ("type" to JsonPrimitive(TYPE_PREFIX + shortType)))
            val expandedTree = JsonObject(tree + ("event" to expandedEvent))
            return json.decodeFromJsonElement(serializer(), expandedTree)
        }
    }
}
