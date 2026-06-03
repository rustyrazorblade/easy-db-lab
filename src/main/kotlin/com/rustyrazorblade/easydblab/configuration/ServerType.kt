package com.rustyrazorblade.easydblab.configuration

enum class ServerType(
    val serverType: String,
) {
    Cassandra("db"),
    Stress("app"),
    Control("control"),

    ;

    companion object {
        /**
         * Creates a [ServerType] from a kit node-type string.
         * Accepts "db" → [Cassandra], "app" → [Stress], "control" → [Control].
         *
         * @throws IllegalArgumentException for unrecognised values
         */
        fun from(type: String): ServerType =
            entries.firstOrNull { it.serverType == type }
                ?: throw IllegalArgumentException(
                    "Invalid node-type '$type'. Valid values: ${entries.joinToString { it.serverType }}",
                )
    }
}
