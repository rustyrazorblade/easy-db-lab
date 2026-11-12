package com.rustyrazorblade.easydblab.configuration

enum class ServerType(
    val serverType: String,
) {
    Cassandra("db"),
    Stress("app"),
    Control("control"),

    ;

    /**
     * The ordinal in a node alias of this type: the plain decimal number after [serverType]
     * (`db12` is 12). Null for an alias of another type or one whose suffix is not a number;
     * each caller picks its own fallback.
     */
    fun ordinalOf(alias: String): Int? =
        alias
            .takeIf { it.startsWith(serverType) }
            ?.substring(serverType.length)
            ?.takeIf { suffix -> suffix.isNotEmpty() && suffix.all { it.isDigit() } }
            ?.toIntOrNull()

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
