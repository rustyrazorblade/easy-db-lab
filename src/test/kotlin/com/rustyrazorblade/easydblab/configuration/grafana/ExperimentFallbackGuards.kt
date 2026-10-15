package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finds the dashboard queries that would lose the clusters from before the `experiment` label.
 *
 * Such a cluster's series carry no `experiment` and no `edl_tenant`, so a query that matches on
 * either label silently skips it. It is an experiment of one, named for its `<name>-<id>`: a query
 * that matches on the labels must also read `up{experiment=""}`, joined by `or`, and a query that
 * writes a tenant for it must fall back to the hidden `home_tenant` when Mimir's `__tenant_id__` is
 * absent. Logs and traces filter on the cluster only and never read the two labels. Each check
 * returns what it found, so a test reports every problem at once and the checks themselves can be
 * tested on small samples.
 */
object ExperimentFallbackGuards {
    /** The two labels a pre-label cluster's series lack. */
    private val labels = listOf(DashboardVariables.EXPERIMENT, EDL_TENANT)

    /** A matcher on one of [labels]: its name, operator and value. */
    private val labelMatcher = Regex("""(?<![\w.])(${labels.joinToString("|")})\s*(=~|!~|!=|=)\s*"([^"]*)"""")

    /** The selector of the unlabelled series, `up{... experiment="" ...}`. */
    private val unlabelledUp = Regex("""\bup\s*\{[^}]*(?<![\w.])experiment\s*=\s*""[^}]*}""")
    private val orOperator = Regex("""\bor\b""")
    private val labelWord = Regex("""(?<![\w.$])(${labels.joinToString("|")})(?!\w)""")
    private val tracesOrLogsWord = Regex("""(?<!\w)(${labels.joinToString("|")})(?!\w)""")
    private val homeTenantReference = Regex("""\$\{?${DashboardVariables.HOME_TENANT}\b""")
    private const val HOME_TENANT_LITERAL = "\"\${${DashboardVariables.HOME_TENANT}}\""

    /**
     * Rule (a): each `experiment` or `edl_tenant` matcher of a PromQL [query], other than
     * `experiment=""`, when the query holds no `up{...experiment=""...}` selector joined by `or`.
     */
    fun missingUnlabelledBranch(query: Query): List<String> {
        if (query.language != Language.PROMQL) return emptyList()
        val matchers = labelMatchers(query.text).filterNot { it.isUnlabelled }.map { it.text }
        val branched = unlabelledUp.containsMatchIn(query.text) && orOperator.containsMatchIn(query.text)
        return if (branched) emptyList() else matchers
    }

    /**
     * Rule (b): a PromQL [query] that outputs `edl_tenant`, by grouping on it or building it with a
     * label function, without the `"${'$'}{home_tenant}"` fallback for a cluster with no tenant label.
     * A query that only matches on `edl_tenant` outputs no tenant.
     */
    fun missingHomeTenant(query: Query): List<String> {
        if (query.language != Language.PROMQL) return emptyList()
        val withoutMatchers = labelMatcher.replace(query.text, "")
        val outputs = labelWord.findAll(withoutMatchers).any { it.value == EDL_TENANT }
        return if (outputs && HOME_TENANT_LITERAL !in query.text) {
            listOf("outputs $EDL_TENANT without the $HOME_TENANT_LITERAL fallback")
        } else {
            emptyList()
        }
    }

    /**
     * Rule (c): a [dashboard] that references `home_tenant` anywhere and does not declare it as a
     * hidden custom variable, the form the install-time pass fills in.
     */
    fun undeclaredHomeTenant(dashboard: JsonObject): List<String> {
        if (strings(dashboard).none { homeTenantReference.containsMatchIn(it) }) return emptyList()
        val variable =
            ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .firstOrNull { it.string("name") == DashboardVariables.HOME_TENANT }
        return when {
            variable == null -> listOf("references ${DashboardVariables.HOME_TENANT} but does not declare it")
            variable.string("type") != "custom" || variable.string("hide") != HIDDEN ->
                listOf("${DashboardVariables.HOME_TENANT} is not a hidden custom variable (type custom, hide $HIDDEN)")
            else -> emptyList()
        }
    }

    /** Rule (d): each experiment label a LogQL or TraceQL [query] names. */
    fun experimentInLogsOrTraces(query: Query): List<String> =
        if (query.language == Language.LOGQL || query.language == Language.TRACEQL) {
            tracesOrLogsWord.findAll(query.text).map { it.value }.toList()
        } else {
            emptyList()
        }

    private data class LabelMatcher(
        val text: String,
        val isUnlabelled: Boolean,
    )

    private fun labelMatchers(text: String): List<LabelMatcher> =
        labelMatcher
            .findAll(text)
            .map { match ->
                val (name, operator, value) = match.destructured
                LabelMatcher(match.value, name == DashboardVariables.EXPERIMENT && operator == "=" && value.isEmpty())
            }.toList()

    /** Every string in [element], at any depth. */
    private fun strings(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.values.flatMap { strings(it) }
            is JsonArray -> element.flatMap { strings(it) }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull.takeIf { element.isString })
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The label the dashboards read a cluster's tenant from. */
    private const val EDL_TENANT = "edl_tenant"

    /** Grafana's `hide` value for a variable shown neither as a label nor as a picker. */
    private const val HIDDEN = "2"
}
