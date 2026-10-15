package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Language
import com.rustyrazorblade.easydblab.configuration.grafana.ClusterFilterGuards.Query
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A cluster from before the `experiment` label is an experiment of one, so every dashboard query
 * that reads the experiment labels also reads those clusters, and every query that writes a tenant
 * for them falls back to the home tenant.
 *
 * The rules, checked on every core and kit dashboard, panel targets and variable queries:
 *
 * - (a) a PromQL query with an `experiment` or `edl_tenant` matcher other than `experiment=""`
 *   also holds an `up{...experiment=""...}` selector joined by `or`;
 * - (b) a PromQL query that outputs `edl_tenant` holds `"${'$'}{home_tenant}"`;
 * - (c) a dashboard that references `home_tenant` declares it as a hidden custom variable;
 * - (d) no LogQL or TraceQL query names `experiment` or `edl_tenant`: logs and traces filter on the
 *   cluster only.
 */
class ExperimentFallbackTest {
    private fun promql(text: String) = Query("sample", Language.PROMQL, text)

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text.trimIndent()).jsonObject

    @Test
    fun `an experiment matcher without the unlabelled branch is reported`() {
        val labelledOnly = """count by (cluster) (up{edl_tenant=~"${'$'}exp_tenant", experiment=~"${'$'}exp_name"})"""

        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(labelledOnly)))
            .containsExactly("""edl_tenant=~"${'$'}exp_tenant"""", """experiment=~"${'$'}exp_name"""")
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql("""count by (cluster) (up{experiment!=""})"""))).hasSize(1)
    }

    @Test
    fun `the unlabelled branch must be an up selector joined by or`() {
        val joinedByAnd = """count by (cluster) (up{experiment=~"${'$'}exp_name"}) and count by (cluster) (up{experiment=""})"""
        val notUp = """count by (cluster) (up{experiment=~"${'$'}exp_name"}) or count by (cluster) (x{experiment=""})"""

        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(joinedByAnd))).hasSize(1)
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(notUp))).hasSize(1)
    }

    @Test
    fun `the experiment cluster list with its pre-label branch passes`() {
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(EXPERIMENT_CLUSTERS))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(A_REPORT))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql(PICKER))).isEmpty()
    }

    @Test
    fun `a query that names no experiment label, or matches only the unlabelled series, needs no branch`() {
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql("""up{cluster=~"${'$'}cluster"}"""))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(promql("""count(up{experiment=""})"""))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingUnlabelledBranch(Query("s", Language.LOGQL, """{experiment=~"x"}"""))).isEmpty()
    }

    @Test
    fun `a query that outputs edl_tenant without the home tenant fallback is reported`() {
        val noFallback =
            """count by (edl_tenant, experiment) (up{experiment!=""}) or """ +
                """label_replace(count by (cluster, __tenant_id__) (up{experiment=""}), "edl_tenant", "${'$'}1", "__tenant_id__", "(.+)")"""

        assertThat(ExperimentFallbackGuards.missingHomeTenant(promql(noFallback))).isNotEmpty()
        assertThat(ExperimentFallbackGuards.missingHomeTenant(promql(PICKER))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingHomeTenant(promql(A_REPORT))).isEmpty()
    }

    @Test
    fun `a query that only filters on edl_tenant outputs no tenant`() {
        assertThat(ExperimentFallbackGuards.missingHomeTenant(promql(EXPERIMENT_CLUSTERS))).isEmpty()
        assertThat(ExperimentFallbackGuards.missingHomeTenant(promql("""up{cluster=~"${'$'}cluster"}"""))).isEmpty()
    }

    @Test
    fun `a dashboard that references home_tenant must declare it as a hidden custom variable`() {
        val uses = """{"panels": [{"targets": [{"expr": "label_replace(up, \"t\", \"${'$'}{home_tenant}\", \"t\", \"\")"}]}]"""

        assertThat(ExperimentFallbackGuards.undeclaredHomeTenant(parse("""$uses, "templating": {"list": []}}""")))
            .containsExactly("references home_tenant but does not declare it")
        assertThat(
            ExperimentFallbackGuards.undeclaredHomeTenant(
                parse("""$uses, "templating": {"list": [{"name": "home_tenant", "type": "custom", "hide": 0}]}}"""),
            ),
        ).containsExactly("home_tenant is not a hidden custom variable (type custom, hide 2)")
        assertThat(
            ExperimentFallbackGuards.undeclaredHomeTenant(
                parse("""$uses, "templating": {"list": [{"name": "home_tenant", "type": "custom", "hide": 2}]}}"""),
            ),
        ).isEmpty()
        assertThat(ExperimentFallbackGuards.undeclaredHomeTenant(parse("""{"panels": []}"""))).isEmpty()
    }

    @Test
    fun `a log or trace query that names the experiment labels is reported`() {
        assertThat(ExperimentFallbackGuards.experimentInLogsOrTraces(Query("s", Language.LOGQL, """{experiment=~"${'$'}exp_name"}""")))
            .containsExactly("experiment")
        assertThat(
            ExperimentFallbackGuards.experimentInLogsOrTraces(
                Query("s", Language.TRACEQL, """{ resource.edl_tenant = "acme" && resource.cluster =~ "${'$'}cluster" }"""),
            ),
        ).containsExactly("edl_tenant")
        assertThat(
            ExperimentFallbackGuards.experimentInLogsOrTraces(
                Query("s", Language.LOGQL, """{cluster=~"${'$'}cluster"} |= "experimental""""),
            ),
        ).isEmpty()
        assertThat(ExperimentFallbackGuards.experimentInLogsOrTraces(promql(PICKER))).isEmpty()
    }

    @Test
    fun `every experiment matcher on a dashboard has the unlabelled branch`() {
        assertThat(scan { query -> ExperimentFallbackGuards.missingUnlabelledBranch(query) }).isEmpty()
    }

    @Test
    fun `every query that outputs edl_tenant falls back to the home tenant`() {
        assertThat(scan { query -> ExperimentFallbackGuards.missingHomeTenant(query) }).isEmpty()
    }

    @Test
    fun `every dashboard that references home_tenant declares it hidden`() {
        val found =
            DashboardFiles.all().flatMap { file ->
                ExperimentFallbackGuards.undeclaredHomeTenant(parse(file.readText())).map { "${file.path}: $it" }
            }

        assertThat(found).isEmpty()
    }

    @Test
    fun `no dashboard log or trace query names the experiment labels`() {
        assertThat(scan { query -> ExperimentFallbackGuards.experimentInLogsOrTraces(query) }).isEmpty()
    }

    /** Every problem [check] finds in any query of any core or kit dashboard, naming the file and the query. */
    private fun scan(check: (Query) -> List<String>): List<String> =
        DashboardFiles.all().flatMap { file ->
            ClusterFilterGuards.queries(parse(file.readText())).flatMap { query ->
                check(query).map { "${file.path} ${query.where}: $it in ${query.text}" }
            }
        }

    private companion object {
        /** Design 8.2 site 3: the experiment's clusters, with the pre-label branch. */
        const val EXPERIMENT_CLUSTERS =
            """query_result(max by (cluster) (max_over_time(timestamp(count by (cluster) """ +
                """(up{edl_tenant=~"${'$'}exp_tenant", experiment=~"${'$'}exp_name"}) or count by (cluster) """ +
                """(up{experiment="", cluster=~"${'$'}exp_name"}))[${'$'}lookback:${'$'}{resolution}s])))"""

        /** Design 8.2 site 6: the report folder of the A side, in the lookup form. */
        const val A_REPORT =
            """query_result(max by (exp_key) (label_join(last_over_time(up{cluster="${'$'}cluster_a", experiment!=""}""" +
                """[${'$'}lookback]), "exp_key", "/", "edl_tenant", "experiment")) or on() max by (exp_key) """ +
                """(label_join(label_replace(label_replace(last_over_time(up{cluster="${'$'}cluster_a", experiment=""}""" +
                """[${'$'}lookback]), "t", "${'$'}1", "__tenant_id__", "(.+)"), "t", "${'$'}{home_tenant}", "t", ""), """ +
                """"exp_key", "/", "t", "cluster")))"""

        /** Design 8.1: the experiment picker, newest first, with the synthetic All last. */
        const val PICKER =
            """query_result(sort_desc(max by (exp_key, exp_text) (label_join(label_join(max_over_time(timestamp(""" +
                """count by (edl_tenant, experiment) (up{experiment!=""}))[${'$'}lookback:${'$'}{resolution}s]), "exp_key", "/", """ +
                """"edl_tenant", "experiment"), "exp_text", "/", "edl_tenant", "experiment") or label_join(label_join(""" +
                """label_replace(label_replace(max_over_time(timestamp(count by (cluster, __tenant_id__) (up{experiment=""}))""" +
                """[${'$'}lookback:${'$'}{resolution}s]), "t", "${'$'}1", "__tenant_id__", "(.+)"), """ +
                """"t", "${'$'}{home_tenant}", "t", ""), """ +
                """"exp_key", "/", "t", "cluster"), "exp_text", "/", "t", "cluster") or label_replace(label_replace(vector(0), """ +
                """"exp_key", ".*/.*", "", ""), "exp_text", "All", "", ""))))"""
    }
}
