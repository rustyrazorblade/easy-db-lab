package com.rustyrazorblade.easydblab.configuration.mimir

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.listAt
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
 * Tests Mimir's configuration and Deployment as rendered by the real [TemplateService].
 *
 * Mimir writes every block to S3 and answers queries only from its own ingester (decision D1): no
 * compactor and no store-gateway run, so nothing in the cluster can compact or delete metrics, and
 * the querier never reads the S3 block store.
 */
class MimirManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: MimirManifestBuilder

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
        builder = MimirManifestBuilder(getKoin().get())
    }

    private fun config(): String = builder.buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)

    private fun pod() =
        builder
            .buildDeployment()
            .spec.template.spec

    @Test
    fun `no compactor and no store-gateway run, so nothing compacts or deletes blocks`() {
        val modules = scalarAt(config(), "target").orEmpty().split(",").map { it.trim() }

        assertThat(modules).containsExactlyInAnyOrder(
            "distributor",
            "ingester",
            "querier",
            "query-frontend",
            "query-scheduler",
        )
        assertThat(modules).doesNotContain("compactor", "store-gateway", "all")
    }

    @Test
    fun `queries are answered from the ingester only, over the cluster's whole life`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "limits", "query_ingesters_within")).isEqualTo("0")
        assertThat(scalarAt(yaml, "querier", "query_store_after")).isEqualTo("87600h")
        // Local blocks are kept longer than the querier would ever send a query to the store.
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "retention_period")).isEqualTo("87601h")
    }

    @Test
    fun `blocks are two hours, shipped within a minute, and flushed on shutdown`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "ship_interval")).isEqualTo("1m")
        assertThat(listAt(yaml, "blocks_storage", "tsdb", "block_ranges_period")).containsExactly("2h")
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "flush_blocks_on_shutdown")).isEqualTo("true")
    }

    @Test
    fun `blocks land under the metrics root of the account bucket, read from cluster-config`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "blocks_storage", "backend")).isEqualTo("s3")
        assertThat(scalarAt(yaml, "blocks_storage", "storage_prefix")).isEqualTo("\${METRICS_S3_PREFIX}")
        assertThat(scalarAt(yaml, "blocks_storage", "s3", "bucket_name")).isEqualTo("\${S3_BUCKET}")

        val env = pod().containers[0].env
        val prefix = env.single { it.name == "METRICS_S3_PREFIX" }.valueFrom.configMapKeyRef
        assertThat(prefix.name).isEqualTo("cluster-config")
        assertThat(prefix.key).isEqualTo("metrics_s3_prefix")
        assertThat(pod().containers[0].args).contains("-config.expand-env=true")
    }

    @Test
    fun `tenants are native and can be queried together`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "multitenancy_enabled")).isEqualTo("true")
        assertThat(scalarAt(yaml, "tenant_federation", "enabled")).isEqualTo("true")
    }

    @Test
    fun `ingestion limits do not silently drop a test cluster's series`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "limits", "max_global_series_per_user")).isEqualTo("0")
        assertThat(scalarAt(yaml, "limits", "out_of_order_time_window")).isEqualTo("10m")
        assertThat(scalarAt(yaml, "limits", "ingestion_rate")?.toDouble()).isGreaterThan(DEFAULT_INGESTION_RATE)
        assertThat(scalarAt(yaml, "limits", "max_label_names_per_series")?.toInt()).isGreaterThan(DEFAULT_LABEL_NAMES)
    }

    @Test
    fun `rings live in memory with one replica`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "ingester", "ring", "kvstore", "store")).isEqualTo("inmemory")
        assertThat(scalarAt(yaml, "ingester", "ring", "replication_factor")).isEqualTo("1")
        assertThat(scalarAt(yaml, "distributor", "ring", "kvstore", "store")).isEqualTo("inmemory")
    }

    @Test
    fun `the TSDB and WAL live on the control node's disk`() {
        val pod = pod()
        val volume = pod.volumes.single { it.name == "data" }
        val mount = pod.containers[0].volumeMounts.single { it.name == "data" }

        assertThat(volume.hostPath.path).isEqualTo(MimirManifestBuilder.DATA_HOST_PATH)
        assertThat(MimirManifestBuilder.DATA_HOST_PATH).isEqualTo("/mnt/db1/mimir")
        assertThat(scalarAt(config(), "blocks_storage", "tsdb", "dir")).isEqualTo(mount.mountPath + "/tsdb")
    }

    @Test
    fun `the pod runs on the control node's network, with time to flush and no resource limits`() {
        val pod = pod()
        val container = pod.containers[0]

        assertThat(pod.hostNetwork).isTrue()
        assertThat(pod.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(pod.terminationGracePeriodSeconds).isEqualTo(600L)
        assertThat(container.resources?.limits.orEmpty()).isEmpty()
        assertThat(container.image).isEqualTo("grafana/mimir:3.2.1")
        assertThat(container.ports.map { it.containerPort })
            .contains(Constants.K8s.MIMIR_HTTP_PORT, Constants.K8s.MIMIR_GRPC_PORT)
    }

    @Test
    fun `the server listens on the Mimir ports`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "server", "http_listen_port")).isEqualTo("9009")
        assertThat(scalarAt(yaml, "server", "grpc_listen_port")).isEqualTo("9097")
    }

    private companion object {
        const val DEFAULT_INGESTION_RATE = 10_000.0
        const val DEFAULT_LABEL_NAMES = 30
    }
}
