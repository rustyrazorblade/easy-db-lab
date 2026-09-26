package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants

/**
 * Builds the LogQL queries this tool sends to Loki.
 *
 * Every query the tool builds is scoped to one cluster (`cluster="<name>-<id>"`) unless it says
 * otherwise, because clusters in one tenant share Loki's store. `cluster` and `source` are stream
 * labels; the host and the systemd unit are matched as label filters, which also read structured
 * metadata (journald lines carry the host on the record, not the resource). Values are written as
 * LogQL double-quoted strings, with quotes and backslashes escaped.
 */
object LogQl {
    /**
     * The `logs query` query for [cluster]: every option given narrows it, and with none every line
     * of the cluster matches.
     */
    fun logsQuery(
        cluster: String,
        source: String? = null,
        host: String? = null,
        unit: String? = null,
        grep: String? = null,
    ): String {
        val selector = listOfNotNull("cluster" to cluster, source?.let { "source" to it })
        val filters =
            listOfNotNull(
                host?.let { "| host_name=${quote(it)}" },
                unit?.let { "| systemd_unit=${quote(it)}" },
                grep?.let { "|= ${quote(it)}" },
            )
        return (listOf(streamSelector(selector)) + filters).joinToString(" ")
    }

    /**
     * The lines of the Spark job an EMR step ran in [cluster]. A step is named after its job, and
     * the Java agent names the job's service `spark-<job>`; no log line carries the EMR step id.
     */
    fun sparkStep(
        cluster: String,
        stepName: String,
    ): String = streamSelector(listOf("cluster" to cluster, "service_name" to Constants.EMR.SERVICE_NAME_PREFIX + stepName))

    /** Every Spark job's lines in [cluster]; the Java agent names each job's service `spark-<job>`. */
    fun sparkJobs(cluster: String): String = """{cluster=${quote(cluster)}, service_name=~"${Constants.EMR.SERVICE_NAME_PREFIX}.+"}"""

    /**
     * The lines of one trace in any cluster the query's tenant holds, by its `trace_id`.
     *
     * @param traceId the id, or the placeholder Grafana substitutes, e.g. `${'$'}{__trace.traceId}`.
     */
    fun traceToLogs(traceId: String): String = """{cluster=~".+"} | trace_id="$traceId""""

    private fun streamSelector(labels: List<Pair<String, String>>): String =
        labels.joinToString(", ", prefix = "{", postfix = "}") { (name, value) -> "$name=${quote(value)}" }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
