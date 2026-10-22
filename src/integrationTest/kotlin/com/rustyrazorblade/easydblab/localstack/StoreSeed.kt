package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorTaskDefinition
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * MinIO on a Docker network, reachable as `minio:9000` by the containers on that network and through a
 * mapped port by this JVM's S3 client.
 *
 * @property network the network the stack and the writers join.
 */
class NetworkMinio(
    val network: Network,
) : AutoCloseable {
    companion object {
        const val ALIAS = "minio"
        const val PORT = 9000
        const val USER = "minioadmin"
        const val PASSWORD = "minioadmin"
        const val REGION = "us-east-1"
        private const val IMAGE = "minio/minio:RELEASE.2024-12-18T13-15-44Z"
    }

    private val container: GenericContainer<*> =
        GenericContainer(IMAGE)
            .withNetwork(network)
            .withNetworkAliases(ALIAS)
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(PORT).forStatusCode(200))
            .apply { start() }

    /** The address the containers on [network] reach MinIO at. */
    val networkEndpoint: String get() = "$ALIAS:$PORT"

    /** An S3 client for this JVM, with path-style access and the root credentials. */
    fun s3Client(): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create("http://${container.host}:${container.getMappedPort(PORT)}"))
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(USER, PASSWORD)))
            .forcePathStyle(true)
            .build()

    /** Creates [bucket]. */
    fun createBucket(bucket: String) {
        s3Client().use { it.createBucket(CreateBucketRequest.builder().bucket(bucket).build()) }
    }

    override fun close() = container.stop()
}

/**
 * Fills a bucket with what clusters of several tenants leave in the shared store: Mimir blocks with a
 * bucket index, Loki chunks with their index, and Tempo blocks, one cluster per tenant.
 *
 * The writers are the cluster's own images and configuration files, with the S3 endpoint and the
 * MinIO keys given as flags (Mimir, Loki) or written into the endpoint line (Tempo, which has no flag
 * for it). The bucket index is written by the account compactor's own Mimir container, as in the
 * real store. Every writer is stopped before [seed] returns, so nothing writes to the bucket after.
 *
 * @property minio the store.
 * @property bucket the account bucket.
 */
