package com.rustyrazorblade.easydblab.configuration.tempo

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
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
 * Tests the Tempo configuration and Deployment as rendered by the real [TemplateService].
 *
 * Tempo keeps every block: compaction, which is also what runs retention in Tempo 3, is off for
 * every tenant, and no retention is configured. Blocks are cut every five minutes, and both
 * write-ahead logs live on the control node's disk so a pod restart does not lose received spans.
 */
class TempoManifestBuilderTest : BaseKoinTest() {
    private lateinit var builder: TempoManifestBuilder

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
        builder = TempoManifestBuilder(getKoin().get())
    }

    private fun config(): String = builder.buildConfigMap().data.getValue("tempo.yaml")

    @Test
    fun `Tempo runs native multi-tenancy`() {
        assertThat(scalarAt(config(), "multitenancy_enabled")).isEqualTo("true")
    }

    @Test
    fun `compaction, and with it retention, is disabled for every tenant`() {
        assertThat(scalarAt(config(), "overrides", "defaults", "compaction", "compaction_disabled")).isEqualTo("true")
    }

    @Test
    fun `no block retention is configured anywhere`() {
        assertThat(config()).doesNotContain("block_retention")
    }

    @Test
    fun `the Tempo 2 ingester and compactor blocks are gone`() {
        assertThat(keysAt(config())).doesNotContain("ingester", "compactor")
    }

    @Test
    fun `blocks are cut every five minutes`() {
        assertThat(scalarAt(config(), "live_store", "max_block_duration")).isEqualTo("5m")
    }

    @Test
    fun `traces are stored under the observability traces prefix of the account bucket`() {
        val yaml = config()

        assertThat(scalarAt(yaml, "storage", "trace", "s3", "bucket")).isEqualTo("\${S3_BUCKET}")
        assertThat(scalarAt(yaml, "storage", "trace", "s3", "prefix")).isEqualTo("\${TRACES_S3_PREFIX}")

        val env =
            builder
                .buildDeployment()
                .spec.template.spec.containers[0]
                .env
        val prefix = env.single { it.name == "TRACES_S3_PREFIX" }
        assertThat(prefix.valueFrom.configMapKeyRef.name).isEqualTo("cluster-config")
        assertThat(prefix.valueFrom.configMapKeyRef.key).isEqualTo("traces_s3_prefix")
        assertThat(env.map { it.name }).doesNotContain("CLUSTER_S3_PREFIX")
    }

    @Test
    fun `both write-ahead logs live on the control node's disk`() {
        val yaml = config()
        val traceWal = scalarAt(yaml, "storage", "trace", "wal", "path")
        val liveStoreWal = scalarAt(yaml, "live_store", "wal", "path")

        val pod =
            builder
                .buildDeployment()
                .spec.template.spec
        val mount = pod.containers[0].volumeMounts.single { it.name == "wal" }
        val volume = pod.volumes.single { it.name == "wal" }

        assertThat(volume.emptyDir).isNull()
        assertThat(volume.hostPath.path).isEqualTo(TempoManifestBuilder.DATA_HOST_PATH)
        assertThat(traceWal).startsWith(mount.mountPath + "/")
        assertThat(liveStoreWal).startsWith(mount.mountPath + "/")
        assertThat(traceWal).isNotEqualTo(liveStoreWal)
    }

    @Test
    fun `a received trace reaches the WAL within about two seconds`() {
        // Tempo 3.0.3 defaults (5s idle, checked every 5s, 30s live) left a pushed trace in memory
        // for up to 10s, and a kill in that window lost it.
        val yaml = config()

        assertThat(scalarAt(yaml, "live_store", "flush_check_period")).isEqualTo("1s")
        assertThat(scalarAt(yaml, "live_store", "max_trace_idle")).isEqualTo("1s")
        assertThat(scalarAt(yaml, "live_store", "max_trace_live")).isEqualTo("10s")
    }

    @Test
    fun `no live-trace count limit discards spans`() {
        // Tempo 3.0.3 defaults to 10000 live traces per tenant and discards every span past it.
        assertThat(scalarAt(config(), "overrides", "defaults", "ingestion", "max_traces_per_user")).isEqualTo("0")
    }

    @Test
    fun `no live-trace byte limit stalls pushes`() {
        assertThat(scalarAt(config(), "live_store", "max_live_traces_bytes")).isEqualTo("0")
    }

    @Test
    fun `no trace is discarded for its size`() {
        assertThat(scalarAt(config(), "overrides", "defaults", "global", "max_bytes_per_trace")).isEqualTo("0")
    }

    @Test
    fun `no attribute value is truncated`() {
        // Tempo 3.0.3's distributor truncates attribute values past 2048 bytes by default; a
        // per-tenant override of 0 falls back to it, so only the distributor setting turns it off.
        assertThat(scalarAt(config(), "distributor", "max_attribute_bytes")).isEqualTo("0")
    }

    @Test
    fun `the ingestion rate limit is far above what a test cluster sends`() {
        val yaml = config()
        val tempoDefault = 30_000_000L

        listOf("rate_limit_bytes", "burst_size_bytes").forEach { key ->
            val value = scalarAt(yaml, "overrides", "defaults", "ingestion", key)?.toLongOrNull()
            assertThat(value).describedAs(key).isNotNull().isGreaterThanOrEqualTo(tempoDefault * 10)
        }
    }

    /** The metrics generator, when enabled, writes to Mimir with the tenant of the spans it read. */
    @Test
    fun `the metrics generator remote-writes to Mimir with the tenant header`() {
        val yaml = config()
        val remoteWrite = nodeAt(yaml, "metrics_generator", "storage", "remote_write") as YamlList
        val target = remoteWrite.items.single() as YamlMap

        assertThat(target.get<YamlScalar>("url")?.content).isEqualTo("http://mimir.default.svc.cluster.local:9009/api/v1/push")
        assertThat(scalarAt(yaml, "metrics_generator", "storage", "remote_write_add_org_id_header")).isEqualTo("true")
        assertThat(yaml).doesNotContainIgnoringCase("victoria")
    }
}
