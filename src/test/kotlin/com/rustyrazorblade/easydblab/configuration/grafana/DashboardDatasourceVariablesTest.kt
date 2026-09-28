package com.rustyrazorblade.easydblab.configuration.grafana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every core and kit dashboard follows the tenant pickers: no fixed `mimir`, `loki` or `tempo`
 * uid anywhere, a picker for each signal it uses, and links that carry the pickers and the selected
 * clusters. The nested classes test the checks themselves on small samples.
 */
class DashboardDatasourceVariablesTest {
    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `no dashboard names a fixed datasource uid`() {
        val files = DashboardFiles.all()
        assertThat(files).isNotEmpty()

        val found = files.flatMap { file -> DashboardDatasourceGuards.fixedUids(parse(file)).map { "${file.path}: $it" } }
        assertThat(found).isEmpty()
    }

    @Test
    fun `every dashboard declares a picker for each signal it uses`() {
        val found = DashboardFiles.all().flatMap { file -> DashboardDatasourceGuards.missingPickers(parse(file)).map { "${file.path}: $it" } }
        assertThat(found).isEmpty()
    }

    @Test
    fun `every dashboard link carries the declared pickers and clusters`() {
        val found = DashboardFiles.all().flatMap { file -> DashboardDatasourceGuards.linkViolations(parse(file)).map { "${file.path}: $it" } }
        assertThat(found).isEmpty()
    }

    @Nested
    inner class FixedUids {
        @Test
        fun `a fixed uid in a panel is found with its path`() {
            val dashboard = parse("""{"panels":[{"datasource":{"type":"prometheus","uid":"mimir"}}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).containsExactly("$.panels[0].datasource.uid")
        }

        @Test
        fun `a fixed uid as a bare datasource string is found`() {
            val dashboard = parse("""{"panels":[{"datasource":"loki"}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).containsExactly("$.panels[0].datasource")
        }

        @Test
        fun `a fixed uid inside an encoded Explore link is found`() {
            val url =
                "/explore?schemaVersion=1&orgId=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22loki%22%2C%22queries%22%3A%5B%5D%7D%7D"
            val dashboard = parse("""{"panels":[{"links":[{"url":"$url"}]}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).containsExactly("$.panels[0].links[0].url")
        }

        @Test
        fun `a picker inside an encoded Explore link passes`() {
            val url =
                "/explore?panes=%7B%22a%22%3A%7B%22datasource%22%3A%22\${logs_datasource}%22%2C%22q%22%3A%22a+b%22%7D%7D"
            val dashboard = parse("""{"panels":[{"links":[{"url":"$url"}]}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).isEmpty()
        }

        @Test
        fun `a pyroscope uid is allowed`() {
            val dashboard = parse("""{"panels":[{"datasource":{"type":"grafana-pyroscope-datasource","uid":"pyroscope"}}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).isEmpty()
        }

        @Test
        fun `a picker reference passes`() {
            val dashboard = parse("""{"panels":[{"datasource":{"type":"prometheus","uid":"${'$'}{metrics_datasource}"}}]}""")

            assertThat(DashboardDatasourceGuards.fixedUids(dashboard)).isEmpty()
        }
    }

    @Nested
    inner class MissingPickers {
        @Test
        fun `a used type without its picker is reported`() {
            val dashboard =
                parse(
                    """
                    {"panels":[{"datasource":{"type":"prometheus","uid":"${'$'}{datasource}"}},
                               {"datasource":{"type":"loki","uid":"${'$'}{logs_datasource}"}}],
                     "templating":{"list":[{"name":"logs_datasource","type":"datasource","query":"loki"}]}}
                    """.trimIndent(),
                )

            assertThat(DashboardDatasourceGuards.missingPickers(dashboard)).containsExactly("prometheus (metrics_datasource)")
        }

        @Test
        fun `a type used only inside an Explore link needs its picker`() {
            val url = "/explore?panes=%7B%22a%22%3A%7B%22datasource%22%3A%7B%22type%22%3A%22tempo%22%7D%7D%7D"
            val dashboard = parse("""{"panels":[{"links":[{"url":"$url"}]}]}""")

            assertThat(DashboardDatasourceGuards.missingPickers(dashboard)).containsExactly("tempo (traces_datasource)")
        }

        @Test
        fun `declared pickers pass and pyroscope needs none`() {
            val dashboard =
                parse(
                    """
                    {"panels":[{"datasource":{"type":"prometheus","uid":"${'$'}{metrics_datasource}"}},
                               {"datasource":{"type":"grafana-pyroscope-datasource","uid":"pyroscope"}}],
                     "templating":{"list":[{"name":"metrics_datasource","type":"datasource","query":"prometheus"}]}}
                    """.trimIndent(),
                )

            assertThat(DashboardDatasourceGuards.missingPickers(dashboard)).isEmpty()
        }
    }

    @Nested
    inner class LinkRule {
        private fun dashboard(
            variables: List<String>,
            url: String,
        ): JsonObject {
            val list = variables.joinToString(",") { """{"name":"$it","type":"query"}""" }
            return parse("""{"panels":[{"links":[{"url":"$url"}]}],"templating":{"list":[$list]}}""")
        }

        @Test
        fun `a link that drops a declared variable is reported`() {
            val violations =
                DashboardDatasourceGuards.linkViolations(
                    dashboard(listOf("metrics_datasource", "cluster"), "/d/x/x?\${metrics_datasource:queryparam}"),
                )

            assertThat(violations).singleElement().asString().contains("does not pass cluster")
        }

        @Test
        fun `an explicit var value satisfies the rule`() {
            val violations =
                DashboardDatasourceGuards.linkViolations(
                    dashboard(listOf("metrics_datasource", "cluster"), "/d/x/x?\${metrics_datasource:queryparam}&var-cluster=\${__data.fields.cluster}"),
                )

            assertThat(violations).isEmpty()
        }

        @Test
        fun `an undeclared queryparam is reported`() {
            val violations =
                DashboardDatasourceGuards.linkViolations(dashboard(listOf("metrics_datasource"), "/d/x/x?\${metrics_datasource:queryparam}&\${cluster:queryparam}"))

            assertThat(violations).singleElement().asString().contains("passes undeclared cluster")
        }

        @Test
        fun `an Explore link is not a dashboard link`() {
            val violations = DashboardDatasourceGuards.linkViolations(dashboard(listOf("cluster"), "/explore?panes=x"))

            assertThat(violations).isEmpty()
        }

        @Test
        fun `a variable that is not carried need not be passed`() {
            val violations =
                DashboardDatasourceGuards.linkViolations(dashboard(listOf("host", "cluster"), "/d/x/x?\${cluster:queryparam}"))

            assertThat(violations).isEmpty()
        }
    }
}
