package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A variable that lists `node_role` values has an All that matches every node, those without a
 * `node_role` too.
 *
 * Before the collector derived `node_role` from the host name, only the EMR nodes carried it. Without an
 * `allValue`, Grafana's All is the regex of the listed values, `(spark-master|spark-worker)` once EMR
 * ran in the time range, and every query filtered by it loses the cluster's own nodes. `.*` also
 * matches a series without the label.
 */
class NodeRoleVariableTest {
    @Test
    fun `a node_role variable without an All of dot-star is reported, whether its query is in definition or query`() {
        val inQuery = """{"name": "service", "query": {"query": "label_values(up, node_role)"}}"""
        val inDefinition = """{"name": "service", "definition": "label_values(up, node_role)", "allValue": ".*"}"""

        assertThat(problems(parse("""{"templating": {"list": [$inQuery]}}"""))).containsExactly("variable 'service': allValue ''")
        assertThat(problems(parse("""{"templating": {"list": [$inDefinition]}}"""))).isEmpty()
    }

    @Test
    fun `every node_role variable's All matches a node without a node_role`() {
        val problems = DashboardFiles.all().flatMap { file -> problems(parse(file.readText())).map { "${file.path} $it" } }

        assertThat(problems).isEmpty()
    }

    /** Each variable of [dashboard] that lists `node_role` values, in its `definition` or its editor `query`, with no `.*` All. */
    private fun problems(dashboard: JsonObject): List<String> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { variable ->
                val query = (variable["query"] as? JsonObject)?.string("query") ?: variable.string("query")
                listOf(variable.string("definition"), query).any { NODE_ROLE_VALUES.containsMatchIn(it) }
            }.mapNotNull { variable ->
                "variable '${variable.string("name")}': allValue '${variable.string("allValue")}'".takeUnless {
                    variable.string("allValue") ==
                        ".*"
                }
            }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val NODE_ROLE_VALUES = Regex("""label_values\(.*,\s*node_role\s*\)""")
    }
}
