package com.rustyrazorblade.easydblab.localstack

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The live local stack's Tempo config is the cluster's `tempo.yaml` with a few typed patches. These
 * tests read the cluster file from the classpath, as the composer does, and parse the result back.
 */
class TempoLocalConfigTest {
    private val clusterYaml =
        checkNotNull(TempoManifestBuilder::class.java.getResourceAsStream("tempo.yaml")).bufferedReader().use { it.readText() }

    private val rendered = TempoLocalConfig.live(clusterYaml, s3Endpoint = "store-gate:8080")

    private fun parse(text: String): YamlMap = Yaml.default.parseToYamlNode(text) as YamlMap

    private fun nodeAt(
        root: YamlMap,
        vararg path: String,
    ): YamlNode? =
        path.fold(root as YamlNode?) { node, key -> (node as? YamlMap)?.entries?.entries?.firstOrNull { it.key.content == key }?.value }

    /** The node's content as plain maps, lists and strings: kaml's own equality includes source lines. */
    private fun plain(node: YamlNode?): Any? =
        when (node) {
            is YamlMap -> node.entries.entries.associate { it.key.content to plain(it.value) }
            is YamlList -> node.items.map { plain(it) }
            is YamlScalar -> node.content
            else -> node?.toString()
        }

    private fun scalarAt(vararg path: String): String? = (nodeAt(parse(rendered), *path) as? YamlScalar)?.content

    @Test
    fun `Tempo reaches the store through the gate, path-style and without TLS`() {
        assertThat(scalarAt("storage", "trace", "s3", "endpoint")).isEqualTo("store-gate:8080")
        assertThat(scalarAt("storage", "trace", "s3", "insecure")).isEqualTo("true")
        assertThat(scalarAt("storage", "trace", "s3", "forcepathstyle")).isEqualTo("true")
        // The signer replaces the signature; Tempo only needs keys to sign with at all.
        assertThat(scalarAt("storage", "trace", "s3", "access_key")).isNotBlank()
        assertThat(scalarAt("storage", "trace", "s3", "secret_key")).isNotBlank()
        // The bucket and prefix stay the cluster's, expanded from the environment.
        assertThat(scalarAt("storage", "trace", "s3", "bucket")).isEqualTo("\${S3_BUCKET}")
        assertThat(scalarAt("storage", "trace", "s3", "prefix")).isEqualTo("\${TRACES_S3_PREFIX}")
    }

    @Test
    fun `the patched switches are YAML booleans, not strings`() {
        // Tempo's YAML decoder refuses a quoted "true" for a boolean field and does not start.
        assertThat(rendered).contains("insecure: true", "forcepathstyle: true", "compaction_disabled: true")
        assertThat(rendered).doesNotContain("\"true\"", "\"false\"")
    }

    @Test
    fun `the single binary's backend scheduler and worker run inert`() {
        assertThat(scalarAt("overrides", "defaults", "compaction", "compaction_disabled")).isEqualTo("true")
        assertThat(scalarAt("backend_scheduler", "backend_flush_interval")).isEqualTo("87600h")
        assertThat(scalarAt("backend_scheduler", "local_work_path")).isEqualTo("/dev/null/backend-scheduler")
        assertThat(scalarAt("usage_report", "reporting_enabled")).isEqualTo("false")
    }

    @Test
    fun `search and metrics read the backend for everything older than a minute`() {
        assertThat(scalarAt("query_frontend", "search", "query_backend_after")).isEqualTo("1m")
        assertThat(scalarAt("query_frontend", "metrics", "query_backend_after")).isEqualTo("1m")
        // The cluster's own search settings stay beside the patch.
        assertThat(scalarAt("query_frontend", "search", "duration_slo")).isEqualTo("5s")
        assertThat(scalarAt("query_frontend", "trace_by_id", "duration_slo")).isEqualTo("5s")
    }

    @Test
    fun `blocks are found by listing the bucket at Tempo's default poll`() {
        assertThat(scalarAt("storage", "trace", "blocklist_poll_fallback")).isEqualTo("false")
        // Tempo's own 5m default: every poll lists every block of every tenant.
        assertThat(nodeAt(parse(rendered), "storage", "trace", "blocklist_poll")).isNull()
        assertThat(nodeAt(parse(rendered), "storage", "trace", "blocklist_poll_tenant_index_builders")).isNull()
    }

    @Test
    fun `every other setting is the cluster's, unchanged`() {
        val cluster = parse(clusterYaml)
        val local = parse(rendered)

        for (key in listOf("live_store", "distributor", "metrics_generator", "server", "multitenancy_enabled", "stream_over_http_enabled")) {
            assertThat(plain(nodeAt(local, key))).describedAs(key).isEqualTo(plain(nodeAt(cluster, key)))
        }
        assertThat(plain(nodeAt(local, "storage", "trace", "wal"))).isEqualTo(plain(nodeAt(cluster, "storage", "trace", "wal")))
        assertThat(plain(nodeAt(local, "overrides", "defaults", "ingestion"))).isEqualTo(plain(nodeAt(cluster, "overrides", "defaults", "ingestion")))
        val processors = nodeAt(local, "overrides", "defaults", "metrics_generator", "processors") as YamlList
        assertThat(processors.items).isEmpty()
    }
}
