package com.rustyrazorblade.easydblab.configuration.tenancy

import com.rustyrazorblade.easydblab.Constants

/**
 * The Kubernetes namespace easy-db-lab installs into, validated on construction. This is the only
 * place the rule lives: a DNS-1123 label (lower-case letters, digits and `-`, starting and ending
 * with a letter or digit) of at most [Constants.K8s.DNS_LABEL_MAX_LENGTH] characters, and never one
 * of Kubernetes' own system namespaces ([Constants.Tenancy.RESERVED_NAMESPACES]). `init --namespace`
 * builds one before it writes anything, so a recorded namespace is always valid.
 *
 * @throws IllegalArgumentException when [value] breaks the rule; the message names [value].
 */
@JvmInline
value class K8sNamespace(
    val value: String,
) {
    init {
        require(value.length <= Constants.K8s.DNS_LABEL_MAX_LENGTH) {
            "Invalid namespace '$value': a namespace is at most ${Constants.K8s.DNS_LABEL_MAX_LENGTH} characters."
        }
        require(DNS_1123_LABEL.matches(value)) {
            "Invalid namespace '$value': a namespace must be a DNS-1123 label (lower-case letters, digits " +
                "and '-', starting and ending with a letter or digit)."
        }
        require(value !in Constants.Tenancy.RESERVED_NAMESPACES) {
            "Invalid namespace '$value': '$value' is reserved for Kubernetes itself."
        }
    }

    override fun toString(): String = value

    private companion object {
        val DNS_1123_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
    }
}
