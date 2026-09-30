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
 * Only the EMR nodes' collector stamps `node_role`; the cluster's own hosts carry none. Without an
 * `allValue`, Grafana's All is the regex of the listed values, `(spark-master|spark-worker)` once EMR
 * ran in the time range, and every query filtered by it loses the cluster's own nodes. `.*` also
 * matches a series without the label.
 */
class NodeRoleVariableTest {
    @Test
    fun `every node_role variable's All matches a node without a node_role`() {
        val problems =
            DashboardFiles.all().flatMap { file ->
                val dashboard = Json.parseToJsonElement(file.readText()).jsonObject
                ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray)
                    .orEmpty()
                    .filterIsInstance<JsonObject>()
                    .filter { NODE_ROLE_VALUES.containsMatchIn(it.string("definition")) }
                    .mapNotNull { variable ->
                        "${file.path} variable '${variable.string("name")}': allValue '${variable.string("allValue")}'"
                            .takeUnless { variable.string("allValue") == ".*" }
                    }
            }

        assertThat(problems).isEmpty()
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val NODE_ROLE_VALUES = Regex("""label_values\(.*,\s*node_role\s*\)""")
    }
}
