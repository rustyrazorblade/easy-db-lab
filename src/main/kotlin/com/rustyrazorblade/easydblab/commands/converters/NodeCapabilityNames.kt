package com.rustyrazorblade.easydblab.commands.converters

import com.rustyrazorblade.easydblab.configuration.NodeCapability

/**
 * The serial names of every [NodeCapability], as picocli completion candidates.
 *
 * `init --capability` names them in its help through `${COMPLETION-CANDIDATES}`, so a new capability
 * appears in the help and in shell completion without anyone editing the option's description.
 */
class NodeCapabilityNames : Iterable<String> {
    override fun iterator(): Iterator<String> = NodeCapability.entries.map { it.serialName }.iterator()
}
