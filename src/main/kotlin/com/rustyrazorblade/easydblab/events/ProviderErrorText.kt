package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kernel.ProviderPolicy
import com.rustyrazorblade.easydblab.kernel.ProviderRequest

/**
 * The console text of [Event.Command.ProviderError]: a headline that names the failed request and
 * the refusing policy when the handler knew them, then each policy violation on its own line.
 *
 * Kept out of `Event.kt` because it is the only event whose text depends on which optional facts
 * a handler filled in.
 */
internal object ProviderErrorText {
    fun headline(
        provider: String,
        kind: ProviderErrorKind,
        request: ProviderRequest?,
        policy: ProviderPolicy?,
    ): String =
        when (kind) {
            ProviderErrorKind.CREDENTIALS -> "$provider credentials are missing, expired or invalid."
            ProviderErrorKind.PERMISSION_DENIED ->
                request?.let { "$provider denied permission: ${denied(it)}." }
                    ?: "$provider denied permission for this operation."
            ProviderErrorKind.UNAVAILABLE -> "$provider is not available."
            ProviderErrorKind.REJECTED -> rejected(provider, request, policy)
        }

    /** One indented line per violation, or nothing when the policy gave none. */
    fun violations(policy: ProviderPolicy?): String =
        policy
            ?.violations
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n", prefix = "\n") { "  - $it" }
            .orEmpty()

    private fun denied(request: ProviderRequest): String {
        val who = request.principal?.let { "$it cannot " }.orEmpty()
        return "$who${request.verb} ${request.target()}${inNamespace(request)}"
    }

    private fun rejected(
        provider: String,
        request: ProviderRequest?,
        policy: ProviderPolicy?,
    ): String {
        if (request == null && policy == null) return "$provider rejected this operation."
        val what = request?.let { " ${it.verb} ${it.target()}${inNamespace(it)}" } ?: " this operation"
        val why = policy?.let { ": " + listOfNotNull(it.name, it.level).joinToString(" ") }.orEmpty()
        return "$provider rejected$what$why."
    }

    private fun inNamespace(request: ProviderRequest): String = request.namespace?.let { " in namespace $it" }.orEmpty()
}
