package com.rustyrazorblade.easydblab.export

import com.charleskorn.kaml.Yaml
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaServerSettings
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.ComposeProject
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.NetworkMinio
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import com.rustyrazorblade.easydblab.localstack.StoreSeed
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * A tenant export against a store in MinIO that the cluster's own writers filled for two tenants,
 * `acme` and `default`, then the export opened with `docker compose up` as an operator opens it.
 *
 * Scenarios:
 * - **Nothing in S3 changes.** The bucket listing, keys and ETags, is the same before and after the
 *   export. The export reads through [S3ExportStore], which has no write call at all.
 * - **The zip holds the tenant's store and nothing of the other tenant**, with its own Mimir bucket
 *   index and Tempo tenant index, each listing exactly the blocks in the zip.
 * - **The export opens offline.** The unzipped stack runs on a project network made `internal`, so
 *   no container can reach anything outside it; every image is a registered pin and Grafana installs
 *   no plugin. Through the opened stack's own Grafana datasources (its data proxy), the tenant's
 *   metrics, logs and trace show, the other tenant's do not, the dashboards are provisioned and the
 *   documents view serves the report. Every file under `data/` is byte-identical afterwards.
 *
 * The test's `compose.override.yml` changes only the network; the stack is the export's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TenantExportIntegrationTest {
    private companion object {
        const val TENANT = "acme"
        const val OTHER = "default"
        const val REPORT = "<html>acme report</html>"
        val READY_WAIT: Duration = Duration.ofMinutes(5)
        val ANSWER_WAIT: Duration = Duration.ofMinutes(4)
        val POLL: Duration = Duration.ofSeconds(3)
        const val LOG_TAIL = 4000
    }

    private lateinit var root: File
    private val suffix = UUID.randomUUID().toString().take(8)
    private val bucket = "edl-export-it-$suffix"
    private lateinit var network: Network
    private lateinit var minio: NetworkMinio
    private lateinit var seeded: StoreSeed.Seeded
    private lateinit var listingBefore: Map<String, String>
    private lateinit var zip: File
    private lateinit var stack: File
    private lateinit var project: ComposeProject
    private lateinit var dataBefore: Map<String, String>

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @BeforeAll
    fun export(
        @TempDir tempDir: File,
    ) {
        root = tempDir
        network = Network.newNetwork()
        minio = NetworkMinio(network)
        minio.createBucket(bucket)
        seeded = StoreSeed(minio, bucket).seed(listOf(TENANT, OTHER))
        putObject("grafana/annotations/$TENANT/20261015-100000_c-$TENANT.json", "[]")
        putObject("grafana/annotations/$OTHER/20261015-100000_c-$OTHER.json", "[]")
        putObject("reports/$TENANT/exp/index.html", REPORT)
        putObject("reports/$OTHER/exp/index.html", "<html>other</html>")
        listingBefore = listing()

        val exporter =
            TenantExporter(
                stores = { S3ExportStore(minio.s3Client(), bucket) },
                stack =
                    ExportStackFiles(
                        PackagedDashboardTree(GrafanaDashboardCatalog.discover(), PackagedKitDashboards.discover()),
                        toolVersion = "it",
                        grafanaPort = freePort(),
                        documentsPort = freePort(),
                    ),
                zipWriter = ExportZipWriter(),
                diskSpace = { it.usableSpace },
                eventBus = EventBus(),
            )
        zip = File(root, "$TENANT-export.zip")
        exporter.export(ExportTarget.of(zip), TENANT)
    }

    @AfterAll
    fun cleanUp() {
        if (::project.isInitialized) project.compose("down", "-v", "--remove-orphans", "--timeout", "30")
        if (::stack.isInitialized) removeRootOwnedState()
        if (::minio.isInitialized) minio.close()
        if (::network.isInitialized) network.close()
    }

    private fun putObject(
        key: String,
        content: String,
    ) {
        minio.s3Client().use {
            it.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(key)
                    .build(),
                RequestBody.fromString(content),
            )
        }
    }

    /** Every object in the bucket, key to ETag, listed straight from MinIO. */
    private fun listing(): Map<String, String> =
        minio.s3Client().use { s3 ->
            s3
                .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .associate { it.key() to it.eTag() }
        }

    private fun zipEntries(): Map<String, ByteArray> =
        ZipFile(zip).use { file ->
            file.entries().asSequence().filter { !it.isDirectory }.associate {
                it.name to
                    file.getInputStream(it).readBytes()
            }
        }

    private fun gunzip(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()).jsonObject

    @Test
    @Order(1)
    fun `the export writes nothing to S3 - the bucket listing, keys and ETags, is unchanged`() {
        assertThat(zip).isFile()
        assertThat(listing()).isEqualTo(listingBefore)
    }

    @Test
    @Order(2)
    fun `the zip holds the tenant's store, its own indexes of exactly the blocks in it, and nothing of the other tenant`() {
        val entries = zipEntries()
        val data = entries.keys.filter { it.startsWith("$TENANT-export/data/") }.map { it.removePrefix("$TENANT-export/data/") }
        val lokiIndexOfOther = Regex("loki/index/[^/]+/$OTHER/.*")

        assertThat(data).anyMatch { it.startsWith("mimir/$TENANT/") }
        assertThat(data).anyMatch { it.startsWith("tempo/$TENANT/") }
        assertThat(data).anyMatch { it.startsWith("loki/$TENANT/") }
        assertThat(data).anyMatch { it.startsWith("loki/index/") }
        assertThat(data).contains("grafana/annotations/$TENANT/20261015-100000_c-$TENANT.json", "reports/$TENANT/exp/index.html")
        assertThat(data).noneMatch { it.startsWith("mimir/$OTHER/") || it.startsWith("tempo/$OTHER/") || it.startsWith("loki/$OTHER/") }
        assertThat(data).noneMatch { lokiIndexOfOther.matches(it) || it.contains("/$OTHER/") }

        fun blocksIn(prefix: String) =
            data
                .filter {
                    it.startsWith(
                        prefix,
                    ) &&
                        it.removePrefix(prefix).contains('/')
                }.map { it.removePrefix(prefix).substringBefore('/') }
                .toSet()
        val mimirIndex = gunzip(entries.getValue("$TENANT-export/data/mimir/$TENANT/bucket-index.json.gz"))
        assertThat(
            mimirIndex.getValue("blocks").jsonArray.map {
                it.jsonObject
                    .getValue("block_id")
                    .jsonPrimitive.content
            },
        ).containsExactlyInAnyOrderElementsOf(blocksIn("mimir/$TENANT/"))
            .isNotEmpty()
        val tempoIndex = gunzip(entries.getValue("$TENANT-export/data/tempo/$TENANT/index.json.gz"))
        assertThat(
            tempoIndex.getValue("meta").jsonArray.map {
                it.jsonObject
                    .getValue("blockID")
                    .jsonPrimitive.content
            },
        ).containsExactlyInAnyOrderElementsOf(blocksIn("tempo/$TENANT/"))
            .isNotEmpty()
    }

    @Test
    @Order(3)
    fun `the zip's stack needs no image beyond the registered pins and installs no Grafana plugin`() {
        val compose = ComposeFile.fromYaml(String(zipEntries().getValue("$TENANT-export/${Constants.LocalStack.COMPOSE_FILE}")))

        assertThat(compose.services.values.map { it.image }).allSatisfy { assertThat(LocalImages.all).contains(it) }
        assertThat(compose.services.values.flatMap { it.environment.orEmpty().keys }).doesNotContain("GF_INSTALL_PLUGINS", "AWS_PROFILE")
        assertThat(compose.services.values.flatMap { it.environment.orEmpty().values })
            .noneMatch { value -> GrafanaServerSettings.DOWNLOADED_PLUGINS.any { value.contains(it) } }
    }

    @Test
    @Order(4)
    fun `the unzipped export opens offline with docker compose up and shows the tenant's metrics, logs, trace, dashboards and report`() {
        openStack()

        awaitUntil("Grafana answers", { project.logs(Constants.LocalStack.Services.GRAFANA).takeLast(LOG_TAIL) }) {
            grafana("/api/health")?.contains("\"database\"") == true
        }
        val cluster = seeded.cluster(TENANT)
        awaitUntil("the tenant's metrics through Grafana's mimir datasource", {
            project.logs(Constants.LocalStack.Services.MIMIR).takeLast(LOG_TAIL)
        }) {
            metricClusters() == listOf(cluster)
        }
        awaitUntil(
            "the tenant's logs through Grafana's loki datasource",
            { project.logs(Constants.LocalStack.Services.LOKI).takeLast(LOG_TAIL) },
        ) {
            logClusters() == List(seeded.linesPerCluster) { cluster }
        }
        awaitUntil("the tenant's trace through Grafana's tempo datasource", {
            project.logs(Constants.LocalStack.Services.TEMPO).takeLast(LOG_TAIL)
        }) {
            traceFound(seeded.traceIds.getValue(TENANT))
        }
        assertThat(traceFound(seeded.traceIds.getValue(OTHER))).describedAs("the other tenant's trace").isFalse()

        awaitUntil("the dashboards provisioned", { grafana("/api/search?type=dash-db").orEmpty() }) {
            grafana("/api/search?type=dash-db")?.let { Json.parseToJsonElement(it).jsonArray.size > 1 } == true
        }
        // The command output is collected line by line, so it ends with a line break the file does not have.
        assertThat(
            grafana(
                "http://${Constants.LocalStack.Services.DOCUMENTS}:${Constants.Grafana.Documents.WEB_PORT}/reports/$TENANT/exp/index.html",
                absolute = true,
            )?.trimEnd(),
        ).isEqualTo(REPORT)
        assertThat(File(stack, "data/grafana/annotations/$TENANT/20261015-100000_c-$TENANT.json")).isFile()
    }

    /**
     * The documents view serves the reports from the data directory, which also holds every other
     * signal: from inside Grafana, nothing outside `/reports/` is served, a `..` in the path does not
     * leave it, and only GET is answered, while the report itself still is.
     */
    @Test
    @Order(5)
    fun `the opened export's documents view serves only GETs of the reports`() {
        val documents = "http://${Constants.LocalStack.Services.DOCUMENTS}:${Constants.Grafana.Documents.WEB_PORT}"
        val report = "$documents/reports/$TENANT/exp/index.html"
        val refused =
            mapOf(
                "a Mimir bucket index" to listOf("$documents/mimir/$TENANT/bucket-index.json.gz"),
                "an annotation backup" to listOf("$documents/grafana/annotations/$TENANT/20261015-100000_c-$TENANT.json"),
                "a path that leaves /reports/" to listOf("$documents/reports/%2e%2e/mimir/$TENANT/bucket-index.json.gz"),
                "a POST to the report" to listOf("--post-data=x", report),
            )
        assertThat(File(stack, "data/mimir/$TENANT/bucket-index.json.gz")).describedAs("the file the probes ask for").isFile()

        refused.forEach { (what, args) ->
            val answer = wgetFromGrafana(*args.toTypedArray())
            assertThat(answer.exitCode).describedAs("$what: ${answer.output}").isNotEqualTo(0)
        }
        val served = wgetFromGrafana(report)
        assertThat(served.exitCode).describedAs(served.output).isEqualTo(0)
        assertThat(served.output.trimEnd()).isEqualTo(REPORT)
    }

    @Test
    @Order(6)
    fun `opening the export changes no file under data`() {
        assertThat(dataSnapshot()).isEqualTo(dataBefore)
        assertThat(listing()).isEqualTo(listingBefore)
    }

    /** The project network, made internal: no container of the stack can reach anything outside it. */
    @Serializable
    private data class InternalNetwork(
        val internal: Boolean,
    )

    @Serializable
    private data class NetworkOverride(
        val networks: Map<String, InternalNetwork>,
    )

    /** Unpacks the zip into [parent], as `unzip` does, directory entries included. */
    private fun unzip(parent: File) {
        ZipFile(zip).use { file ->
            file.entries().asSequence().forEach { entry ->
                val target = File(parent, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    file.getInputStream(entry).use { input -> target.writeBytes(input.readBytes()) }
                }
            }
        }
    }

    private fun openStack() {
        val parent = File(root, "opened").apply { mkdirs() }
        unzip(parent)
        stack = File(parent, "$TENANT-export")
        assertThat(File(stack, Constants.Export.README_FILE)).isFile()
        val override = NetworkOverride(mapOf("default" to InternalNetwork(internal = true)))
        File(stack, Constants.LocalStack.OVERRIDE_FILE).writeText(Yaml.default.encodeToString(NetworkOverride.serializer(), override))
        dataBefore = dataSnapshot()
        project = ComposeProject(stack, "edl-export-it-$suffix")
        project.composeOrFail("up", "-d")
    }

    /** sha256 of every file under the opened stack's `data/`, by relative path. */
    private fun dataSnapshot(): Map<String, String> {
        val data = File(stack, Constants.Export.DATA_DIR)
        return data.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(data).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * A GET from inside the Grafana container: Grafana's own API on loopback, or with [absolute] any
     * URL on the project network. Null when the request fails.
     */
    private fun grafana(
        path: String,
        absolute: Boolean = false,
    ): String? {
        val url = if (absolute) path else "http://127.0.0.1:${Constants.K8s.GRAFANA_PORT}$path"
        val result = wgetFromGrafana(url)
        return result.output.takeIf { result.exitCode == 0 }
    }

    /** `wget -q -O - <args>` inside the Grafana container, on the project network. */
    private fun wgetFromGrafana(vararg args: String): ComposeProject.Result =
        project.compose("exec", "-T", Constants.LocalStack.Services.GRAFANA, "wget", "-q", "-O", "-", *args)

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    /** The `cluster` of each probe series Grafana's stable `mimir` datasource answers. */
    private fun metricClusters(): List<String>? {
        val body =
            grafana("/api/datasources/proxy/uid/mimir/api/v1/query?query=${encode("last_over_time(${seeded.metric}[1h])")}") ?: return null
        return runCatching {
            Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject.getValue("result").jsonArray.map {
                it.jsonObject
                    .getValue("metric")
                    .jsonObject
                    .getValue("cluster")
                    .jsonPrimitive.content
            }
        }.getOrNull()
    }

    /** The cluster of each log line Grafana's stable `loki` datasource answers over the last hour. */
    private fun logClusters(): List<String>? {
        val query = encode("""{cluster=~"c-.+"}""")
        val body = grafana("/api/datasources/proxy/uid/loki/loki/api/v1/query_range?query=$query&since=1h&limit=1000") ?: return null
        return runCatching {
            Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject.getValue("result").jsonArray.flatMap { stream ->
                val cluster =
                    stream.jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("cluster")
                        .jsonPrimitive.content
                List(
                    stream.jsonObject
                        .getValue("values")
                        .jsonArray.size,
                ) { cluster }
            }
        }.getOrNull()
    }

    private fun traceFound(traceId: String): Boolean {
        val body = grafana("/api/datasources/proxy/uid/tempo/api/v2/traces/$traceId") ?: return false
        return ObservabilityBackends.traceFound(200, body)
    }

    /** The backends and Grafana run as root, so a container removes the state they wrote. */
    private fun removeRootOwnedState() {
        GenericContainer(Constants.LocalStack.HEALTHCHECK_TOOLS_IMAGE)
            .withCreateContainerCmdModifier { it.hostConfig?.withBinds(Bind(stack.absolutePath, Volume("/stack"))) }
            .withCommand("rm", "-rf", "/stack/${Constants.LocalStack.CACHE_DIR}", "/stack/${Constants.LocalStack.GRAFANA_DATA_DIR}")
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))
            .use { it.start() }
    }

    private fun awaitUntil(
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val within = if (what.startsWith("Grafana")) READY_WAIT else ANSWER_WAIT
        val deadline = System.nanoTime() + within.toNanos()
        while (!done() && System.nanoTime() < deadline) Thread.sleep(POLL.toMillis())
        assertThat(done()).describedAs { "$what within $within\n${detail()}" }.isTrue()
    }
}
