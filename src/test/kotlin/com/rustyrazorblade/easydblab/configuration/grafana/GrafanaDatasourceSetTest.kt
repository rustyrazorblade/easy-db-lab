package com.rustyrazorblade.easydblab.configuration.grafana

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.YamlTestSupport.nodeAt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The datasource set Grafana is provisioned with, read back from the rendered YAML the way Grafana
 * reads it: the stable, per-tenant and all-tenants views, their tenant headers, their cross links,
 * and the UID length rule.
 */
class GrafanaDatasourceSetTest {
    private val tenants = TenantSet.of("default", listOf("acme"))

    private fun yaml(
        set: TenantSet = tenants,
        urls: BackendUrls = BackendUrls.CONTROL_NODE,
    ) = GrafanaDatasourceSet.build(set, urls).toYaml()

    /** Every provisioned datasource, by UID, as Grafana reads it from the rendered YAML. */
    private fun provisioned(yaml: String = yaml()): Map<String, YamlMap> =
        (nodeAt(yaml, "datasources") as YamlList)
            .items
            .map { it as YamlMap }
            .associateBy { it.scalar("uid").orEmpty() }

    private fun YamlMap.scalar(vararg path: String): String? {
        var node: YamlMap? = this
        for (key in path.dropLast(1)) node = node?.get<YamlMap>(key)
        return node?.get<YamlScalar>(path.last())?.content
    }

    /** The tenant a datasource sends in `X-Scope-OrgID`, after Grafana's provisioning env expansion. */
    private fun orgId(ds: YamlMap): String? {
        assertThat(ds.scalar("jsonData", "httpHeaderName1")).isEqualTo("X-Scope-OrgID")
        return ds.scalar("secureJsonData", "httpHeaderValue1")?.let(::grafanaEnvInterpolate)
    }

    @Test
    fun `each tenant gets a datasource per signal, and one all-tenants datasource per signal sends every tenant`() {
        val ds = provisioned()

        assertThat(ds.keys).containsExactlyInAnyOrder(
            "mimir",
            "loki",
            "tempo",
            "mimir-acme",
            "loki-acme",
            "tempo-acme",
            "mimir-default",
            "loki-default",
            "tempo-default",
            "mimir--all",
            "loki--all",
            "tempo--all",
            "pyroscope",
        )
        for (signal in listOf("mimir", "loki", "tempo")) {
            assertThat(orgId(ds.getValue("$signal-acme"))).isEqualTo("acme")
            assertThat(orgId(ds.getValue("$signal-default"))).isEqualTo("default")
            assertThat(orgId(ds.getValue("$signal--all"))).isEqualTo("acme|default")
        }
        assertThat(ds.getValue("mimir-acme").scalar("name")).isEqualTo("Mimir (acme)")
        assertThat(ds.getValue("loki--all").scalar("name")).isEqualTo("Loki (all tenants)")
        assertThat(ds.values.map { it.scalar("name") }).doesNotHaveDuplicates()
    }

    @Test
    fun `the stable datasources keep their names and types, send the home tenant, and Mimir stays the default`() {
        val ds = provisioned(yaml(TenantSet.of("acme", listOf("default"))))

        for (
        (uid, name, type) in
        listOf(
            Triple("mimir", "Mimir", "prometheus"),
            Triple("loki", "Loki", "loki"),
            Triple("tempo", "Tempo", "tempo"),
            Triple("pyroscope", "Pyroscope", "grafana-pyroscope-datasource"),
        )
        ) {
            assertThat(ds.getValue(uid).scalar("name")).isEqualTo(name)
            assertThat(ds.getValue(uid).scalar("type")).isEqualTo(type)
            assertThat(orgId(ds.getValue(uid))).describedAs(uid).isEqualTo("acme")
        }
        assertThat(ds.filterValues { it.scalar("isDefault") == "true" }.keys).containsExactly("mimir")
    }

