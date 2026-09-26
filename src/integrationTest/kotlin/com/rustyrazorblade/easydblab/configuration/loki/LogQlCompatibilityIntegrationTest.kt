package com.rustyrazorblade.easydblab.configuration.loki

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.DashboardQueries
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceConfig
import com.rustyrazorblade.easydblab.services.LogQl
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
 * Runs every LogQL query the tool sends — each Loki panel, variable, annotation query and Explore
 * link in the dashboard tree, every query [LogQl] builds, and the trace-to-logs link — against the
 * pinned Loki, and fails on any query Loki refuses. Loki answers a query it cannot parse with 400.
 */
class LogQlCompatibilityIntegrationTest : BaseKoinTest() {
    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "logql-it-${UUID.randomUUID().toString().take(8)}"
    private val volume = "logql-${UUID.randomUUID().toString().take(8)}"
    private var loki: GenericContainer<*>? = null

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "logql", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun stop() {
        loki?.stop()
        runCatching { docker.removeVolumeCmd(volume).exec() }
    }

    /** The trace-to-logs query as Grafana runs it: its provisioning env expansion turns `$$` into `$`. */
    private fun traceToLogs(): String {
        val query =
            GrafanaDatasourceConfig
                .create("acme")
                .datasources
                .firstNotNullOf { it.jsonData?.tracesToLogsV2?.query }
        return DashboardQueries.substitute(query.replace("$$", "$"))
    }

    private fun toolQueries(): List<DashboardQueries.Query> =
        listOf(
            "logs query" to LogQl.logsQuery("lab-x"),
            "logs query --source --host --unit --grep" to
                LogQl.logsQuery(
                    "lab-x",
                    "cassandra",
                    "db0",
                    "cassandra.service",
                    "timed \"out\"",
                ),
            "spark logs" to LogQl.sparkStep("lab-x", "s-ABC"),
            "EMR step lookup" to LogQl.sparkJobs("lab-x"),
            "trace-to-logs link" to traceToLogs(),
        ).map { (source, text) -> DashboardQueries.Query(source, DashboardQueries.Language.LOGQL, text) }

    @Test
    fun `every dashboard and tool LogQL query parses on the pinned Loki`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val rendered = LokiManifestBuilder(getKoin().get()).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        val container =
            ObservabilityBackends.startLoki(
                ObservabilityBackends.lokiConfig(rendered),
                volume,
                bucket,
                "observability/logs",
                "acme",
                "logql-x",
            )
        loki = container
        val base = ObservabilityBackends.baseUrl(container, Constants.K8s.LOKI_HTTP_PORT)

        val queries = DashboardQueries.all(DashboardQueries.Language.LOGQL) + toolQueries()
        assertThat(queries.map { it.source }).anyMatch { "cassandra-logs-analysis" in it }.anyMatch { "annotation" in it }

        val refused =
            queries.mapNotNull { query ->
                val url =
                    query.labelValues?.let { (selector, label) ->
                        "$base/loki/api/v1/label/$label/values?query=${URLEncoder.encode(selector, Charsets.UTF_8)}&since=1h"
                    } ?: "$base/loki/api/v1/query_range?query=${URLEncoder.encode(query.text, Charsets.UTF_8)}&since=1h&limit=10"
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
}
