package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * System Overview and System A/B Comparison have a `role` picker, so an operator shows every db
 * node, or every app node, without picking hosts one by one. A host's role is read from its name
 * (owner decision): db0 is `db`, app1 is `app`, control0 is `control`, and an EMR node, named
 * `ip-...` by EC2, is `spark`. The host pickers list only hosts of the selected roles, and every
 * panel filters by the role, so a panel never shows a host the picker hides.
 */
class RolePickerTest {
    private val dashboards =
        listOf("dashboards/infrastructure/system-overview.json", "dashboards/infrastructure/system-ab-comparison.json")

    /** The host pickers of each dashboard. */
    private val hostPickers =
        mapOf(
            "system-overview.json" to listOf("hostname"),
            "system-ab-comparison.json" to listOf("baseline", "candidate"),
        )

    private fun parse(path: String): JsonObject = Json.parseToJsonElement(File(path).readText()).jsonObject

    private fun variables(dashboard: JsonObject): Map<String, JsonObject> =
        ((dashboard["templating"] as JsonObject)["list"] as JsonArray)
            .map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }

    @Test
    fun `the role picker maps each role to a host name pattern, and All to every host`() {
        for (path in dashboards) {
            val role = variables(parse(path)).getValue("role")

            assertThat(role.string("type")).describedAs(path).isEqualTo("custom")
            assertThat(role.string("query"))
                .describedAs(path)
                .isEqualTo("db : db[0-9]+,app : app[0-9]+,control : control[0-9]+,spark : ip-.+")
            assertThat(role["multi"]?.jsonPrimitive?.content).describedAs(path).isEqualTo("true")
            assertThat(role["includeAll"]?.jsonPrimitive?.content).describedAs(path).isEqualTo("true")
            assertThat(role.string("allValue")).describedAs(path).isEqualTo(".*")
            assertThat((role["current"] as JsonObject)["value"].toString()).describedAs(path).contains("\$__all")
        }
    }

    /** PromQL anchors a regex matcher, so each pattern must match a whole host name of its role only. */
    @Test
    fun `each role's pattern selects exactly the hosts of that role`() {
        val hosts = mapOf("db0" to "db", "db12" to "db", "app0" to "app", "control0" to "control", "ip-10-28-1-222" to "spark")

        for (path in dashboards) {
            val patterns =
                variables(parse(path))
                    .getValue("role")
                    .string("query")
                    .orEmpty()
                    .split(",")
                    .associate { it.substringBefore(" : ") to it.substringAfter(" : ") }
            for ((host, expected) in hosts) {
                assertThat(patterns.filterValues { Regex(it).matches(host) }.keys).describedAs("$path $host").containsExactly(expected)
            }
        }
    }

    @Test
    fun `the host pickers list only hosts of the selected roles`() {
        for (path in dashboards) {
            val declared = variables(parse(path))
            for (picker in hostPickers.getValue(File(path).name)) {
                val query = ((declared.getValue(picker)["query"] as JsonObject).string("query")).orEmpty()
                assertThat(query).describedAs("$path $picker").contains(ROLE_MATCHER)
            }
        }
    }

    @Test
    fun `every panel selector that reads a host metric filters by the role`() {
        val missing =
            dashboards.flatMap { path ->
                ClusterFilterGuards
                    .queries(parse(path))
                    .filter { it.language == Language.PROMQL && !it.where.startsWith("variable ") }
                    .flatMap { query ->
                        selectors
                            .findAll(query.text)
                            .map { it.value }
                            .filter { clusterMatcher.containsMatchIn(it) && ROLE_MATCHER !in it && TAG_ROLE_MATCHER !in it }
                            .map { "$path ${query.where}: $it" }
                            .toList()
                    }
            }

        assertThat(missing).isEmpty()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val ROLE_MATCHER = "host_name=~\"\${role:pipe}\""

        /** The CloudWatch series carry the host's name as `tag_Name`. */
        const val TAG_ROLE_MATCHER = "tag_Name=~\"\${role:pipe}\""
        val selectors = Regex("""\{[^{}]*}""")
        val clusterMatcher = Regex("""cluster=~?"\$\{?(cluster|baseline_cluster|candidate_cluster)""")
    }
}
