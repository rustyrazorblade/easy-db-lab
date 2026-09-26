package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.YamlTestSupport.nodeAt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GrafanaDatasourceConfigTest {
    @Test
    fun `createDatasourceConfig should include all datasources`() {
        val config = GrafanaDatasourceConfig.create("acme")

        assertThat(config.datasources.map { it.name }).containsExactlyInAnyOrder("Mimir", "Loki", "Tempo", "Pyroscope")
        assertThat(config.datasources.map { it.uid }).containsExactlyInAnyOrder("mimir", "loki", "tempo", "pyroscope")
    }

    @Test
    fun `metrics come from Mimir's Prometheus API and logs from Loki`() {
        val yaml = GrafanaDatasourceConfig.create("acme").toYaml()

        val mimir = provisioned(yaml, "Mimir")
        assertThat(mimir.scalar("type")).isEqualTo("prometheus")
        assertThat(mimir.scalar("url")).isEqualTo("http://localhost:9009/prometheus")
        assertThat(mimir.scalar("isDefault")).isEqualTo("true")

        val loki = provisioned(yaml, "Loki")
        assertThat(loki.scalar("type")).isEqualTo("loki")
        assertThat(loki.scalar("url")).isEqualTo("http://localhost:3100")
        assertThat(yaml).doesNotContainIgnoringCase("victoria")
    }

    /** A log line's trace_id (structured metadata) links to the trace in Tempo. */
    @Test
    fun `a log line's trace id links to Tempo`() {
        val loki = provisioned(GrafanaDatasourceConfig.create("acme").toYaml(), "Loki")
        val field =
            (
                loki
                    .get<YamlMap>("jsonData")
                    ?.get<YamlList>("derivedFields")
                    ?.items
                    .orEmpty()
            ).single() as YamlMap

        assertThat(field.scalar("matcherType")).isEqualTo("label")
        assertThat(field.scalar("matcherRegex")).isEqualTo("trace_id")
        assertThat(field.scalar("datasourceUid")).isEqualTo("tempo")
        assertThat(field.scalar("url")?.let(::grafanaEnvInterpolate)).isEqualTo("\${__value.raw}")
        assertThat(loki.get<YamlMap>("jsonData")?.get<YamlList>("logLevelRules")).isNull()
    }

    /** Trace views link to Mimir for the service map and span metrics, and to Loki for logs. */
    @Test
    fun `Tempo links to Mimir and Loki`() {
        val tempo = provisioned(GrafanaDatasourceConfig.create("acme").toYaml(), "Tempo")

        assertThat(tempo.scalar("jsonData", "serviceMap", "datasourceUid")).isEqualTo("mimir")
        assertThat(tempo.scalar("jsonData", "tracesToMetrics", "datasourceUid")).isEqualTo("mimir")
        assertThat(tempo.scalar("jsonData", "tracesToLogsV2", "datasourceUid")).isEqualTo("loki")
    }

    @Test
    fun `toYaml should produce valid YAML output`() {
        val config = GrafanaDatasourceConfig.create("acme")
        val yaml = config.toYaml()

        assertThat(yaml).contains("apiVersion: 1")
        assertThat(yaml).contains("Mimir")
        assertThat(yaml).contains("prometheus")
    }

    /** The provisioned datasource named [name], as Grafana reads it from the rendered YAML. */
    private fun provisioned(
        yaml: String,
        name: String,
    ): YamlMap =
        (nodeAt(yaml, "datasources") as YamlList)
            .items
            .map { it as YamlMap }
            .single { it.get<YamlScalar>("name")?.content == name }

    private fun YamlMap.scalar(vararg path: String): String? {
        var node: YamlMap? = this
        for (key in path.dropLast(1)) node = node?.get<YamlMap>(key)
        return node?.get<YamlScalar>(path.last())?.content
    }

    /**
     * Every backend runs native multi-tenancy, so a query with no tenant reads nothing. Each
     * datasource sends the cluster's tenant on every query, as a provisioned custom HTTP header.
     */
    @Test
    fun `every datasource queries the cluster's tenant`() {
        val yaml = GrafanaDatasourceConfig.create("acme").toYaml()

        for (name in listOf("Mimir", "Loki", "Tempo", "Pyroscope")) {
            val ds = provisioned(yaml, name)
            assertThat(ds.scalar("jsonData", "httpHeaderName1")).describedAs(name).isEqualTo("X-Scope-OrgID")
            assertThat(ds.scalar("secureJsonData", "httpHeaderValue1")?.let(::grafanaEnvInterpolate)).describedAs(name).isEqualTo("acme")
        }
    }

    /**
     * Grafana expands environment variables in every provisioned value: it splits the value on `$$`,
     * runs Go's `os.ExpandEnv` on each part, and joins the parts with a literal `$`
     * (`interpolateValue`, pkg/services/provisioning/values/values.go, v13.2.2). None of the
     * variables exist in its environment, so this model expands each one to an empty string.
     */
    private fun grafanaEnvInterpolate(value: String): String =
        value.split("$$").joinToString("$") { it.replace(Regex("""\$\{[^}]*}|\$[A-Za-z0-9_]+"""), "") }

    /**
     * The trace-to-logs and trace-to-metrics links hold Grafana macros that Grafana fills in when
     * the link is clicked. The provisioned values escape them, so they survive Grafana's
     * provisioning-time env expansion. Unescaped, `${__trace.traceId}` became an empty trace id.
     */
    @Test
    fun `the Tempo trace links keep their Grafana macros after provisioning env expansion`() {
        val tempo = provisioned(GrafanaDatasourceConfig.create("acme").toYaml(), "Tempo")

        assertThat(tempo.scalar("jsonData", "tracesToLogsV2", "query")?.let(::grafanaEnvInterpolate))
            .isEqualTo("{cluster=~\".+\"} | trace_id=\"\${__trace.traceId}\"")
        val metricQueries =
            tempo
                .get<YamlMap>("jsonData")
                ?.get<YamlMap>("tracesToMetrics")
                ?.get<YamlList>("queries")
                ?.items
                .orEmpty()
                .map { grafanaEnvInterpolate((it as YamlMap).scalar("query").orEmpty()) }
        assertThat(metricQueries).isNotEmpty.allSatisfy { assertThat(it).contains("{\$__tags}") }
    }
}