    @Test
    fun `there is exactly one profiles datasource`() {
        val types = provisioned().values.map { it.scalar("type") }

        assertThat(types.count { it == "grafana-pyroscope-datasource" }).isEqualTo(1)
    }

    @Test
    fun `the same tenants with other backend URLs differ only in their URLs`() {
        val other = BackendUrls("http://m:1/prometheus", "http://l:2", "http://t:3", "http://p:4")
        val control = GrafanaDatasourceSet.build(tenants, BackendUrls.CONTROL_NODE).datasources
        val compose = GrafanaDatasourceSet.build(tenants, other).datasources

        assertThat(compose.map { it.copy(url = null) }).isEqualTo(control.map { it.copy(url = null) })
        assertThat(compose.map { it.url }.toSet()).containsExactlyInAnyOrder(other.metrics, other.logs, other.traces, other.profiles)
        assertThat(control.single { it.uid == "mimir" }.url).isEqualTo("http://localhost:9009/prometheus")
        assertThat(control.single { it.uid == "loki" }.url).isEqualTo("http://localhost:3100")
    }

    @Test
    fun `a tenant whose UID would pass 40 characters is shortened to a prefix and a hash`() {
        val long = "a".repeat(30) + "-tenant-name-" + "b".repeat(15)
        val set = TenantSet.of("default", listOf(long))
        val uids = provisioned(yaml(set)).keys

        assertThat(uids).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(40) }
        val shortened = GrafanaDatasourceSet.uid("tempo", long)
        assertThat(shortened).matches("tempo-a+-[0-9a-f]{8}").hasSize(40)
        assertThat(uids).contains(shortened)
        // Two long tenants with the same prefix still get distinct UIDs.
        assertThat(GrafanaDatasourceSet.uid("tempo", long + "x")).isNotEqualTo(shortened)
        assertThat(GrafanaDatasourceSet.uid("tempo", "acme")).isEqualTo("tempo-acme")
    }

    /** A view's links to another signal point at that signal's datasource for the same tenant view. */
    @Test
    fun `every cross link stays within its tenant view`() {
        val ds = provisioned()

        // The stable view, the two per-tenant views, and the all-tenants view.
        for (suffix in listOf("", "-acme", "-default", "--all")) {
            val loki = ds.getValue("loki$suffix")
            val field =
                (
                    loki
                        .get<YamlMap>("jsonData")
                        ?.get<YamlList>("derivedFields")
                        ?.items
                        .orEmpty()
                ).single() as YamlMap
            assertThat(field.scalar("datasourceUid")).isEqualTo("tempo$suffix")

            val tempo = ds.getValue("tempo$suffix")
            assertThat(tempo.scalar("jsonData", "tracesToLogsV2", "datasourceUid")).isEqualTo("loki$suffix")
            assertThat(tempo.scalar("jsonData", "tracesToMetrics", "datasourceUid")).isEqualTo("mimir$suffix")
            assertThat(tempo.scalar("jsonData", "serviceMap", "datasourceUid")).isEqualTo("mimir$suffix")
        }
    }

    /** A log line's trace_id (structured metadata) links to the trace in Tempo. */
    @Test
    fun `a log line's trace id links to Tempo`() {
        val field =
            (
                provisioned()
                    .getValue("loki")
                    .get<YamlMap>("jsonData")
                    ?.get<YamlList>("derivedFields")
                    ?.items
                    .orEmpty()
            ).single() as YamlMap

        assertThat(field.scalar("matcherType")).isEqualTo("label")
        assertThat(field.scalar("matcherRegex")).isEqualTo("trace_id")
        assertThat(field.scalar("url")?.let(::grafanaEnvInterpolate)).isEqualTo("\${__value.raw}")
        assertThat(provisioned().getValue("loki").get<YamlMap>("jsonData")?.get<YamlList>("logLevelRules")).isNull()
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
        val tempo = provisioned().getValue("tempo")

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
