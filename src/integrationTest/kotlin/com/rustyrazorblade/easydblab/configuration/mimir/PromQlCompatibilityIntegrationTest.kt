package com.rustyrazorblade.easydblab.configuration.mimir

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DashboardQueries
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceConfig
import com.rustyrazorblade.easydblab.mcp.MetricsQueries
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import java.net.URLEncoder
import java.util.UUID

/**
 * Runs every PromQL query the tool sends — each metrics panel, variable, annotation query and link
 * in the dashboard tree, the trace-to-metrics queries, and the live metrics stream's queries — with
 * variables substituted, against the pinned Mimir, and fails on any query Mimir refuses. The
 * dashboards were written for VictoriaMetrics; Mimir answers a query it cannot parse with 400.
 */
class PromQlCompatibilityIntegrationTest : BaseKoinTest() {
    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "promql-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "promql-${UUID.randomUUID().toString().take(8)}"
    private var mimir: GenericContainer<*>? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "promql", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun stop() {
        mimir?.stop()
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    /** The trace-to-metrics queries as Grafana runs them, with the span's tags filled in. */
    private fun traceToMetrics(): List<String> =
        GrafanaDatasourceConfig
            .create("acme")
            .datasources
            .mapNotNull { it.jsonData?.tracesToMetrics }
            .flatMap { it.queries }
            .map { it.query.replace("$$", "$").replace("\$__tags", "service_name=\"x\"") }

    private fun toolQueries(): List<DashboardQueries.Query> {
        val stream = MetricsQueries.forCluster("lab-x")
        return (stream.system() + stream.cassandra() + traceToMetrics()).map {
            DashboardQueries.Query("tool", DashboardQueries.Language.PROMQL, it)
        }
    }

    @Test
    fun `every dashboard and tool PromQL query parses on the pinned Mimir`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val rendered = MimirManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        val container =
            ObservabilityBackends.startMimir(
                ObservabilityBackends.mimirConfig(rendered),
                volume,
                bucket,
                Constants.Observability.METRICS_ROOT,
            )
        mimir = container
        val base = "${ObservabilityBackends.baseUrl(container, Constants.K8s.MIMIR_HTTP_PORT)}/prometheus/api/v1"

        val queries = DashboardQueries.all(DashboardQueries.Language.PROMQL) + toolQueries()
        assertThat(queries.size).isGreaterThan(MIN_EXPECTED_QUERIES)

        val refused =
            queries.mapNotNull { query ->
                val url =
                    query.labelValues?.let { (selector, label) ->
                        val match = if (selector.isBlank()) "" else "?match[]=${URLEncoder.encode(selector, Charsets.UTF_8)}"
                        "$base/label/$label/values$match"
                    } ?: "$base/query?query=${URLEncoder.encode(query.text, Charsets.UTF_8)}"
                val response = ObservabilityBackends.get(url, "acme")
                if (response.statusCode() ==
                    200
                ) {
                    null
                } else {
                    "${query.source}: ${query.text}\n  -> ${response.statusCode()} ${response.body().trim()}"
                }
            }
        assertThat(refused).isEmpty()
    }

    private companion object {
        /** The tree holds well over a thousand metric expressions; far fewer means the extraction broke. */
        const val MIN_EXPECTED_QUERIES = 1000
    }
}
