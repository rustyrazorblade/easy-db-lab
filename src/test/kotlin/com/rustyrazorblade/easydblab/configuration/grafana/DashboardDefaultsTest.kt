package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [DashboardDefaults], the install-time pass: it fills in the defaults the shared
 * dashboard files cannot hold, and changes nothing else.
 */
class DashboardDefaultsTest {
    private val context =
        DashboardInstallContext(
            cluster = "lab-abc123",
            experiment = ExperimentKey("acme", "e1"),
            tenants = TenantSet.of("acme", listOf("default")),
            documentsUrl = "http://10.0.0.5:3080",
        )

    /** The live local stack: no current cluster and no experiment, only a home tenant. */
    private val liveLocal = context.copy(cluster = null, experiment = null)

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun variable(
        dashboard: JsonObject,
        name: String,
    ): JsonObject =
        dashboard
            .getValue("templating")
            .jsonObject
            .getValue("list")
            .jsonArray
            .map { it.jsonObject }
            .single { it["name"]?.jsonPrimitive?.content == name }

    private fun current(
        dashboard: JsonObject,
        name: String,
    ): JsonElement = variable(dashboard, name).getValue("current").jsonObject.getValue("value")

    private fun dashboardWith(vararg variables: String): JsonObject = parse("""{"templating":{"list":[${variables.joinToString(",")}]}}""")

    @Test
    fun `a multi-select cluster defaults to a one-element list of the current cluster`() {
        val out = DashboardDefaults.apply(dashboardWith("""{"name":"cluster","type":"query","multi":true,"current":{}}"""), context)

        assertThat(current(out, "cluster")).isEqualTo(JsonArray(listOf(Json.parseToJsonElement("\"lab-abc123\""))))
    }

    @Test
    fun `a single-select cluster defaults to the current cluster`() {
        val out = DashboardDefaults.apply(dashboardWith("""{"name":"cluster","type":"query","multi":false}"""), context)

        assertThat(current(out, "cluster").jsonPrimitive.content).isEqualTo("lab-abc123")
    }

    @Test
    fun `cluster_b defaults to the current cluster and cluster_a gets no default`() {
        val out =
            DashboardDefaults.apply(
                dashboardWith(
                    """{"name":"cluster_a","type":"query"}""",
                    """{"name":"cluster_b","type":"query"}""",
                ),
                context,
            )

        assertThat(variable(out, "cluster_a")).doesNotContainKey("current")
        assertThat(current(out, "cluster_b").jsonPrimitive.content).isEqualTo("lab-abc123")
    }

    @Test
    fun `on a dashboard that declares experiment, cluster gets no default so it opens on All`() {
        val out =
            DashboardDefaults.apply(
                dashboardWith(
                    """{"name":"experiment","type":"query"}""",
                    """{"name":"cluster","type":"query","multi":true,"includeAll":true}""",
                    """{"name":"cluster_b","type":"query"}""",
                ),
                context,
            )

        assertThat(variable(out, "cluster")).doesNotContainKey("current")
        assertThat(current(out, "cluster_b").jsonPrimitive.content).isEqualTo("lab-abc123")
    }

    @Test
    fun `experiment defaults to the cluster's tenant and name`() {
        val out = DashboardDefaults.apply(dashboardWith("""{"name":"experiment","type":"query"}"""), context)

        val selected = variable(out, "experiment").getValue("current").jsonObject
        assertThat(selected.getValue("value").jsonPrimitive.content).isEqualTo("acme/e1")
        assertThat(selected.getValue("text").jsonPrimitive.content).isEqualTo("acme/e1")
    }

    @Test
    fun `the live local stack sets no cluster, cluster_b or experiment default`() {
        val dashboard =
            dashboardWith(
                """{"name":"cluster","type":"query","multi":true}""",
                """{"name":"cluster_b","type":"query"}""",
                """{"name":"experiment","type":"query"}""",
            )

        assertThat(DashboardDefaults.apply(dashboard, liveLocal)).isEqualTo(dashboard)
    }

    @Test
    fun `an export with an experiment sets that experiment and no cluster`() {
        val export = liveLocal.copy(experiment = ExperimentKey("acme", "e2"))
        val out =
            DashboardDefaults.apply(
                dashboardWith(
                    """{"name":"experiment","type":"query"}""",
                    """{"name":"cluster_b","type":"query"}""",
                ),
                export,
            )

        assertThat(current(out, "experiment").jsonPrimitive.content).isEqualTo("acme/e2")
        assertThat(variable(out, "cluster_b")).doesNotContainKey("current")
    }

