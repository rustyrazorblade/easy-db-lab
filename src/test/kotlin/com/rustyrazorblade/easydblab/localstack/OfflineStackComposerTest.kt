package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.YamlTestSupport.keysAt
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.grafana.BackendUrls
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDatasourceSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The offline stack an export carries: the live stack's read-only backends over the zip's `data/`
 * directory with filesystem backends, and nothing that needs the network, AWS credentials or a pull
 * beyond the registered images. Plain `docker compose up` opens it.
 */
class OfflineStackComposerTest {
    private val spec =
        LocalStackSpec(
            mode = LocalStackSpec.Mode.Offline(dataDir = Constants.Export.DATA_DIR, home = "acme"),
            tenants = listOf("acme"),
        )
    private val files = LocalStackComposer.compose(spec)
    private val compose = ComposeFile.fromYaml(text(Constants.LocalStack.COMPOSE_FILE))
    private val backends =
        listOf(Constants.LocalStack.Services.MIMIR, Constants.LocalStack.Services.LOKI, Constants.LocalStack.Services.TEMPO)

    private fun text(path: String): String = String(checkNotNull(files[path]) { "no $path among ${files.keys}" })

    private fun service(name: String): ComposeService = checkNotNull(compose.services[name]) { "no service $name" }

    private fun command(name: String): List<String> = service(name).command.orEmpty()

    @Test
    fun `the stack is the backends, memcached, Grafana and the documents view, with no gate, signer or store check`() {
        assertThat(compose.services.keys).containsExactlyInAnyOrder("mimir", "memcached", "loki", "tempo", "grafana", "documents-web")
    }

    @Test
    fun `every image is a registered pin, so the opening machine needs only the images the README lists`() {
        assertThat(compose.services.values.map { it.image }).allSatisfy { assertThat(LocalImages.all).contains(it) }
    }

    @Test
    fun `Grafana installs no plugin at start, which would need the network and stops an offline Grafana`() {
        val environment = service(Constants.LocalStack.Services.GRAFANA).environment.orEmpty()

        assertThat(environment.keys).doesNotContain("GF_INSTALL_PLUGINS", "GF_PLUGINS_PREINSTALL", "GF_PLUGINS_PREINSTALL_SYNC")
        assertThat(environment).containsEntry(Constants.Grafana.DISABLE_SANITIZE_HTML_ENV, "true")
        assertThat(environment).containsEntry("GF_DATAPROXY_TIMEOUT", "300")
    }

    @Test
    fun `Grafana's data source proxy is allowed only the stack's backends`() {
        val allowed = service(Constants.LocalStack.Services.GRAFANA).environment.orEmpty()["GF_SECURITY_DATA_SOURCE_PROXY_WHITELIST"]

        assertThat(allowed?.split(' ')).containsExactlyInAnyOrder("mimir:9009", "loki:3100", "tempo:3200", "pyroscope:4040")
    }

    @Test
    fun `the zip's data is mounted read-only into every backend and the documents view, and state is written under cache`() {
        (backends + Constants.LocalStack.Services.DOCUMENTS).forEach { name ->
            val data = service(name).volumes.orEmpty().filter { it.source == "./${Constants.Export.DATA_DIR}" }
            assertThat(data).describedAs(name).singleElement().satisfies({ assertThat(it.readOnly).isTrue() })
        }
        backends.forEach { name ->
            assertThat(
                service(name)
                    .volumes
                    .orEmpty()
                    .filter { it.readOnly != true }
                    .map { it.source },
            ).describedAs(name)
                .allSatisfy { assertThat(it).startsWith("./${Constants.LocalStack.CACHE_DIR}/") }
        }
    }

    @Test
    fun `nothing waits for a store check, and nothing reads AWS credentials`() {
        assertThat(compose.services.values.flatMap { it.dependsOn.orEmpty().keys }).doesNotContain("store-check", "store-gate")
        assertThat(
            compose.services.values
                .flatMap { it.volumes.orEmpty() }
                .map { it.source },
        ).noneMatch { it.contains(".aws") }
        assertThat(compose.services.values.flatMap { it.environment.orEmpty().keys }).doesNotContain("AWS_PROFILE")
    }

    @Test
    fun `Mimir runs the live stack's read path over a filesystem store, with no S3 and no compactor`() {
        assertThat(command("mimir")).contains(
            "-target=query-frontend,querier,store-gateway,query-scheduler,ingester",
            "-usage-stats.enabled=false",
            "-blocks-storage.backend=filesystem",
            "-blocks-storage.filesystem.dir=/store",
        )
        assertThat(command("mimir")).noneMatch { it.contains("s3") || it.contains("compactor") }
        assertThat(text("config/mimir/mimir.yaml")).contains("max_stale_period: 87600h", "ignore_blocks_within: 0")
    }

    @Test
    fun `Loki runs the live stack's read path over a filesystem store, with retention and deletion off`() {
        val loki = text("config/loki/loki.yaml")

        assertThat(command("loki")).contains(
            "-target=read,query-scheduler",
            "-querier.query-store-only=true",
            "-reporting.enabled=false",
        )
        assertThat(command("loki")).noneMatch { it.contains("s3") }
        assertThat(scalarAt(loki, "storage_config", "filesystem", "directory")).isEqualTo("/store")
        assertThat(scalarAt(loki, "schema_config", "configs", "0", "object_store")).isEqualTo("filesystem")
        assertThat(keysAt(loki, "common", "storage")).doesNotContain("s3")
        assertThat(scalarAt(loki, "compactor", "retention_enabled")).isEqualTo("false")
        assertThat(scalarAt(loki, "limits_config", "deletion_mode")).isEqualTo("disabled")
    }

    @Test
    fun `Tempo is the single binary over a local backend, its scheduler inert and its search reading the backend after a minute`() {
        val tempo = text("config/tempo/tempo.yaml")

        assertThat(command("tempo")).noneMatch { it.startsWith("-target") }
        assertThat(scalarAt(tempo, "storage", "trace", "backend")).isEqualTo("local")
        assertThat(scalarAt(tempo, "storage", "trace", "local", "path")).isEqualTo("/store/${Constants.Observability.TRACES_ROOT}")
        assertThat(keysAt(tempo, "storage", "trace")).doesNotContain("s3")
        assertThat(scalarAt(tempo, "overrides", "defaults", "compaction", "compaction_disabled")).isEqualTo("true")
        assertThat(scalarAt(tempo, "backend_scheduler", "backend_flush_interval")).isEqualTo("87600h")
        assertThat(scalarAt(tempo, "backend_scheduler", "local_work_path")).isEqualTo("/dev/null/backend-scheduler")
        assertThat(scalarAt(tempo, "usage_report", "reporting_enabled")).isEqualTo("false")
        assertThat(scalarAt(tempo, "query_frontend", "search", "query_backend_after")).isEqualTo("1m")
        assertThat(scalarAt(tempo, "query_frontend", "metrics", "query_backend_after")).isEqualTo("1m")
    }

    @Test
    fun `the documents view serves the zip's reports from disk, GET only, with no upstream`() {
        val nginx = text("config/documents-web/nginx.conf")

        assertThat(nginx).contains("root /data;", "location /reports/", "!= GET")
        assertThat(nginx).doesNotContain("proxy_pass")
    }

    @Test
    fun `Grafana's datasources are a cluster's for the export's tenants, on the compose backends`() {
        assertThat(text("config/grafana/provisioning/datasources/datasources.yaml"))
            .isEqualTo(GrafanaDatasourceSet.build(spec.tenantSet, BackendUrls.LOCAL_COMPOSE).toYaml())
    }
}
