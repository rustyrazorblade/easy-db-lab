package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.LabelSelector

/**
 * Matches a Kubernetes [LabelSelector] against a set of labels in memory, with the API server's rules.
 *
 * It exists so the rollout wait can list a namespace's pods once and pick out each workload's pods
 * itself, instead of asking the API server for one filtered list per workload on every poll. Every
 * `matchLabels` entry must hold and every `matchExpressions` requirement must hold. An empty selector
 * matches everything; an operator Kubernetes does not define matches nothing.
 */
object LabelSelectorMatcher {
    /** Whether [labels] satisfy [selector]. */
    fun matches(
        selector: LabelSelector,
        labels: Map<String, String>?,
    ): Boolean {
        val actual = labels.orEmpty()
        val byLabel = selector.matchLabels.orEmpty().all { (key, value) -> actual[key] == value }
        return byLabel &&
            selector.matchExpressions.orEmpty().all { requirement ->
                val values = requirement.values.orEmpty()
                when (requirement.operator) {
                    IN -> actual[requirement.key] in values
                    NOT_IN -> actual[requirement.key] !in values
                    EXISTS -> requirement.key in actual
                    DOES_NOT_EXIST -> requirement.key !in actual
                    else -> false
                }
            }
    }

    private const val IN = "In"
    private const val NOT_IN = "NotIn"
    private const val EXISTS = "Exists"
    private const val DOES_NOT_EXIST = "DoesNotExist"
}