    @Test
    fun `a cluster picker's default shows the short name and keeps the full id as its value`() {
        val id = "lab-1a2b3c4d-aaaa-bbbb-cccc-dddddddddddd"
        val full = context.copy(cluster = id)
        val out =
            DashboardDefaults.apply(
                dashboardWith(
                    """{"name":"cluster","type":"query","multi":true}""",
                    """{"name":"cluster_b","type":"query"}""",
                ),
                full,
            )

        val multi = variable(out, "cluster").getValue("current").jsonObject
        assertThat(multi.getValue("text")).isEqualTo(JsonArray(listOf(JsonPrimitive("lab-1a2b3c4d"))))
        assertThat(multi.getValue("value")).isEqualTo(JsonArray(listOf(JsonPrimitive(id))))
        val single = variable(out, "cluster_b").getValue("current").jsonObject
        assertThat(single.getValue("text").jsonPrimitive.content).isEqualTo("lab-1a2b3c4d")
        assertThat(single.getValue("value").jsonPrimitive.content).isEqualTo(id)
    }

    @Test
    fun `each picker defaults to the stable datasource of its type`() {
        val out =
            DashboardDefaults.apply(
                dashboardWith(
                    """{"name":"metrics_datasource","type":"datasource","query":"prometheus","current":{}}""",
                    """{"name":"logs_datasource","type":"datasource","query":"loki"}""",
                    """{"name":"traces_datasource","type":"datasource","query":"tempo"}""",
                    """{"name":"KeeperDatasource","type":"datasource","query":"prometheus"}""",
                ),
                context,
            )

        assertThat(current(out, "metrics_datasource").jsonPrimitive.content).isEqualTo("mimir")
        assertThat(current(out, "logs_datasource").jsonPrimitive.content).isEqualTo("loki")
        assertThat(current(out, "traces_datasource").jsonPrimitive.content).isEqualTo("tempo")
        assertThat(current(out, "KeeperDatasource").jsonPrimitive.content).isEqualTo("mimir")
        assertThat(
            variable(out, "logs_datasource")
                .getValue("current")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        ).isEqualTo("Loki")
    }

    @Test
    fun `home_tenant holds one option, the home tenant, and selects it`() {
        val out =
            DashboardDefaults.apply(
                dashboardWith("""{"name":"home_tenant","type":"custom","hide":2,"query":"","options":[]}"""),
                liveLocal,
            )

        val homeTenant = variable(out, "home_tenant")
        assertThat(
            homeTenant.getValue("options").jsonArray.map {
                it.jsonObject
                    .getValue("value")
                    .jsonPrimitive.content
            },
        ).containsExactly("acme")
        assertThat(homeTenant.getValue("query").jsonPrimitive.content).isEqualTo("acme")
        assertThat(current(out, "home_tenant").jsonPrimitive.content).isEqualTo("acme")
    }

    @Test
    fun `doc_tenant is retired and passes through unchanged`() {
        val dashboard = dashboardWith("""{"name":"doc_tenant","type":"custom","query":"","options":[]}""")

        assertThat(DashboardDefaults.apply(dashboard, context)).isEqualTo(dashboard)
    }

    @Test
    fun `the documents placeholder becomes the web server URL`() {
        val dashboard =
            parse(
                """{"panels":[{"type":"text","options":{"content":"<iframe src=\"__DOCUMENTS_URL__/reports/x/index.html\"></iframe>"}}]}""",
            )

        val out = DashboardDefaults.apply(dashboard, context)

        assertThat(out.toString()).contains("http://10.0.0.5:3080/reports/x/index.html").doesNotContain("__DOCUMENTS_URL__")
    }

    @Test
    fun `a dashboard with none of these variables comes out equal`() {
        val dashboard =
            parse(
                """
                {"title":"t","panels":[{"targets":[{"expr":"rate(x[${'$'}__rate_interval])"}]}],
                 "templating":{"list":[{"name":"host","type":"query","query":"label_values(up, host_name)"}]}}
                """.trimIndent(),
            )

        assertThat(DashboardDefaults.apply(dashboard, context)).isEqualTo(dashboard)
    }

    /** Every `targets` array in [element], in document order. */
    private fun targets(element: JsonElement): List<JsonElement> =
        when (element) {
            is JsonObject -> element.flatMap { (key, value) -> (if (key == "targets") listOf(value) else emptyList()) + targets(value) }
            is JsonArray -> element.flatMap { targets(it) }
            else -> emptyList()
        }

    @Test
    fun `no query changes on a shipped dashboard`() {
        DashboardFiles.all().forEach { file ->
            val dashboard = parse(file.readText())

            val out = DashboardDefaults.apply(dashboard, context)

            assertThat(targets(out)).describedAs(file.path).isEqualTo(targets(dashboard))
            val queries = { d: JsonObject ->
                (
                    d["templating"]
                        ?.jsonObject
                        ?.get("list")
                        ?.jsonArray
                        .orEmpty()
                ).map { it.jsonObject }
                    .filter { it["name"]?.jsonPrimitive?.content != DashboardVariables.HOME_TENANT }
                    .map { it["query"] }
            }
            assertThat(queries(out)).describedAs(file.path).isEqualTo(queries(dashboard))
        }
    }
}