class StoreSeed(
    private val minio: NetworkMinio,
    private val bucket: String,
) {
    private companion object {
        const val METRIC = "edl_local_probe"
        const val SAMPLES = 8
        const val SAMPLE_STEP_SECONDS = 30L
        const val LOG_LINES = 3
        const val STOP_TIMEOUT_SECONDS = 120
        const val LOG_TAIL = 4000
        val STARTUP: Duration = Duration.ofMinutes(3)
        val WRITE_WAIT: Duration = Duration.ofMinutes(6)
        val POLL: Duration = Duration.ofSeconds(3)
        const val SPAN_DURATION_NANOS = 1_000_000L
    }

    /**
     * What [seed] wrote.
     *
     * @property tenants the tenants written, each with one cluster, [cluster].
     * @property metric the metric every cluster wrote.
     * @property traceIds the one trace each tenant wrote, by tenant.
     * @property linesPerCluster the log lines each cluster wrote.
     */
    data class Seeded(
        val tenants: List<String>,
        val metric: String,
        val traceIds: Map<String, String>,
        val linesPerCluster: Int,
    ) {
        /** The cluster label of [tenant]'s cluster. */
        fun cluster(tenant: String): String = "c-$tenant"
    }

    private val docker = DockerClientFactory.instance().client()
    private val s3 = minio.s3Client()

    /** Writes every signal for each of [tenants], waits until each is in the bucket, and stops every writer. */
    fun seed(tenants: List<String>): Seeded {
        val seeded =
            Seeded(
                tenants = tenants,
                metric = METRIC,
                traceIds = tenants.associateWith { UUID.randomUUID().toString().replace("-", "") },
                linesPerCluster = LOG_LINES,
            )
        seedMetrics(seeded)
        seedLogs(seeded)
        seedTraces(seeded)
        buildBucketIndexes(tenants)
        return seeded
    }

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
     * One writer: a cluster backend's image, its configuration file and the flags that point it at MinIO.
     *
     * @property dataDir the writer's state directory, a hostPath on the control node; a tmpfs here.
     * @property readyPort the port that serves `/ready`.
     * @property extraPorts other ports the test reaches, such as Tempo's OTLP receiver.
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

    private fun seedMetrics(seeded: Seeded) {
        val mimir =
            start(
                Writer(
                    image = MimirManifestBuilder.IMAGE,
                    configFile = "/etc/mimir/mimir.yaml",
                    config = CompactorTaskDefinition.resource(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                    command = s3Flags("blocks-storage.s3") + "-blocks-storage.s3.bucket-lookup-type=path",
                    dataDir = "/data",
                    readyPort = Constants.K8s.MIMIR_HTTP_PORT,
                ),
            )
        try {
            val base = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)
            val first = Instant.now().minusSeconds(SAMPLES * SAMPLE_STEP_SECONDS).toEpochMilli()
            val written = (0 until SAMPLES).map { first + it * SAMPLE_STEP_SECONDS * 1000 }
            seeded.tenants.forEach { tenant ->
                written.forEachIndexed { index, at ->
                    val labels = mapOf("__name__" to seeded.metric, "cluster" to seeded.cluster(tenant))
                    post(
                        "$base/api/v1/push",
                        tenant,
                        PrometheusRemoteWrite.body(listOf(PrometheusRemoteWrite.Series(labels, index.toDouble(), at))),
                        "application/x-protobuf",
                        mapOf("Content-Encoding" to "snappy", "X-Prometheus-Remote-Write-Version" to "0.1.0"),
                    )
                }
            }
            post("$base/ingester/flush?wait=true", null, ByteArray(0), "text/plain")
            seeded.tenants.forEach { tenant ->
                awaitUntil("every sample of $tenant in a shipped block", { mimir.logs.takeLast(LOG_TAIL) }) {
                    val ranges = blockRanges("${Constants.Observability.METRICS_ROOT}/$tenant/")
                    written.all { ts -> ranges.any { ts in it } }
                }
            }
        } finally {
            stop(mimir)
        }
    }

    private fun seedLogs(seeded: Seeded) {
        val loki =
            start(
                Writer(
                    image = LokiManifestBuilder.IMAGE,
                    configFile = "/etc/loki/loki.yaml",
                    config = CompactorTaskDefinition.resource(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE),
                    command = s3Flags("common.storage.s3") + "-common.storage.s3.force-path-style=true",
                    dataDir = "/loki",
                    readyPort = Constants.K8s.LOKI_HTTP_PORT,
                ),
            )
        try {
            val base = ObservabilityBackends.baseUrl(loki, Constants.K8s.LOKI_HTTP_PORT)
            seeded.tenants.forEach { tenant ->
                repeat(seeded.linesPerCluster) { line ->
                    val nanos = (System.currentTimeMillis() - (seeded.linesPerCluster - line) * 1000L) * 1_000_000
                    val body =
                        """
                        {"resourceLogs":[{"resource":{"attributes":[
                          {"key":"cluster","value":{"stringValue":"${seeded.cluster(tenant)}"}},
                          {"key":"host.name","value":{"stringValue":"db0"}}]},
                         "scopeLogs":[{"logRecords":[{"timeUnixNano":"$nanos","body":{"stringValue":"line $line of $tenant"}}]}]}]}
                        """.trimIndent()
                    post("$base/otlp/v1/logs", tenant, body.toByteArray(), "application/json")
                }
            }
            post("$base/ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false", null, ByteArray(0), "text/plain")
        } finally {
            // As `down` does after the flush: the index head is uploaded when Loki stops.
            stop(loki)
        }
        awaitUntil("Loki's index in the store", { "keys: ${keys("${Constants.Observability.LOGS_ROOT}/")}" }) {
            keys("${Constants.Observability.LOGS_ROOT}/index/").isNotEmpty()
        }
    }

    private fun seedTraces(seeded: Seeded) {
        val clusterTempo = CompactorTaskDefinition.resource(TempoManifestBuilder::class.java, "tempo.yaml")
        val tempo =
            start(
                Writer(
                    image = TempoManifestBuilder.IMAGE,
                    configFile = "/etc/tempo/tempo.yaml",
                    config = tempoConfig(clusterTempo),
                    command = emptyList(),
                    dataDir = "/var/tempo",
                    readyPort = Constants.K8s.TEMPO_PORT,
                    extraPorts = listOf(ObservabilityBackends.TEMPO_OTLP_HTTP_PORT),
                ),
            )
        try {
            val otlp = ObservabilityBackends.baseUrl(tempo, ObservabilityBackends.TEMPO_OTLP_HTTP_PORT)
            seeded.traceIds.forEach { (tenant, traceId) ->
                val now = System.currentTimeMillis() * 1_000_000
                val body =
                    """
                    {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"local-stack-it"}},
                    {"key":"cluster","value":{"stringValue":"${seeded.cluster(tenant)}"}}]},
                    "scopeSpans":[{"spans":[{"traceId":"$traceId","spanId":"${traceId.take(16)}","name":"probe","kind":1,
                    "startTimeUnixNano":"$now","endTimeUnixNano":"${now + SPAN_DURATION_NANOS}"}]}]}]}
                    """.trimIndent()
                post("$otlp/v1/traces", tenant, body.toByteArray(), "application/json")
            }
            seeded.tenants.forEach { tenant ->
                awaitUntil("a Tempo block of $tenant in the store", { tempo.logs.takeLast(LOG_TAIL) }) {
                    keys("${Constants.Observability.TRACES_ROOT}/$tenant/").any { it.endsWith("/meta.json") }
                }
            }
        } finally {
            stop(tempo)
        }
    }

    /** Tempo's configuration with its S3 endpoint and keys pointed at MinIO; Tempo has no flag for them. */
    private fun tempoConfig(rendered: String): String {
        val endpointLine = "      endpoint: s3.\${AWS_REGION}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "tempo.yaml no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |      endpoint: ${minio.networkEndpoint}
            |      insecure: true
            |      forcepathstyle: true
            |      access_key: ${NetworkMinio.USER}
            |      secret_key: ${NetworkMinio.PASSWORD}
            """.trimMargin(),
        )
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

    /** The time range, in milliseconds, of each Mimir block under [prefix]; a block's maxTime is exclusive. */
    private fun blockRanges(prefix: String): List<LongRange> =
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
            meta.getValue("minTime").jsonPrimitive.long until meta.getValue("maxTime").jsonPrimitive.long
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
