package com.rustyrazorblade.easydblab.configuration.loki

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.nodeAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
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

/**
 * Tests Loki's configuration and Deployment as rendered by the real [TemplateService].
 *
 * Loki runs `target: all` with its compactor idle (decision D5): the only single-process target
 * that both writes and reads the S3 index, with compaction, retention and deletion turned off.
 */
class LokiManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: LokiManifestBuilder

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf()))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        builder = LokiManifestBuilder(getKoin().get())
    }

    private fun config(): String = builder.buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)

    private fun pod() =
        builder
            .buildDeployment()
            .spec.template.spec

    @Test
    fun `Loki runs as one process that reads and writes the S3 index, with native tenants`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "target")).isEqualTo("all")
        assertThat(scalarAt(yaml, "auth_enabled")).isEqualTo("true")
        assertThat(scalarAt(yaml, "querier", "multi_tenant_queries_enabled")).isEqualTo("true")
    }

    @Test
    fun `the compactor never runs, and nothing is retained by age or deleted`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "compactor", "compaction_interval")).isEqualTo("87600h")
        assertThat(scalarAt(yaml, "compactor", "retention_enabled")).isEqualTo("false")
        assertThat(scalarAt(yaml, "limits_config", "retention_period")).isIn("0", "0s")
        assertThat(scalarAt(yaml, "limits_config", "deletion_mode")).isEqualTo("disabled")
    }

    @Test
    fun `chunks and index land under the logs prefix, read from cluster-config`() {
        assertThat(scalarAt(config(), "storage_config", "object_prefix")).isEqualTo("\${LOGS_S3_PREFIX}")

        val env = pod().containers[0].env
        val prefix = env.single { it.name == "LOGS_S3_PREFIX" }.valueFrom.configMapKeyRef
        assertThat(prefix.name).isEqualTo("cluster-config")
        assertThat(prefix.key).isEqualTo("logs_s3_prefix")
        assertThat(pod().containers[0].args).contains("-config.expand-env=true")
    }

    /** The schema is a permanent contract of the shared store: every cluster must read it the same. */
    @Test
    fun `the schema is fixed tsdb v13 with daily index tables`() {
        val periods = nodeAt(config(), "schema_config", "configs") as YamlList
        val period = periods.items.single() as YamlMap

        assertThat(period.get<YamlScalar>("store")?.content).isEqualTo("tsdb")
        assertThat(period.get<YamlScalar>("schema")?.content).isEqualTo("v13")
        assertThat(period.get<YamlScalar>("from")?.content).matches("\\d{4}-\\d{2}-\\d{2}")
        val index = period.get<YamlMap>("index")
        assertThat(index?.get<YamlScalar>("prefix")?.content).isEqualTo("index_")
        assertThat(index?.get<YamlScalar>("period")?.content).isEqualTo("24h")
    }

    @Test
    fun `each index file names its tenant and cluster through the ingester id`() {
        assertThat(scalarAt(config(), "ingester", "lifecycler", "id")).isEqualTo("\${TENANT}.\${CLUSTER_NAME}")

        val env = pod().containers[0].env.associateBy { it.name }
        assertThat(
            env
                .getValue("TENANT")
                .valueFrom.configMapKeyRef.key,
        ).isEqualTo("tenant")
        assertThat(
            env
                .getValue("CLUSTER_NAME")
                .valueFrom.configMapKeyRef.key,
        ).isEqualTo("cluster_name")
    }

    @Test
    fun `the cluster, host, role and source are index labels`() {
        val yaml = config()
        val rules = nodeAt(yaml, "limits_config", "otlp_config", "resource_attributes", "attributes_config") as YamlList
        val indexLabels =
            rules.items
                .map { it as YamlMap }
                .filter { it.get<YamlScalar>("action")?.content == "index_label" }
                .flatMap { rule -> (rule.get<YamlList>("attributes")?.items.orEmpty()).map { (it as YamlScalar).content } }

        assertThat(indexLabels).containsExactlyInAnyOrder("cluster", "host.name", "node_role", "source")
    }

    @Test
    fun `old and backdated lines are accepted and limits do not drop a test cluster's streams`() {
        val yaml = config()

        // The annotation mirror skips entries outside this window using the same constants, so the
        // rendered limits must match them exactly.
        assertThat(scalarAt(yaml, "limits_config", "reject_old_samples_max_age"))
            .isEqualTo("${Constants.Loki.MAX_ENTRY_AGE_HOURS}h")
        assertThat(scalarAt(yaml, "limits_config", "creation_grace_period"))
            .isEqualTo("${Constants.Loki.MAX_ENTRY_AHEAD_HOURS}h")
        assertThat(scalarAt(yaml, "limits_config", "max_global_streams_per_user")).isEqualTo("0")
        assertThat(scalarAt(yaml, "limits_config", "ingestion_rate_mb")?.toDouble()).isGreaterThan(DEFAULT_INGESTION_RATE_MB)
        assertThat(scalarAt(yaml, "limits_config", "max_label_names_per_series")?.toInt()).isGreaterThan(DEFAULT_LABEL_NAMES)
    }

    @Test
    fun `the WAL, index and compactor directories live on the control node's disk`() {
        val yaml = config()
        val pod = pod()
        val volume = pod.volumes.single { it.name == "data" }
        val mount =
            pod.containers[0]
                .volumeMounts
                .single { it.name == "data" }
                .mountPath

        assertThat(volume.hostPath.path).isEqualTo("/mnt/db1/loki")
        assertThat(scalarAt(yaml, "ingester", "wal", "dir")).isEqualTo("$mount/wal")
        assertThat(scalarAt(yaml, "ingester", "wal", "flush_on_shutdown")).isEqualTo("true")
        assertThat(scalarAt(yaml, "storage_config", "tsdb_shipper", "active_index_directory")).isEqualTo("$mount/tsdb-index")
        assertThat(scalarAt(yaml, "storage_config", "tsdb_shipper", "cache_location")).isEqualTo("$mount/tsdb-cache")
        assertThat(scalarAt(yaml, "compactor", "working_directory")).isEqualTo("$mount/compactor")
    }

    @Test
    fun `the pod runs on the control node's network, with time to flush and no resource limits`() {
        val pod = pod()
        val container = pod.containers[0]

        assertThat(pod.hostNetwork).isTrue()
        assertThat(pod.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(pod.terminationGracePeriodSeconds).isEqualTo(600L)
        assertThat(container.resources?.limits.orEmpty()).isEmpty()
        assertThat(container.image).isEqualTo("grafana/loki:3.7.8")
        assertThat(container.ports.map { it.containerPort }).contains(Constants.K8s.LOKI_HTTP_PORT, Constants.K8s.LOKI_GRPC_PORT)
        assertThat(scalarAt(config(), "server", "http_listen_port")).isEqualTo("3100")
        assertThat(scalarAt(config(), "server", "grpc_listen_port")).isEqualTo("9098")
    }

    @Test
    fun `the config parses as YAML`() {
        assertThat(Yaml.default.parseToYamlNode(config())).isInstanceOf(YamlMap::class.java)
    }

    private companion object {
        const val DEFAULT_INGESTION_RATE_MB = 4.0
        const val DEFAULT_LABEL_NAMES = 15
    }
}
