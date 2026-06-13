package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
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

    private fun yamlFrom(configMap: ConfigMap): String =
        checkNotNull(configMap.data["otel-collector-config.yaml"]) {
            "ConfigMap missing expected data key 'otel-collector-config.yaml'"
        }

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
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'cassandra-maac'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: 'hubble'")
    }

    @Test
    fun `buildConfigMap with empty list does not leave placeholder in output`() {
        val configMap = builder.buildConfigMap(emptyList())
        val yaml = yamlFrom(configMap)

        assertThat(yaml).doesNotContain("__WORKLOAD_SCRAPE_JOBS__")
    }

    @Test
    fun `buildConfigMap injects dynamic scrape job for each workload`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "presto", jobName = "presto", port = 8081, path = "/metrics"),
                WorkloadScrapeConfig(kitName = "opensearch", jobName = "opensearch", port = 9600, path = "/_prometheus/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: \"presto\"")
        assertThat(yaml).contains("localhost:8081")
        assertThat(yaml).contains("metrics_path: \"/metrics\"")
        assertThat(yaml).contains("job_name: \"opensearch\"")
        assertThat(yaml).contains("localhost:9600")
        assertThat(yaml).contains("metrics_path: \"/_prometheus/metrics\"")
    }

    @Test
    fun `buildConfigMap uses kitName as OTel job_name and relabels job to jobName`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "postgres-duckdb", jobName = "postgres", port = 30987, path = "/metrics"),
                WorkloadScrapeConfig(kitName = "postgres-postgis", jobName = "postgres", port = 30988, path = "/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        // OTel job_name uses kitName to ensure uniqueness across instances
        assertThat(yaml).contains("job_name: \"postgres-duckdb\"")
        assertThat(yaml).contains("job_name: \"postgres-postgis\"")
        // Relabel sets the `job` label in metrics back to the logical jobName
        assertThat(yaml).contains("target_label: \"job\"").contains("replacement: \"postgres\"")
    }

    @Test
    fun `buildConfigMap with dynamic jobs still contains all static scrape jobs`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(kitName = "clickhouse", jobName = "clickhouse", port = 9363, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("job_name: 'beyla'")
        assertThat(yaml).contains("job_name: 'ebpf-exporter'")
        assertThat(yaml).contains("job_name: 'cassandra-maac'")
        assertThat(yaml).contains("job_name: 'yace'")
        assertThat(yaml).contains("job_name: 'hubble'")
        assertThat(yaml).contains("job_name: \"clickhouse\"")
    }

    @Test
    fun `buildConfigMap dynamic jobs use OTel runtime env expansion not placeholder`() {
        val scrapeConfigs = listOf(WorkloadScrapeConfig(kitName = "mydb", jobName = "mydb", port = 9999, path = "/metrics"))

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

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
                WorkloadScrapeConfig(kitName = "trino", jobName = "trino", port = 8080, path = "/metrics", username = "trino"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).contains("basic_auth:")
        assertThat(yaml).contains("username: \"trino\"")
    }

    @Test
    fun `buildConfigMap without username omits basic_auth block`() {
        val scrapeConfigs =
            listOf(
                WorkloadScrapeConfig(kitName = "clickhouse", jobName = "clickhouse", port = 9363, path = "/metrics"),
            )

        val configMap = builder.buildConfigMap(scrapeConfigs)
        val yaml = yamlFrom(configMap)

        assertThat(yaml).doesNotContain("basic_auth:")
    }
}
