package com.rustyrazorblade.easydblab.configuration.environment

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks the `environment` value of a `state.json` before it is bound to an [Environment], so a
 * record this easy-db-lab cannot read fails with a message that says what is wrong with it.
 *
 * Binding alone reports a missing type as the unknown value `null` and a missing variant field as a
 * generic parser error far from its cause. Each problem here is named instead: not an object, no
 * type, an unknown type, or a variant field that is missing or blank.
 */
object EnvironmentRecord {
    /** The fields each variant cannot be read without; a variant not listed needs none. */
    private val requiredFields: Map<String, String> =
        mapOf(
            Environment.K3S_DOCKER to "dataDir",
            Environment.KUBECONFIG to "source",
        )

    /**
     * Why the `environment` [node] of the state at [path] cannot be read, or null when it can (or the
     * state records none, which reads as [Environment.Ec2K3s]).
     */
    fun problem(
        node: JsonNode?,
        path: String,
    ): String? {
        if (node == null || node.isNull) return null
        val known = "This easy-db-lab knows: ${Environment.TYPE_IDS.joinToString(", ")}."
        if (!node.isObject) {
            return "The execution environment in $path must be an object with a '${Environment.TYPE_PROPERTY}' field, " +
                "not ${node.nodeType.name.lowercase()} $node. $known"
        }
        val type = node.get(Environment.TYPE_PROPERTY)
        if (type == null || !type.isTextual || type.asText().isBlank()) {
            return "The execution environment in $path has no type ('${Environment.TYPE_PROPERTY}'). $known"
        }
        val id = type.asText()
        if (id !in Environment.TYPE_IDS) return "Unknown execution environment '$id' in $path. $known"
        val field = requiredFields[id] ?: return null
        val value = node.get(field)
        if (value == null || !value.isTextual || value.asText().isBlank()) {
            return "The '$id' execution environment in $path has no '$field'."
        }
        return null
    }
}
