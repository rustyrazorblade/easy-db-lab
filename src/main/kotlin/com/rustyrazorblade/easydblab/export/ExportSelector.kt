package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ExperimentKey

/**
 * What one `observability export` copies: a whole tenant's store, the data of some clusters, or the
 * data of every cluster of one experiment. A command takes exactly one.
 */
sealed interface ExportSelector {
    /**
     * A file copy of [tenant]'s store.
     *
     * @property tenant the tenant.
     */
    data class Tenant(
        val tenant: String,
    ) : ExportSelector

    /**
     * An export by query of the clusters [ids], each `<name>-<id>`.
     *
     * @property ids the clusters, each once, in the order given.
     */
    data class Clusters(
        val ids: List<String>,
    ) : ExportSelector

    /**
     * An export by query of every cluster of [key] in its tenant.
     *
     * @property key the experiment and its tenant.
     */
    data class Experiment(
        val key: ExperimentKey,
    ) : ExportSelector
}

/**
 * The selector options as given on the command line, before they are checked.
 *
 * @property tenant `--tenant`: the tenant export, or the experiment's tenant.
 * @property clusters every `--cluster`.
 * @property experiment `--experiment`.
 */
data class ExportSelectorChoice(
    val tenant: String?,
    val clusters: List<String>,
    val experiment: String?,
)

/**
 * The tenant an experiment export reads, and why that one: the operator named it, it is the
 * workspace's, or there is no workspace and it is the default.
 *
 * @property tenant the tenant.
 * @property source why.
 */
data class TenantChoice(
    val tenant: String,
    val source: Source,
) {
    /** Where an experiment export's tenant came from. */
    enum class Source {
        /** `--tenant`. */
        OPTION,

        /** The cluster workspace the command runs in. */
        WORKSPACE,

        /** Neither: the default tenant. */
        DEFAULT,
    }
}

/**
 * A checked selector, and for an experiment, the tenant it reads and why.
 *
 * @property selector what the export copies.
 * @property tenantChoice the experiment's tenant and its source; null for the other selectors.
 */
data class ExportSelection(
    val selector: ExportSelector,
    val tenantChoice: TenantChoice?,
) {
    companion object {
        /** The three kinds of export, as every refusal names them. */
        const val CHOICES =
            "Choose exactly one kind of export: --tenant <tenant> (a copy of the tenant's store), " +
                "--cluster <name-id> (repeatable; those clusters only) or --experiment <name> " +
                "(every cluster of the experiment; add --tenant to name its tenant)."

        /**
         * Checks [choice]; [workspaceTenant] is the tenant of the workspace the command runs in, if any.
         *
         * @throws ExportSelectorRefused when [choice] names no kind of export or more than one.
         * @throws ClusterIdRefused when a `--cluster` value is not a cluster's `<name>-<id>`.
         * @throws IllegalArgumentException when another value breaks its rule.
         */
        fun of(
            choice: ExportSelectorChoice,
            workspaceTenant: String?,
        ): ExportSelection {
            choice.tenant?.let(ExperimentKey::requireValidTenant)
            require(choice.clusters.none { it.isBlank() }) { "--cluster needs a cluster's <name>-<id>; a blank value was given" }
            // Beside --experiment, --tenant names the experiment's tenant and is not a kind of its own.
            val tenant = choice.tenant?.takeIf { choice.experiment == null }
            val experiment = choice.experiment
            val clusters = choice.clusters.distinct()
            val kinds = listOfNotNull(tenant, clusters.ifEmpty { null }, experiment)
            if (kinds.size != 1) throw ExportSelectorRefused(CHOICES)
            ClusterIdRule.violations(clusters).takeIf { it.isNotEmpty() }?.let { throw ClusterIdRefused(it) }
            return when {
                tenant != null -> ExportSelection(ExportSelector.Tenant(tenant), tenantChoice = null)
                experiment != null -> experiment(experiment, choice.tenant, workspaceTenant)
                else -> ExportSelection(ExportSelector.Clusters(clusters), tenantChoice = null)
            }
        }

        private fun experiment(
            name: String,
            tenant: String?,
            workspaceTenant: String?,
        ): ExportSelection {
            val chosen =
                when {
                    tenant != null -> TenantChoice(tenant, TenantChoice.Source.OPTION)
                    workspaceTenant != null -> TenantChoice(workspaceTenant, TenantChoice.Source.WORKSPACE)
                    else -> TenantChoice(Constants.Observability.DEFAULT_TENANT, TenantChoice.Source.DEFAULT)
                }
            return ExportSelection(ExportSelector.Experiment(ExperimentKey(chosen.tenant, name)), chosen)
        }
    }
}

/**
 * The rule a cluster id must follow before it goes into a PromQL, LogQL or TraceQL selector, which
 * takes it unescaped: [Constants.Observability.CLUSTER_ID_PATTERN]. A `--cluster` value and a
 * cluster Mimir names for an experiment, a pre-label cluster's own id included, both follow it.
 */
object ClusterIdRule {
    private val CLUSTER_ID = Regex(Constants.Observability.CLUSTER_ID_PATTERN)

    /** Each of [values] that is not a cluster id, in order. */
    fun violations(values: List<String>): List<String> = values.filterNot(CLUSTER_ID::matches)
}

/** The selector options name no kind of export, or more than one. */
class ExportSelectorRefused(
    message: String,
) : IllegalArgumentException(message)

/**
 * Some `--cluster` values are not a cluster's `<name>-<id>` ([Constants.Observability.CLUSTER_ID_PATTERN]).
 *
 * @property clusters each such value, in the order given.
 */
class ClusterIdRefused(
    val clusters: List<String>,
) : IllegalArgumentException(
        "--cluster needs a cluster's <name>-<id> (${Constants.Observability.CLUSTER_ID_PATTERN}); " +
            "not a cluster id: ${clusters.joinToString(", ")}",
    )
