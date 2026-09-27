package com.rustyrazorblade.easydblab.configuration.compactor

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.PrometheusRemoteWrite
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.LogUtils
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.ecs.model.ContainerDefinition
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Runs the account compactor's task as ECS would, from the real [CompactorTaskDefinition], against
 * S3 (LocalStack), and proves what the compactor must keep true for the shared store.
 *
 * Scenarios:
 * - **Every compactor starts from its task definition.** Each non-config container runs its image
 *   with its command, environment and user, the configuration files mounted at `/config`, in one
 *   network namespace as in the Fargate task, and becomes ready.
 * - **Stored blocks are queryable.** Mimir A ships blocks for tenant `acme`; the compactor writes
 *   `acme`'s `bucket-index.json.gz`; Mimir B, on an empty data volume, answers A's samples from the
 *   store alone.
 * - **Compaction keeps every sample, log line and trace.** The compactor merges A's one-minute
 *   blocks, and Mimir B answers the same number of samples as A did before compaction. (Loki and
 *   Tempo are covered here only by their start; the live AWS QA covers their data.)
 * - **The account compactor runs with retention off.** The samples are four hours old, and every
 *   one is still answered after the compactor ran with the task definition's retention flags.
 *
 * The one edit to the configuration is the harness's: the S3 endpoint and credentials point at
 * LocalStack. The Mimir compactor of the second scenario adds one flag to the task definition's
 * arguments, `-compactor.first-level-compaction-wait-period=0`: the real task waits 25 minutes
 * after upload before it merges a one-minute block, and the test cannot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactorTaskIntegrationTest {
    private companion object {
        const val TENANT = "acme"
        const val METRIC = "edl_compactor_probe"
        const val CONFIG_DIR = "/config"
        const val READY_PATH = "/ready"
        const val LOG_TAIL = 4000
        const val SAMPLE_STEP_SECONDS = 60L
        const val TWO_HOURS_SECONDS = 7200L

        /** Samples span a whole two-hour range and 10 minutes past it, so the range is complete. */
        const val SAMPLE_COUNT = 130
        val STARTUP: Duration = Duration.ofMinutes(3)
        val COMPACTION: Duration = Duration.ofMinutes(8)
        val POLL: Duration = Duration.ofSeconds(3)
        val FIRST_LEVEL_WAIT_OFF = "-compactor.first-level-compaction-wait-period=0"
        val HTTP_PORT_FLAG = Regex("""-server\.http-listen-port=(\d+)""")
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "compactor-it-${UUID.randomUUID().toString().take(8)}"
    private val volumes = mutableListOf<String>()
    private val containers = mutableListOf<GenericContainer<*>>()

    /** Containers started in another container's network namespace, by ID. */
    private val joined = mutableListOf<String>()
    private lateinit var templateService: TemplateService

    private val definition by lazy {
        CompactorTaskDefinition(bucket, SharedLocalStack.region(), "arn:aws:iam::1:role/task", "arn:aws:iam::1:role/exec")
    }

    @BeforeAll
    fun prepare() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        val clusterStateManager =
            mock<ClusterStateManager>().also {
                whenever(it.load()).thenReturn(ClusterState(name = "compactor-it", versions = mutableMapOf()))
            }
        val user =
            User(
                region = SharedLocalStack.region(),
                email = "test@example.com",
                keyName = "",
                awsProfile = "",
                awsAccessKey = "",
                awsSecret = "",
            )
        templateService = TemplateService(clusterStateManager, user)
    }

    @AfterAll
    fun cleanUp() {
        removeJoined()
        containers.forEach { it.stop() }
        volumes.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
    }

    private fun volume(): String =
        "compactor-it-${UUID.randomUUID().toString().take(8)}".also {
            docker.createVolumeCmd().withName(it).exec()
            volumes += it
        }

    /** The task's configuration files, pointed at LocalStack at [s3Host]. */
    private fun configFiles(s3Host: String): Map<String, String> {
        val local = "host.testcontainers.internal:${SharedLocalStack.hostPort()}"
        return CompactorTaskDefinition.configFiles().mapValues { (file, content) ->
            when (file) {
                MimirManifestBuilder.CONFIG_FILE -> ObservabilityBackends.mimirConfig(content)
                LokiManifestBuilder.CONFIG_FILE -> ObservabilityBackends.lokiConfig(content)
                else -> ObservabilityBackends.tempoConfig(content)
            }.replace(local, s3Host)
        }
    }

    private fun container(name: String): ContainerDefinition = definition.containers().single { it.name() == name }

    /**
     * [container] as ECS runs it: its image, command, environment and user, and the configuration
     * files at `/config`.
     */
    private fun taskContainer(
        container: ContainerDefinition,
        files: Map<String, String>,
        command: List<String> = container.command(),
    ): GenericContainer<*> =
        GenericContainer(container.image())
            .withCommand(*command.toTypedArray())
            .withEnv(container.environment().associate { it.name() to it.value() })
            .withCreateContainerCmdModifier { cmd ->
                container.user()?.let { cmd.withUser(it) }
                cmd.hostConfig?.withBinds(taskVolumes(container))
            }.apply { files.forEach { (file, content) -> withCopyToContainer(Transferable.of(content), "$CONFIG_DIR/$file") } }

    /**
     * Starts [container] as ECS runs it, in [owner]'s network namespace, and returns its ID. It is
     * started with the Docker client: Testcontainers connects every container it starts to its
     * host-access network, which Docker refuses for a container sharing another's namespace.
     */
    private fun startInNamespace(
        container: ContainerDefinition,
        files: Map<String, String>,
        owner: GenericContainer<*>,
    ): String {
        RemoteDockerImage(DockerImageName.parse(container.image())).get()
        val id =
            docker
                .createContainerCmd(container.image())
                .withCmd(container.command())
                .withEnv(container.environment().map { "${it.name()}=${it.value()}" })
                .apply { container.user()?.let { withUser(it) } }
                .withLabels(
                    DockerClientFactory.DEFAULT_LABELS +
                        (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID),
                ).withHostConfig(
                    HostConfig
                        .newHostConfig()
                        .withNetworkMode("container:${owner.containerId}")
                        .withBinds(taskVolumes(container)),
                ).exec()
                .id
        joined += id
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            files.forEach { (file, content) -> Transferable.of(content).transferTo(out, "${CONFIG_DIR.removePrefix("/")}/$file") }
        }
        docker
            .copyArchiveToContainerCmd(id)
            .withTarInputStream(ByteArrayInputStream(tar.toByteArray()))
            .withRemotePath("/")
            .exec()
        docker.startContainerCmd(id).exec()
        return id
    }

    /** A fresh Docker volume at each writable task volume [container] mounts, as Fargate's ephemeral storage gives it. */
    private fun taskVolumes(container: ContainerDefinition): List<Bind> =
        container
            .mountPoints()
            .filterNot { it.containerPath() == CONFIG_DIR }
            .map { Bind(volume(), Volume(it.containerPath())) }

    private fun isRunning(id: String): Boolean =
        docker
            .inspectContainerCmd(id)
            .exec()
            .state.running == true

    private fun logs(id: String): String = LogUtils.getOutput(docker, id).takeLast(LOG_TAIL)

    /** The HTTP port [container] serves `/ready` on: its `-server.http-listen-port`, or its config file's. */
    private fun httpPort(container: ContainerDefinition): Int =
        container.command().firstNotNullOfOrNull {
            HTTP_PORT_FLAG
                .matchEntire(it)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        }
            ?: when (container.name()) {
                CompactorTaskDefinition.MIMIR_CONTAINER -> Constants.K8s.MIMIR_HTTP_PORT
                CompactorTaskDefinition.LOKI_CONTAINER -> Constants.K8s.LOKI_HTTP_PORT
                else -> Constants.K8s.TEMPO_PORT
            }

    private fun awaitReady(
        url: String,
        within: Duration,
    ): Int {
        val deadline = System.nanoTime() + within.toNanos()
        var status = -1
        while (status != 200 && System.nanoTime() < deadline) {
            status = runCatching { ObservabilityBackends.get(url, TENANT).statusCode() }.getOrDefault(-1)
            if (status != 200) Thread.sleep(POLL.toMillis())
        }
        return status
    }

    @Test
    fun `every compactor container starts from its task definition and becomes ready`() {
        val compactors = definition.containers().filterNot { it.name() == CompactorTaskDefinition.CONFIG_CONTAINER }
        val ports = compactors.associate { it.name() to httpPort(it) }
        // Stands in for the Fargate task's network namespace: every container shares its localhost.
        val task =
            GenericContainer(Constants.Compactor.CONFIG_IMAGE)
                .withCommand("sleep", "3600")
                .withExposedPorts(*ports.values.toTypedArray())
                // Nothing listens until the compactors join; readiness is checked on each port below.
                .waitingFor(
                    object : AbstractWaitStrategy() {
                        override fun waitUntilReady() = Unit
                    },
                ).apply { start() }
                .also { containers += it }
        val files = configFiles(SharedLocalStack.networkAddress())
        val running = compactors.associate { it.name() to startInNamespace(it, files, task) }

        ports.forEach { (name, port) ->
            val status = awaitReady("${ObservabilityBackends.baseUrl(task, port)}$READY_PATH", STARTUP)
            assertThat(status).describedAs("$name /ready on port $port\n${logs(running.getValue(name))}").isEqualTo(200)
        }
        assertThat(running).allSatisfy { name, id ->
            assertThat(isRunning(id)).describedAs("$name is still running\n${logs(id)}").isTrue()
        }
        removeJoined()
    }

    private fun removeJoined() {
        joined.forEach { runCatching { docker.removeContainerCmd(it).withForce(true).exec() } }
        joined.clear()
    }

    @Test
    fun `Mimir blocks compacted by the account compactor keep every sample, and a new Mimir reads them from the store`() {
        val rendered = MimirManifestBuilder(templateService).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        val clusterConfig = ObservabilityBackends.mimirConfig(rendered)
        val mimirA =
            ObservabilityBackends
                .startMimir(clusterConfig, volume(), bucket, Constants.Observability.METRICS_ROOT)
                .also { containers += it }

        // Four-hour-old samples across a complete two-hour range, then shipped as one-minute blocks.
        val start = (Instant.now().epochSecond - 2 * TWO_HOURS_SECONDS) / TWO_HOURS_SECONDS * TWO_HOURS_SECONDS
        write(mimirA, (0 until SAMPLE_COUNT).map { (start + it * SAMPLE_STEP_SECONDS) * 1000 })
        flush(mimirA)
        val shipped = awaitStable { blockMetas().size }
        val before = samples(mimirA)
        assertThat(before).describedAs("samples Mimir A answers before compaction").isEqualTo(SAMPLE_COUNT)

        val mimirCompactor = container(CompactorTaskDefinition.MIMIR_CONTAINER)
        val compactor =
            taskContainer(
                mimirCompactor,
                configFiles("host.testcontainers.internal:${SharedLocalStack.hostPort()}"),
                command = mimirCompactor.command() + FIRST_LEVEL_WAIT_OFF,
            ).apply { start() }.also { containers += it }

        awaitUntil(COMPACTION, "the bucket index and a merged block for $TENANT", {
            "${objectKeys().size} objects\n${compactor.logs.takeLast(LOG_TAIL)}"
        }) {
            objectKeys().contains("${tenantRoot()}bucket-index.json.gz") && blockMetas().any { compactionLevel(it) > 1 }
        }
        mimirA.stop()

        val mimirB =
            ObservabilityBackends
                .startMimir(clusterConfig, volume(), bucket, Constants.Observability.METRICS_ROOT)
                .also { containers += it }
        awaitUntil(
            STARTUP,
            "Mimir B answers $before samples from the store",
            { "answered ${samples(mimirB)}" },
        ) { samples(mimirB) == before }
        assertThat(samples(mimirB)).isEqualTo(before)
        assertThat(shipped).describedAs("one-minute blocks Mimir A shipped").isGreaterThan(1)
    }

    private fun tenantRoot() = "${Constants.Observability.METRICS_ROOT}/$TENANT/"

    private fun objectKeys(): Set<String> =
        s3
            .listObjectsV2Paginator(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(tenantRoot())
                    .build(),
            ).contents()
            .map { it.key() }
            .toSet()

    private fun blockMetas(): List<String> = objectKeys().filter { it.endsWith("/meta.json") }

    private fun compactionLevel(metaKey: String): Int =
        Json
            .parseToJsonElement(
                s3
                    .getObject(
                        GetObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(metaKey)
                            .build(),
                        ResponseTransformer.toBytes(),
                    ).asUtf8String(),
            ).jsonObject
            .getValue("compaction")
            .jsonObject
            .getValue("level")
            .jsonPrimitive.int

    private fun url(mimir: GenericContainer<*>) = ObservabilityBackends.baseUrl(mimir, Constants.K8s.MIMIR_HTTP_PORT)

    private fun write(
        mimir: GenericContainer<*>,
        timestampsMillis: List<Long>,
    ) {
        timestampsMillis.forEachIndexed { index, at ->
            val body =
                PrometheusRemoteWrite.body(
                    listOf(PrometheusRemoteWrite.Series(mapOf("__name__" to METRIC, "cluster" to "lab-a"), index.toDouble(), at)),
                )
            val response =
                ObservabilityBackends.send(
                    HttpRequest
                        .newBuilder(URI("${url(mimir)}/api/v1/push"))
                        .header("Content-Type", "application/x-protobuf")
                        .header("Content-Encoding", "snappy")
                        .header("X-Prometheus-Remote-Write-Version", "0.1.0")
                        .header(Constants.Observability.TENANT_HEADER, TENANT)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                )
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        }
    }

    private fun flush(mimir: GenericContainer<*>) {
        val response =
            ObservabilityBackends.send(
                HttpRequest
                    .newBuilder(URI("${url(mimir)}/ingester/flush?wait=true"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
            )
        assertThat(response.statusCode()).describedAs(response.body()).isIn(200, 204)
    }

    /** The number of the probe's samples in the last six hours; 0 while Mimir cannot answer. */
    private fun samples(mimir: GenericContainer<*>): Int {
        val query = URLEncoder.encode("count_over_time($METRIC[6h])", Charsets.UTF_8)
        val response = ObservabilityBackends.get("${url(mimir)}/prometheus/api/v1/query?query=$query", TENANT)
        if (response.statusCode() != 200) return 0
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("result")
            .jsonArray
            .sumOf {
                it.jsonObject
                    .getValue("value")
                    .jsonArray[1]
                    .jsonPrimitive.content
                    .toDouble()
                    .toInt()
            }
    }

    /** Polls [value] until it is non-zero and unchanged for three looks in a row. */
    private fun awaitStable(value: () -> Int): Int {
        val deadline = System.nanoTime() + STARTUP.toNanos()
        var last = -1
        var same = 0
        while (same < 3 && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            val now = value()
            same = if (now > 0 && now == last) same + 1 else 0
            last = now
        }
        assertThat(same).describedAs("a stable non-zero value, last $last").isEqualTo(3)
        return last
    }

    private fun awaitUntil(
        within: Duration,
        what: String,
        detail: () -> String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (!done() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(done()).describedAs { "$what within $within: ${detail()}" }.isTrue()
    }
}
