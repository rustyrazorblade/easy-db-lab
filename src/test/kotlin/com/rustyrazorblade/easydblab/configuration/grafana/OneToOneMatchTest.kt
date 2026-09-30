package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An arithmetic or comparison match `on (labels)` without `group_left`/`group_right` needs one series
 * per match group on each side. A raw selector has two whenever a label appears or changes on a host
 * (`node_role`, a collector upgrade), and for the few minutes both are live Mimir answers 422
 * ("found duplicate series for the match group"). System Overview's "Load per core" failed this way
 * at 24h. Each side of such a match is therefore an aggregation by the match labels.
 */
class OneToOneMatchTest {
    @Test
    fun `a one-to-one match on a raw selector is reported`() {
        val load = """system_cpu_load_average_1m{cluster=~"${'$'}cluster"}"""
        val cores = """system_cpu_logical_count{cluster=~"${'$'}cluster"}"""

        assertThat(problems("$load / on (cluster, host_name) $cores")).hasSize(2)
        assertThat(problems("max by (cluster, host_name) ($load) / on (cluster, host_name) $cores")).hasSize(1)
        assertThat(
            problems("max by (cluster, host_name) ($load) / on (cluster, host_name) (max by (cluster, host_name) ($cores))"),
        ).isEmpty()
        assertThat(problems("$load and on (cluster, host_name) $cores")).isEmpty()
        assertThat(problems("$load / on (cluster) group_left max by (cluster) ($cores)")).isEmpty()
    }

    @Test
    fun `every one-to-one match aggregates both sides`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                ClusterFilterGuards
                    .queries(Json.parseToJsonElement(file.readText()).jsonObject)
                    .flatMap { query -> problems(query.text).map { "${file.path} ${query.where}: $it" } }
            }

        assertThat(found).isEmpty()
    }

    private fun problems(query: String): List<String> =
        RAW_LEFT.findAll(query).map { "a raw selector left of '${it.value.trim()}'" }.toList() +
            RAW_RIGHT.findAll(query).map { "a raw selector right of '${it.groupValues[1].trim()}'" }.toList()

    private companion object {
        /** An arithmetic or comparison operator, then `on (...)` with no group modifier. */
        const val ONE_TO_ONE = """(?:[-+*/%^]|==|!=|>=|<=|>|<)\s*(?:bool\s+)?on\s*\([^)]*\)(?!\s*group_)"""

        /** A selector's closing brace right before the match: the left side is not aggregated. */
        val RAW_LEFT = Regex(""""\s*}\s*$ONE_TO_ONE""")

        /** A metric name and its braces right after the match: the right side is not aggregated. */
        val RAW_RIGHT = Regex("""($ONE_TO_ONE)\s*\(?\s*[a-zA-Z_:][\w:]*\{""")
    }
}
