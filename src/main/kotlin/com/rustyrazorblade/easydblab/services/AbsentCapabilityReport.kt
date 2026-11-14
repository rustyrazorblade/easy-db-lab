package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapabilities

/**
 * What `init` tells the operator about the capabilities their cluster lacks: each absent capability,
 * why it is absent, and every stage that will not be deployed because of it. The stages come from
 * the same declarations `up` gates on ([ObservabilityStage]), so the report names exactly the
 * stages `up` later skips.
 */
object AbsentCapabilityReport {
    /** The report for [capabilities] against [stages], or null when nothing is absent. */
    fun render(
        capabilities: NodeCapabilities,
        stages: List<StageRequirement>,
    ): String? {
        val absent = capabilities.absent()
        if (absent.isEmpty()) return null
        val blocks =
            absent.joinToString("\n") { capability ->
                val disabled = stages.filter { it.requires == capability }.map { it.label }
                val consequence =
                    when {
                        disabled.isEmpty() -> "no component needs it yet"
                        else -> "disables: ${disabled.joinToString(", ")}"
                    }
                "  ${capability.serialName}: ${capability.absentReason}\n    $consequence"
            }
        return "Absent capabilities:\n$blocks"
    }
}
