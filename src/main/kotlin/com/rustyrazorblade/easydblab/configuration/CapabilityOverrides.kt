package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

/**
 * The capability values an operator set with `init --capability <name>=<bool>`. A declared or probed
 * set can be wrong for a cluster the user brings (a policy engine that ignores dry-run, a webhook
 * that mutates instead of rejecting), and an override is the escape hatch: it wins over whatever the
 * environment declared or the probe found.
 *
 * Parsing is pure and rejects the whole list on the first bad entry, so `init` fails before it
 * records anything.
 */
@JvmInline
value class CapabilityOverrides private constructor(
    private val values: Map<NodeCapability, Boolean>,
) {
    /** [base] with every overridden capability added (`true`) or removed (`false`). */
    fun applyTo(base: NodeCapabilities): NodeCapabilities {
        val (added, removed) = values.entries.partition { it.value }
        return NodeCapabilities(base.present().toSet() + added.map { it.key } - removed.map { it.key }.toSet())
    }

    companion object {
        private val VALUES = mapOf("true" to true, "false" to false)

        /**
         * Parses `<name>=<bool>` entries, where `<name>` is a capability's serial name.
         *
         * @throws IllegalArgumentException on an unknown name, a value other than `true`/`false`, or
         *   one name given two different values; the message lists the valid names
         */
        fun parse(entries: List<String>): CapabilityOverrides {
            val values = mutableMapOf<NodeCapability, Boolean>()
            for (entry in entries) {
                val (capability, value) = parseEntry(entry)
                val previous = values.put(capability, value)
                require(previous == null || previous == value) {
                    "Capability '${capability.serialName}' is overridden as both true and false. " +
                        "Give ${Constants.Capabilities.OVERRIDE_OPTION} ${capability.serialName}=<bool> once."
                }
            }
            return CapabilityOverrides(values)
        }

        private fun parseEntry(entry: String): Pair<NodeCapability, Boolean> {
            val name = entry.substringBefore(Constants.Capabilities.OVERRIDE_SEPARATOR)
            val capability =
                requireNotNull(NodeCapability.fromSerialName(name)) {
                    "Unknown capability '$name' in ${Constants.Capabilities.OVERRIDE_OPTION} $entry. ${validNames()}"
                }
            val value =
                requireNotNull(
                    entry
                        .takeIf { Constants.Capabilities.OVERRIDE_SEPARATOR in it }
                        ?.substringAfter(Constants.Capabilities.OVERRIDE_SEPARATOR)
                        ?.let(VALUES::get),
                ) {
                    "Invalid ${Constants.Capabilities.OVERRIDE_OPTION} $entry for '$name': expected " +
                        "<name>=true or <name>=false. ${validNames()}"
                }
            return capability to value
        }

        private fun validNames(): String = "Valid capabilities: ${NodeCapability.entries.joinToString(", ") { it.serialName }}."
    }
}
