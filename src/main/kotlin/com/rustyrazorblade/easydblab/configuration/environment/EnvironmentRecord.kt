package com.rustyrazorblade.easydblab.configuration.environment

import com.fasterxml.jackson.databind.JsonNode

/**
 * Checks the `environment` value of a `state.json` before it is bound to an [Environment], so a
 * record this easy-db-lab cannot read fails with a message that says what is wrong with it.
 *
 * Binding alone reports a missing type as the unknown value `null` and a missing variant field as a
 * generic parser error far from its cause. Each problem here is named instead: not an object, no
 * type, an unknown type, a variant field that is missing or blank, or one that is not an absolute path.
 */
object EnvironmentRecord {
    /**
     * The fields each variant cannot be read without, for every id in [Environment.TYPE_IDS]. A variant
     * that needs none is listed with no fields, so one left out of this map is a failing test, never a
     * variant read without checking its fields. Every field listed is a path, and must be absolute.
     */
    internal val requiredFields: Map<String, List<String>> =
        mapOf(
            Environment.EC2_K3S to emptyList(),
            Environment.K3S_DOCKER to listOf("dataDir"),
            Environment.KUBECONFIG to listOf("source"),
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
        val missing =
            requiredFields.getValue(id).firstOrNull { field ->
                val value = node.get(field)
                value == null || !value.isTextual || value.asText().isBlank()
            }
        if (missing != null) return "The '$id' execution environment in $path has no '$missing'."
        return requiredFields
            .getValue(id)
            .firstNotNullOfOrNull { field -> Environment.relativePathProblem(id, field, node.get(field).asText()) }
            ?.let { "$it, in $path." }
    }
}
