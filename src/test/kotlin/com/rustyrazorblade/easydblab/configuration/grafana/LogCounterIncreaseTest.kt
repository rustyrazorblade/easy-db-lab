package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The collector counts log records into `cassandra_log_*` counters only when a record arrives, and
 * restarts a counter after 5 idle minutes (`delta_to_cumulative` `max_stale`). A logger that warns
 * once in a window therefore has one sample there, and `increase()` needs two, so Neo4j's "WARN+ Log
 * Records by Logger" showed nothing for such a logger. Every `increase()` over one of these counters
 * also takes the last value of a series born in the window.
 */
class LogCounterIncreaseTest {
    @Test
    fun `an increase over a log counter that misses a series born in the window is reported`() {
        val m = """cassandra_log_records_total{job="neo4j"}"""

        assertThat(problems("sum by (cluster, logger) (increase($m[\$__range]))")).hasSize(1)
        assertThat(
            problems(
                "sum by (cluster, logger) ((last_over_time($m[\$__range]) unless last_over_time($m[\$__range] offset \$__range)) or increase($m[\$__range]))",
            ),
        ).isEmpty()
    }

    @Test
    fun `every dashboard counts a log counter's series born in the window`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                ClusterFilterGuards
                    .queries(Json.parseToJsonElement(file.readText()).jsonObject)
                    .flatMap { query -> problems(query.text).map { "${file.path} ${query.where}: $it" } }
            }

        assertThat(found).isEmpty()
    }

    private fun problems(query: String): List<String> =
        LOG_COUNTER_INCREASE
            .findAll(query)
            .filterNot { "unless last_over_time(${it.groupValues[1]}" in query }
            .map { "increase(${it.groupValues[1]}...) misses a series born in the window" }
            .toList()

    private companion object {
        val LOG_COUNTER_INCREASE = Regex("""increase\((cassandra_log_\w+)""")
    }
}
