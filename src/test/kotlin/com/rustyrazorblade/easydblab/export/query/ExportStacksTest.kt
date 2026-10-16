package com.rustyrazorblade.easydblab.export.query

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeNetworks
import com.rustyrazorblade.easydblab.localstack.ComposeService
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two temporary stacks of a query export (design section 7). The reader is the live local
 * stack's read-only store access and backends, with no Grafana, each backend on a random loopback
 * port. The writer is a Loki and a Tempo with filesystem backends that accept old data and keep its
 * own times; it has no Mimir (metrics are copied as blocks).
 */
class ExportStacksTest {
    private val live = LocalStackSpec.Mode.Live(bucket = "acct", region = "eu-west-1", awsProfile = "lab", home = "acme")

    private fun compose(files: Map<String, ByteArray>): ComposeFile =
        ComposeFile.fromYaml(String(files.getValue(Constants.LocalStack.COMPOSE_FILE)))

    private fun scalar(
        yaml: String,
        path: String,
    ): String? {
        var node: YamlNode? = Yaml.default.parseToYamlNode(yaml)
        path.split('.').forEach { key ->
            node =
                (node as? YamlMap)
                    ?.entries
                    ?.entries
                    ?.firstOrNull { it.key.content == key }
                    ?.value
        }
        return (node as? YamlScalar)?.content
    }

    private fun has(
        yaml: String,
        path: String,
    ): Boolean {
        var node: YamlNode? = Yaml.default.parseToYamlNode(yaml)
        path.split('.').forEach { key ->
            node =
                (node as? YamlMap)
                    ?.entries
                    ?.entries
                    ?.firstOrNull { it.key.content == key }
                    ?.value
        }
        return node != null
    }

    @Test
    fun `the reader is the read-only store access and the backends, with no Grafana`() {
        val services = compose(ExportReaderStack.compose(live)).services

        assertThat(
            services.keys,
        ).containsExactlyInAnyOrder("store-signer", "store-gate", "store-check", "memcached", "mimir", "loki", "tempo")
    }

    @Test
    fun `only the reader's gate and store check share a network with its signer, whose way out is its own`() {
        val reader = compose(ExportReaderStack.compose(live))
        val egress = ComposeNetworks.of(reader, "store-signer") - ComposeNetworks.of(reader, "store-gate")

        assertThat(ComposeNetworks.sharingWith(reader, "store-signer")).isEqualTo(ComposeNetworks.SIGNER_PEERS)
        assertThat(egress).isNotEmpty()
        listOf("mimir", "loki", "tempo", "memcached").forEach { backend ->
            assertThat(
                ComposeNetworks.sharingWith(reader, backend),
            ).describedAs(backend).contains("store-gate").doesNotContain("store-signer")
            assertThat(ComposeNetworks.of(reader, backend)).describedAs(backend).doesNotContainAnyElementsOf(egress)
        }
    }

    @Test
    fun `only the reader's backends are published, each on a random loopback port`() {
        val services = compose(ExportReaderStack.compose(live)).services

        assertThat(services.filterValues { it.ports != null }.mapValues { it.value.ports })
            .containsOnly(
                java.util.AbstractMap.SimpleEntry("mimir", listOf("127.0.0.1::9009")),
                java.util.AbstractMap.SimpleEntry("loki", listOf("127.0.0.1::3100")),
                java.util.AbstractMap.SimpleEntry("tempo", listOf("127.0.0.1::3200")),
            )
    }

    @Test
    fun `the writer is a Loki and a Tempo, run as the operator, published on random loopback ports, with no Mimir`() {
        val services = compose(ExportWriterStack.compose("acme", owner = "501:20")).services

        assertThat(services.keys).containsExactlyInAnyOrder("loki", "tempo")
        assertThat(services.values.map { it.user }).containsOnly("501:20")
        assertThat(services.values.map { it.image }).containsExactlyInAnyOrder(LocalImages.LOKI, LocalImages.TEMPO)
        assertThat(services.getValue("loki").ports).containsExactly("127.0.0.1::3100")
        assertThat(services.getValue("tempo").ports).containsExactly("127.0.0.1::3200", "127.0.0.1::4321")
        services.values.forEach { service: ComposeService ->
            assertThat(
                service.volumes
                    .orEmpty()
                    .single { it.target == ExportWriterStack.STORE }
                    .readOnly,
            ).isFalse()
        }
    }

    @Test
    fun `the writer's Tempo keeps old spans' own times in their blocks, with both ingestion slacks wide and never zero`() {
        val tempo = String(ExportWriterStack.compose("acme", owner = "501:20").getValue("config/tempo/tempo.yaml"))

        assertThat(scalar(tempo, "live_store.wal.ingestion_time_range_slack")).isEqualTo("87600h")
        assertThat(scalar(tempo, "storage.trace.wal.ingestion_time_range_slack")).isEqualTo("87600h")
        assertThat(scalar(tempo, "storage.trace.backend")).isEqualTo("local")
        assertThat(scalar(tempo, "storage.trace.local.path")).isEqualTo("${ExportWriterStack.STORE}/tempo")
        assertThat(has(tempo, "storage.trace.s3")).isFalse()
        assertThat(scalar(tempo, "overrides.defaults.compaction.compaction_disabled")).isEqualTo("true")
        assertThat(scalar(tempo, "usage_report.reporting_enabled")).isEqualTo("false")
    }

    @Test
    fun `the writer's Loki accepts old lines and writes a filesystem store, with no S3`() {
        val loki = String(ExportWriterStack.compose("acme", owner = "501:20").getValue("config/loki/loki.yaml"))

        assertThat(scalar(loki, "limits_config.reject_old_samples")).isEqualTo("false")
        assertThat(has(loki, "common.storage.s3")).isFalse()
        assertThat(scalar(loki, "storage_config.filesystem.directory")).isEqualTo(ExportWriterStack.STORE)
        assertThat(scalar(loki, "limits_config.retention_period")).isEqualTo("0s")
        assertThat(scalar(loki, "compactor.retention_enabled")).isEqualTo("false")
    }

    @Test
    fun `every writer image is a registered pin`() {
        assertThat(compose(ExportWriterStack.compose("acme", "1:1")).services.values.map { it.image }).allSatisfy {
            assertThat(LocalImages.all).contains(it)
        }
    }
}
