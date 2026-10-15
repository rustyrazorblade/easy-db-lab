package com.rustyrazorblade.easydblab.configuration.grafana

/**
 * The names of the dashboard template variables that the install-time pass and the dashboard
 * guard tests both depend on: the datasource pickers, the cluster pickers, the experiment picker
 * and the variables derived from it.
 *
 * A dashboard file, [DashboardDefaults] and every guard test must agree on these names; a rename in
 * one place and not another silently stops a default from being set or a rule from being checked.
 * They live here so that agreement is one edit.
 */
object DashboardVariables {
    /** The metrics datasource picker. */
    const val METRICS_DATASOURCE = "metrics_datasource"

    /** The logs datasource picker. */
    const val LOGS_DATASOURCE = "logs_datasource"

    /** The traces datasource picker. */
    const val TRACES_DATASOURCE = "traces_datasource"

    /** The cluster multi-select every dashboard that queries data declares. */
    const val CLUSTER = "cluster"

    /** The A side of a pair dashboard; it opens on the experiment's oldest cluster. */
    const val CLUSTER_A = "cluster_a"

    /** The B side of a pair dashboard; it defaults to the current cluster. */
    const val CLUSTER_B = "cluster_b"

    /** The experiment picker, whose value is `<tenant>/<name>` (`ExperimentKey.pickerValue`). */
    const val EXPERIMENT = "experiment"

    /** The hidden tenant part of the selected experiment, `.*` for "All". */
    const val EXP_TENANT = "exp_tenant"

    /** The hidden name part of the selected experiment, `.*` for "All". */
    const val EXP_NAME = "exp_name"

    /** The hidden custom variable holding the home tenant, the fallback tenant of a pre-label cluster. */
    const val HOME_TENANT = "home_tenant"

    /** The `<tenant>/<experiment>` report folder of the A side's cluster. */
    const val A_REPORT = "a_report"

    /** The `<tenant>/<experiment>` report folder of the B side's cluster. */
    const val B_REPORT = "b_report"

    /** The host role picker of the system dashboards. */
    const val ROLE = "role"

    /** The two sides of a pair dashboard, A then B. */
    val PAIR = listOf(CLUSTER_A, CLUSTER_B)

    /** The variables whose own query lists clusters or experiments, so it reads every cluster. */
    val CLUSTER_LISTS = setOf(CLUSTER, CLUSTER_A, CLUSTER_B, EXPERIMENT, EXP_TENANT, EXP_NAME)

    /** The variables a cluster filter may match `cluster` against: the cluster picker and the pair. */
    val CLUSTER_FILTERS = listOf(CLUSTER) + PAIR
}
