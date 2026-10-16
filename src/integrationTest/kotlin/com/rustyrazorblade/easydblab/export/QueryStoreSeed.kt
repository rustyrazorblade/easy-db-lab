package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.localstack.NetworkMinio
import com.rustyrazorblade.easydblab.localstack.YamlDocument
import com.rustyrazorblade.easydblab.localstack.YamlPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Fills a bucket the way several clusters of two tenants leave the shared store, for the query export
 * test: Mimir blocks with each tenant's bucket index (written by the account compactor's own container),
 * Loki chunks and index, and Tempo blocks, every signal labelled with `cluster`, `experiment` and
 * `edl_tenant` as the collector labels them. One cluster also has a days-old `up`, log line and span:
 * that cluster ran then. The writers are the cluster's images and files pointed at MinIO; the seed
 * Mimir accepts the old samples out of order, and the seed Tempo keeps the old span's own time in its
 * block, as a cluster's Tempo did when the span was new. Every writer is stopped before [seed]
 * returns.
 *
 * @property minio the store.
 * @property bucket the account bucket.
 */
class QueryStoreSeed(
    private val minio: NetworkMinio,
    private val bucket: String,
) {
    /**
     * One cluster to write.
     *
     * @property name its `<name>-<id>`.
     * @property tenant its tenant.
     * @property experiment its experiment.
     */
    data class Cluster(
        val name: String,
        val tenant: String,
        val experiment: String,
    )

    /**
     * What [seed] wrote.
     *
     * @property traceIds each cluster's recent trace, by cluster.
     * @property oldTraceId the days-old trace of [old].
     * @property oldAt when the old trace, line and samples are.
     * @property probe the structured-metadata value every log line carries, by cluster.
     */
    data class Seeded(
        val traceIds: Map<String, String>,
        val oldTraceId: String,
        val oldAt: Instant,
        val probe: Map<String, String>,
    )

    private companion object {
        const val METRIC = "edl_query_probe"
        const val SAMPLES = 8
        const val STEP_SECONDS = 30L
        const val STOP_TIMEOUT_SECONDS = 120
        const val LOG_TAIL = 4000
        const val SPAN_NANOS = 1_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1000L
        val OLD_AGE: Duration = Duration.ofDays(3)
        val STARTUP: Duration = Duration.ofMinutes(3)
        val WRITE_WAIT: Duration = Duration.ofMinutes(6)
        val POLL: Duration = Duration.ofSeconds(3)
    }

    private val docker = DockerClientFactory.instance().client()
    private val s3 = minio.s3Client()

    /** Writes every signal of [clusters], with [old]'s days-old data too, and stops every writer. */
    fun seed(
        clusters: List<Cluster>,
        old: Cluster,
    ): Seeded {
        val now = Instant.now()
        val seeded =
            Seeded(
                traceIds = clusters.associate { it.name to id() },
                oldTraceId = id(),
                oldAt = now.minus(OLD_AGE),
                probe = clusters.associate { it.name to "p-${it.name}" },
            )
        seedMetrics(clusters, old, seeded, now)
        seedLogs(clusters, old, seeded, now)
        seedTraces(clusters, old, seeded, now)
        buildBucketIndexes(clusters.map { it.tenant }.distinct())
        return seeded
    }

    private fun id(): String = UUID.randomUUID().toString().replace("-", "")

    private fun labels(cluster: Cluster): Map<String, String> =
        mapOf("cluster" to cluster.name, "experiment" to cluster.experiment, "edl_tenant" to cluster.tenant)

    private fun s3Flags(prefix: String): List<String> =
        listOf(
            "-$prefix.endpoint=${minio.networkEndpoint}",
            "-$prefix.insecure=true",
            "-$prefix.access-key-id=${NetworkMinio.USER}",
            "-$prefix.secret-access-key=${NetworkMinio.PASSWORD}",
        )

    private fun storeEnv(): Map<String, String> =
        mapOf(
            "S3_BUCKET" to bucket,
            "AWS_REGION" to NetworkMinio.REGION,
            "METRICS_S3_PREFIX" to Constants.Observability.METRICS_ROOT,
            "LOGS_S3_PREFIX" to Constants.Observability.LOGS_ROOT,
            "TRACES_S3_PREFIX" to Constants.Observability.TRACES_ROOT,
            "TENANT" to Constants.Observability.DEFAULT_TENANT,
            "CLUSTER_NAME" to "seed",
            "EXPERIMENT" to "seed",
        )

    /**
     * One writer: a cluster backend's image, its configuration file, and the flags that point it at MinIO.
     *
     * @property dataDir its state directory, a tmpfs here.
     * @property readyPort the port that serves `/ready`.
     * @property extraPorts other ports the seed reaches, such as Tempo's OTLP receiver.
     */
    private data class Writer(
        val image: String,
        val configFile: String,
        val config: String,
        val command: List<String>,
        val dataDir: String,
        val readyPort: Int,
        val extraPorts: List<Int> = emptyList(),
    )

    private fun start(writer: Writer): GenericContainer<*> =
        GenericContainer(writer.image)
            .withNetwork(minio.network)
            .withCreateContainerCmdModifier { it.withUser("root") }
            .withTmpFs(mapOf(writer.dataDir to "rw"))
            .withCopyToContainer(Transferable.of(writer.config), writer.configFile)
            .withEnv(storeEnv())
            .withCommand(*(listOf("-config.file=${writer.configFile}", "-config.expand-env=true") + writer.command).toTypedArray())
            .withExposedPorts(writer.readyPort, *writer.extraPorts.toTypedArray())
            .waitingFor(Wait.forHttp("/ready").forPort(writer.readyPort).withStartupTimeout(STARTUP))
            .apply { start() }

    private fun seedMetrics(
        clusters: List<Cluster>,
        old: Cluster,
        seeded: Seeded,
        now: Instant,
    ) {
        val mimir =
            start(
                Writer(
                    MimirManifestBuilder.IMAGE,
                    "/etc/mimir/mimir.yaml",
                    CompactorTaskDefinition.resource(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                    // The old cluster's samples are days behind the head: accept them out of order.
                    s3Flags("blocks-storage.s3") +
                        listOf("-blocks-storage.s3.bucket-lookup-type=path", "-ingester.out-of-order-time-window=96h"),
                    "/data",
                    Constants.K8s.MIMIR_HTTP_PORT,
                ),
            )
        try {
            val base = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)

            fun times(from: Instant) = (0 until SAMPLES).map { from.plusSeconds(it * STEP_SECONDS).toEpochMilli() }
            val recent = times(now.minusSeconds(SAMPLES * STEP_SECONDS))
            val oldTimes = times(seeded.oldAt)
            clusters.forEach { cluster ->
                val written = if (cluster == old) oldTimes + recent else recent
                written.forEachIndexed { index, at ->
                    val series =
                        listOf("up", METRIC).map { name ->
                            PrometheusRemoteWrite.Series(mapOf("__name__" to name, "job" to "node") + labels(cluster), index.toDouble(), at)
                        }
                    post(
                        "$base/api/v1/push",
                        cluster.tenant,
                        PrometheusRemoteWrite.body(series),
                        "application/x-protobuf",
                        mapOf("Content-Encoding" to "snappy", "X-Prometheus-Remote-Write-Version" to "0.1.0"),
                    )
                }
            }
            post("$base/ingester/flush?wait=true", null, ByteArray(0), "text/plain")
            clusters.groupBy { it.tenant }.forEach { (tenant, ofTenant) ->
                val wanted = if (old in ofTenant) recent + oldTimes else recent
                awaitUntil("every sample of $tenant in a shipped block", { mimir.logs.takeLast(LOG_TAIL) }) {
                    post("$base/ingester/flush?wait=true", null, ByteArray(0), "text/plain")
                    val ranges = ranges("${Constants.Observability.METRICS_ROOT}/$tenant/", "minTime", "maxTime")
                    wanted.all { ts -> ranges.any { ts in it } }
                }
            }
        } finally {
            stop(mimir)
        }
    }

    private fun seedLogs(
        clusters: List<Cluster>,
        old: Cluster,
        seeded: Seeded,
        now: Instant,
    ) {
        val loki =
            start(
                Writer(
                    LokiManifestBuilder.IMAGE,
                    "/etc/loki/loki.yaml",
                    CompactorTaskDefinition.resource(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE),
                    s3Flags("common.storage.s3") + "-common.storage.s3.force-path-style=true",
                    "/loki",
                    Constants.K8s.LOKI_HTTP_PORT,
                ),
            )
        try {
            val base = ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)
            clusters.forEach { cluster ->
                // The old line first: Loki takes a stream's lines in time order.
                val times = (if (cluster == old) listOf(seeded.oldAt) else emptyList()) + listOf(now.minusSeconds(60), now.minusSeconds(30))
                times.forEachIndexed { line, at ->
                    val attributes = labels(cluster).entries.joinToString(",") { (k, v) -> """{"key":"$k","value":{"stringValue":"$v"}}""" }
                    val body =
                        """
                        {"resourceLogs":[{"resource":{"attributes":[$attributes,{"key":"host.name","value":{"stringValue":"db0"}}]},
                         "scopeLogs":[{"logRecords":[{"timeUnixNano":"${at.toEpochMilli() * NANOS_PER_MILLI}",
                           "attributes":[{"key":"probe_id","value":{"stringValue":"${seeded.probe.getValue(cluster.name)}"}}],
                           "body":{"stringValue":"line $line of ${cluster.name}"}}]}]}]}
                        """.trimIndent()
                    post("$base/otlp/v1/logs", cluster.tenant, body.toByteArray(), "application/json")
                }
            }
            post("$base/ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false", null, ByteArray(0), "text/plain")
        } finally {
            stop(loki)
        }
        awaitUntil("Loki's index in the store", { "keys: ${keys("${Constants.Observability.LOGS_ROOT}/")}" }) {
            keys("${Constants.Observability.LOGS_ROOT}/index/").isNotEmpty()
        }
    }

    private fun seedTraces(
        clusters: List<Cluster>,
        old: Cluster,
        seeded: Seeded,
        now: Instant,
    ) {
        val config =
            YamlDocument.patch(
                CompactorTaskDefinition.resource(TempoManifestBuilder::class.java, "tempo.yaml"),
                listOf(
                    YamlPatch.set("storage.trace.s3.endpoint", minio.networkEndpoint),
                    YamlPatch.set("storage.trace.s3.insecure", "true"),
                    YamlPatch.set("storage.trace.s3.forcepathstyle", "true"),
                    YamlPatch.set("storage.trace.s3.access_key", NetworkMinio.USER),
                    YamlPatch.set("storage.trace.s3.secret_key", NetworkMinio.PASSWORD),
                    // As when the old span was new: its block keeps its own time.
                    YamlPatch.set("live_store.wal.ingestion_time_range_slack", "87600h"),
                    YamlPatch.set("storage.trace.wal.ingestion_time_range_slack", "87600h"),
                ),
            )
        val tempo =
            start(
                Writer(
                    TempoManifestBuilder.IMAGE,
                    "/etc/tempo/tempo.yaml",
                    config,
                    emptyList(),
                    "/var/tempo",
                    Constants.K8s.TEMPO_PORT,
                    listOf(ObservabilityBackends.TEMPO_OTLP_HTTP_PORT),
                ),
            )
        try {
            val otlp = ObservabilityBackends.baseUrl(tempo, ObservabilityBackends.TEMPO_OTLP_HTTP_PORT)
            val spans = clusters.map { Triple(it, seeded.traceIds.getValue(it.name), now) } + Triple(old, seeded.oldTraceId, seeded.oldAt)
            spans.forEach { (cluster, traceId, at) ->
                val start = at.toEpochMilli() * NANOS_PER_MILLI
                val attributes = labels(cluster).entries.joinToString(",") { (k, v) -> """{"key":"$k","value":{"stringValue":"$v"}}""" }
                val body =
                    """
                    {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"query-export-it"}},$attributes]},
                    "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
                    "startTimeUnixNano":"$start","endTimeUnixNano":"${start + SPAN_NANOS}"}]}]}]}
                    """.trimIndent()
                post("$otlp/v1/traces", cluster.tenant, body.toByteArray(), "application/json")
            }
            clusters.groupBy { it.tenant }.forEach { (tenant, ofTenant) ->
                val wanted = spans.filter { it.first in ofTenant }.map { it.third.toEpochMilli() }
                awaitUntil("Tempo blocks of $tenant holding every span's time", { tempo.logs.takeLast(LOG_TAIL) }) {
                    val ranges = ranges("${Constants.Observability.TRACES_ROOT}/$tenant/", "startTime", "endTime")
                    wanted.all { at -> ranges.any { at in it } }
                }
            }
        } finally {
            stop(tempo)
        }
    }

    /** Runs the account compactor's Mimir container until every tenant has a bucket index, as the real store does. */
    private fun buildBucketIndexes(tenants: List<String>) {
        val definition = CompactorTaskDefinition(bucket, NetworkMinio.REGION, "arn:aws:iam::1:role/task", "arn:aws:iam::1:role/exec")
        val container = definition.containers().single { it.name() == CompactorTaskDefinition.MIMIR_CONTAINER }
        val compactor =
            GenericContainer(container.image())
                .withNetwork(minio.network)
                .withCreateContainerCmdModifier { it.withUser("root") }
                .withCopyToContainer(
                    Transferable.of(CompactorTaskDefinition.configFiles().getValue(MimirManifestBuilder.CONFIG_FILE)),
                    "/config/${MimirManifestBuilder.CONFIG_FILE}",
                ).withEnv(container.environment().associate { it.name() to it.value() })
                .withTmpFs(mapOf("/data" to "rw"))
                .withCommand(
                    *(container.command() + s3Flags("blocks-storage.s3") + "-blocks-storage.s3.bucket-lookup-type=path").toTypedArray(),
                ).withExposedPorts(Constants.K8s.MIMIR_HTTP_PORT)
                .waitingFor(Wait.forHttp("/ready").forPort(Constants.K8s.MIMIR_HTTP_PORT).withStartupTimeout(STARTUP))
                .apply { start() }
        try {
            tenants.forEach { tenant ->
                awaitUntil("the bucket index of $tenant", { compactor.logs.takeLast(LOG_TAIL) }) {
                    "${Constants.Observability.METRICS_ROOT}/$tenant/bucket-index.json.gz" in
                        keys("${Constants.Observability.METRICS_ROOT}/$tenant/")
                }
            }
        } finally {
            stop(compactor)
        }
    }

    private fun stop(container: GenericContainer<*>) {
        runCatching { docker.stopContainerCmd(container.containerId).withTimeout(STOP_TIMEOUT_SECONDS).exec() }
        container.stop()
    }

    private fun post(
        url: String,
        tenant: String?,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val request =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", contentType)
                .apply { tenant?.let { header(Constants.Observability.TENANT_HEADER, it) } }
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        val response = ObservabilityBackends.send(request)
        assertThat(response.statusCode()).describedAs("POST $url: ${response.body()}").isBetween(200, 299)
    }

    private fun keys(prefix: String): List<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }

    /** The time range, in milliseconds, of each block's `meta.json` under [prefix], from its [min] and [max] fields. */
    private fun ranges(
        prefix: String,
        min: String,
        max: String,
    ): List<LongRange> =
        keys(prefix).filter { it.endsWith("/meta.json") }.map { key ->
            val meta =
                Json
                    .parseToJsonElement(
                        s3
                            .getObject(
                                GetObjectRequest
                                    .builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .build(),
                                ResponseTransformer.toBytes(),
                            ).asUtf8String(),
                    ).jsonObject

            fun millis(field: String): Long =
                meta.getValue(field).jsonPrimitive.let { value ->
                    value.content.toLongOrNull() ?: Instant.parse(value.content).toEpochMilli()
                }
            // Tempo writes whole seconds.
            (millis(min) - MILLIS_PER_SECOND)..(millis(max) + MILLIS_PER_SECOND)
        }

    private fun awaitUntil(
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WRITE_WAIT.toNanos()
        while (!done() && System.nanoTime() < deadline) Thread.sleep(POLL.toMillis())
        assertThat(done()).describedAs { "$what within $WRITE_WAIT\n${detail()}" }.isTrue()
    }
}
