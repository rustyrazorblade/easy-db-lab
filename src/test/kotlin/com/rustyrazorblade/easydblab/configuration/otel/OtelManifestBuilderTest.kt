package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OtelManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: OtelManifestBuilder
    private lateinit var mockClusterStateManager: ClusterStateManager

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        mockClusterStateManager = it
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        mockClusterStateManager = getKoin().get()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf()),
        )
        val templateService = getKoin().get<TemplateService>()
        builder = OtelManifestBuilder(templateService)
    }

    @Test
    fun `buildConfigMap with empty list contains all static scrape jobs`() {
        val configMap = builder.buildConfigMap(emptyList())
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'cassandra-maac'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: 'hubble'")
    }

    @Test
    fun `buildConfigMap with empty list does not leave placeholder in output`() {
        val configMap = builder.buildConfigMap(emptyList())
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).doesNotContain("__WORKLOAD_SCRAPE_JOBS__")
    }

    @Test
    fun `buildConfigMap injects dynamic scrape job for each workload`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(jobName = "presto", port = 8081, path = "/metrics"),
                WorkloadScrapeConfig(jobName = "opensearch", port = 9600, path = "/_prometheus/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).contains("job_name: \"presto\"")
        assertThat(yaml).contains("localhost:8081")
        assertThat(yaml).contains("metrics_path: \"/metrics\"")
        assertThat(yaml).contains("job_name: \"opensearch\"")
        assertThat(yaml).contains("localhost:9600")
        assertThat(yaml).contains("metrics_path: \"/_prometheus/metrics\"")
    }

    @Test
    fun `buildConfigMap with dynamic jobs still contains all static scrape jobs`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(jobName = "clickhouse", port = 9363, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'cassandra-maac'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: 'hubble'")
        assertThat(yaml).contains("job_name: \"clickhouse\"")
    }

    @Test
    fun `buildConfigMap dynamic jobs use OTel runtime env expansion not placeholder`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(jobName = "mydb", port = 9999, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).contains("\${env:HOSTNAME}:9999")
        assertThat(yaml).contains("\${env:CLUSTER_NAME}")
    }

    @Test
    fun `buildConfigMap has correct metadata`() {
        val configMap = builder.buildConfigMap(emptyList())

        assertThat(configMap.metadata.name).isEqualTo("otel-collector-config")
        assertThat(configMap.metadata.namespace).isEqualTo("default")
        assertThat(configMap.data).containsKey("otel-collector-config.yaml")
    }

    @Test
    fun `buildConfigMap with username generates basic_auth block in scrape job`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(jobName = "trino", port = 8080, path = "/metrics", username = "trino"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).contains("basic_auth:")
        assertThat(yaml).contains("username: \"trino\"")
    }

    @Test
    fun `buildConfigMap without username omits basic_auth block`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(jobName = "clickhouse", port = 9363, path = "/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = configMap.data["otel-collector-config.yaml"]!!

        assertThat(yaml).doesNotContain("basic_auth:")
    }
}
