package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants

/**
 * The identity of an experiment: one test, which groups one or more clusters of one tenant.
 *
 * An experiment is (tenant, name). Names are unique within a tenant only, so the same name in two
 * tenants is two experiments, and nothing checks one tenant's names against another's. This is the
 * one place that says so: `init` checks both rules through it, and the report, export and
 * dashboard code take a key rather than a bare name.
 *
 * @property tenant the observability tenant the experiment's clusters belong to.
 * @property name the experiment's name within [tenant].
 * @throws IllegalArgumentException if either breaks its rule; the message states the rule.
 */
data class ExperimentKey(
    val tenant: String,
    val name: String,
) {
    init {
        requireValidTenant(tenant)
        requireValidName(name)
    }

    /**
     * The experiment picker's option value, `<tenant>/<name>`. Neither rule allows `/`, so the
     * value splits back into its parts. This is the only builder of it.
     */
    val pickerValue: String get() = "$tenant/$name"

    /** The two rules of an experiment key, usable before a key can be built. */
    companion object {
        private val TENANT = Regex(Constants.Observability.TENANT_PATTERN)
        private val NAME = Regex(Constants.Observability.EXPERIMENT_PATTERN)

        /**
         * Checks [tenant] against [Constants.Observability.TENANT_PATTERN] and
         * [Constants.Observability.RESERVED_TENANT].
         *
         * @throws IllegalArgumentException naming the broken rule.
         */
        fun requireValidTenant(tenant: String) {
            require(TENANT.matches(tenant)) {
                "Invalid tenant '$tenant': a tenant must match ${Constants.Observability.TENANT_PATTERN} " +
                    "(start with a lowercase letter; then lowercase letters, digits, '_' or '-'; at most 63 characters)."
            }
            require(tenant != Constants.Observability.RESERVED_TENANT) {
                "Invalid tenant '$tenant': '${Constants.Observability.RESERVED_TENANT}' is reserved " +
                    "(a Loki tenant with that name would collide with Loki's index path)."
            }
        }

        /**
         * Checks [name] against [Constants.Observability.EXPERIMENT_PATTERN].
         *
         * @throws IllegalArgumentException stating the rule.
         */
        fun requireValidName(name: String) {
            require(NAME.matches(name)) {
                "Invalid experiment '$name': an experiment name must match ${Constants.Observability.EXPERIMENT_PATTERN} " +
                    "(start with a lowercase letter or a digit; then lowercase letters, digits, '_' or '-'; " +
                    "at most 80 characters)."
            }
        }
    }
}
