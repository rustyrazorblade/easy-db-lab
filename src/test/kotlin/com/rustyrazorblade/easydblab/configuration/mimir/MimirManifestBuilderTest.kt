package com.rustyrazorblade.easydblab.configuration.mimir

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.listAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardFiles
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Mimir writes every block to S3 and reads the whole shared store through its store-gateway. No
 * compactor runs, so nothing in the cluster can compact or delete metrics.
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
    fun `the store-gateway runs and no compactor does, so nothing in the cluster compacts or deletes blocks`() {
        val modules = scalarAt(config(), "target").orEmpty().split(",").map { it.trim() }

        assertThat(modules).containsExactlyInAnyOrder(
            "distributor",
            "ingester",
            "querier",
            "query-frontend",
            "query-scheduler",
            "store-gateway",
        )
        assertThat(modules).doesNotContain("compactor", "all")
    }

    @Test
    fun `queries read the ingester and the whole store, and local blocks are kept for 2 hours`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "limits", "query_ingesters_within")).isEqualTo("0")
        assertThat(scalarAt(yaml, "querier", "query_store_after")).isEqualTo("0")
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "retention_period")).isEqualTo("2h")
        assertThat(scalarAt(yaml, "blocks_storage", "bucket_store", "sync_interval")).isEqualTo("1m")
        assertThat(scalarAt(yaml, "blocks_storage", "bucket_store", "ignore_blocks_within")).isEqualTo("0")
        // A stopped compactor leaves the bucket index stale; that must never fail a query.
        assertThat(scalarAt(yaml, "blocks_storage", "bucket_store", "bucket_index", "max_stale_period")).isEqualTo("87600h")
    }

    @Test
    fun `blocks are one minute, cut and shipped every 15 seconds, and flushed on shutdown`() {
        val yaml = config()

        assertThat(listAt(yaml, "blocks_storage", "tsdb", "block_ranges_period")).containsExactly("1m")
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "head_compaction_interval")).isEqualTo("15s")
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "ship_interval")).isEqualTo("15s")
        // An idle head is compacted, so a stopped cluster's last partial block still ships.
        assertThat(scalarAt(yaml, "blocks_storage", "tsdb", "head_compaction_idle_timeout")).isEqualTo("2m")
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

    /**
     * The queries one load of [dashboard] sends: the targets of every top-level panel and every query
     * variable. A collapsed row keeps its panels inside it and loads none of them until it is opened.
     */
    private fun queriesPerLoad(dashboard: JsonObject): Int {
        val targets =
            dashboard["panels"]
                ?.jsonArray
                .orEmpty()
                .sumOf { (it.jsonObject["targets"] as? JsonArray)?.size ?: 0 }
        val variables =
            dashboard["templating"]
                ?.jsonObject
                ?.get("list")
                ?.jsonArray
                .orEmpty()
                .count { it.jsonObject["type"]?.jsonPrimitive?.content == "query" }
        return targets + variables
    }

    @Test
    fun `the query queue holds two full loads of the heaviest dashboard`() {
        // The query-frontend splits each query and schedules at most max_query_parallelism parts of it
        // at once; the scheduler rejects a tenant's request with 429 past its outstanding limit.
        val heaviest = DashboardFiles.all().maxOf { queriesPerLoad(Json.parseToJsonElement(it.readText()).jsonObject) }
        val limit = scalarAt(config(), "query_scheduler", "max_outstanding_requests_per_tenant")?.toInt()

        assertThat(scalarAt(config(), "limits", "max_query_parallelism")).describedAs("query parallelism stays at its default").isNull()
        assertThat(limit).isGreaterThanOrEqualTo(2 * heaviest * DEFAULT_MAX_QUERY_PARALLELISM)
    }

    @Test
    fun `rings live in memory with one replica`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "ingester", "ring", "kvstore", "store")).isEqualTo("inmemory")
        assertThat(scalarAt(yaml, "ingester", "ring", "replication_factor")).isEqualTo("1")
        assertThat(scalarAt(yaml, "distributor", "ring", "kvstore", "store")).isEqualTo("inmemory")
        assertThat(scalarAt(yaml, "store_gateway", "sharding_ring", "kvstore", "store")).isEqualTo("inmemory")
        assertThat(scalarAt(yaml, "store_gateway", "sharding_ring", "replication_factor")).isEqualTo("1")
    }

    @Test
    fun `the TSDB, its WAL and the store-gateway's sync directory live on the control node's disk`() {
        val pod = pod()
        val volume = pod.volumes.single { it.name == "data" }
        val mount = pod.containers[0].volumeMounts.single { it.name == "data" }

        assertThat(volume.hostPath.path).isEqualTo(MimirManifestBuilder.DATA_HOST_PATH)
        assertThat(MimirManifestBuilder.DATA_HOST_PATH).isEqualTo("/mnt/db1/mimir")
        assertThat(scalarAt(config(), "blocks_storage", "tsdb", "dir")).isEqualTo(mount.mountPath + "/tsdb")
        assertThat(scalarAt(config(), "blocks_storage", "bucket_store", "sync_dir")).isEqualTo(mount.mountPath + "/tsdb-sync")
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

        /** Mimir's default `limits.max_query_parallelism`. */
        const val DEFAULT_MAX_QUERY_PARALLELISM = 14
    }
}
