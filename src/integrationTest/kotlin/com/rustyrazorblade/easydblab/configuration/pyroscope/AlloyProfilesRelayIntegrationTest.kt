package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.github.dockerjava.api.model.HostConfig
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.otel.ServiceAccountCa
import com.rustyrazorblade.easydblab.services.TemplateService
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.LogUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Proves the node-local profiles relay in the Alloy eBPF agent's rendered `config.alloy`: a JFR
 * chunk posted the way the db-node profiling reconciler posts it (`/ingest`, `format=jfr`, the
 * series name with its labels, the tenant header) to `127.0.0.1:<ALLOY_RELAY_PORT>` is forwarded
 * to Pyroscope and read back under that tenant with the same series name and labels.
 *
 * The sender joins Alloy's network namespace, as the reconciler shares the node's loopback with
 * the host-network Alloy pod. It is created with the Docker client, as the Mimir harness's memcached
 * is, because Docker refuses Testcontainers' host-access network on a joined namespace. Alloy runs the rendered configuration unedited; it gets a placeholder
 * service account so its Kubernetes discovery starts, and its eBPF profiler is unhealthy in a
 * container, which the relay does not depend on. Pyroscope runs the cluster's rendered,
 * multi-tenant configuration with its S3 client pointed at LocalStack, so the tenant is enforced.
 */
class AlloyProfilesRelayIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val SERVICE = "cassandra"
        const val HOSTNAME_LABEL = "db0"
        const val CLUSTER_LABEL = "relay-lab-0123"
        const val CHUNK = "/chunk.jfr"
        const val SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
        const val ALLOY_UI_PORT = 12345
        const val CURL_IMAGE = "curlimages/curl:8.11.1"
        const val SPIN_MILLIS = 1_500L
        const val EXECUTION_SAMPLE = "jdk.ExecutionSample"
        const val MIN_CPU_SAMPLES = 10
        const val JFR_ATTEMPTS = 5
        val ARRIVAL: Duration = Duration.ofMinutes(2)
        val POLL: Duration = Duration.ofSeconds(2)
        val SENDER_TIMEOUT: Duration = Duration.ofMinutes(1)
        const val EVIDENCE_LOG_LINES = 80
        val EVIDENCE_METRICS = Regex("segment_writer|store_metadata|dlq|metastore_index|distributor_received")
    }

    @TempDir
    lateinit var jfrDir: Path

    private val docker = DockerClientFactory.instance().client()
    private val s3 = SharedLocalStack.s3Client()
    private val bucket = "alloy-relay-${UUID.randomUUID().toString().take(8)}"
    private val http = HttpClient.newHttpClient()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val state =
        ClusterState(
            name = "relay-lab",
            s3Bucket = bucket,
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.reversed().forEach { it.stop() }
    }

    private fun <T : GenericContainer<*>> started(container: T): T =
        container.also {
            it.start()
            containers.add(it)
        }

    private fun bridgeAddress(container: GenericContainer<*>): String {
        val networks =
            docker
                .inspectContainerCmd(container.containerId)
                .exec()
                .networkSettings.networks
        return checkNotNull(networks["bridge"]?.ipAddress) { "no address on the default bridge: $networks" }
    }

    /** The cluster's rendered server config.yaml (multi-tenant), with the S3 client pointed at LocalStack. */
    private fun serverConfig(): String {
        val rendered = PyroscopeManifestBuilder(getKoin().get()).buildServerConfigMap().data.getValue("config.yaml")
        val endpointLine = "    endpoint: s3.${SharedLocalStack.region()}.amazonaws.com"
        check(rendered.contains(endpointLine)) { "rendered config no longer names the regional S3 endpoint" }
        return rendered.replace(
            endpointLine,
            """
            |    endpoint: host.testcontainers.internal:${SharedLocalStack.hostPort()}
            |    insecure: true
            |    force_path_style: true
            |    access_key_id: ${SharedLocalStack.accessKey()}
            |    secret_access_key: ${SharedLocalStack.secretKey()}
            """.trimMargin(),
        )
    }

    /**
     * A real JFR recording of this JVM spinning, holding at least [MIN_CPU_SAMPLES] CPU samples.
     * The sampler can miss a short spin on a busy runner, and a chunk with no samples is accepted
     * by the relay (200) but gives Pyroscope nothing to store, so the test would fail on an empty
     * series for a reason that has nothing to do with the relay. It records again until the chunk
     * has samples, and fails here, naming the cause, if it never does.
     */
    private fun recordJfr(): Path {
        val file = jfrDir.resolve("chunk.jfr")
        repeat(JFR_ATTEMPTS) {
            spinRecording(file)
            if (cpuSamples(file) >= MIN_CPU_SAMPLES) return file
        }
        error("no JFR recording of ${SPIN_MILLIS}ms held $MIN_CPU_SAMPLES CPU samples in $JFR_ATTEMPTS attempts")
    }

    private fun spinRecording(file: Path) {
        Recording().use { recording ->
            recording.enable(EXECUTION_SAMPLE).withPeriod(Duration.ofMillis(10))
            recording.start()
            val end = System.currentTimeMillis() + SPIN_MILLIS
            var sink = 0.0
            while (System.currentTimeMillis() < end) {
                sink += Math.sqrt(sink + 1)
            }
            recording.stop()
            recording.dump(file)
            check(sink > 0)
        }
    }

    private fun cpuSamples(file: Path): Int = RecordingFile.readAllEvents(file).count { it.eventType.name == EXECUTION_SAMPLE }

    private fun startAlloy(pyroscope: GenericContainer<*>): GenericContainer<*> {
        val config = PyroscopeManifestBuilder(getKoin().get()).buildEbpfConfigMap().data.getValue("config.alloy")
        return started(
            GenericContainer(PyroscopeManifestBuilder.ALLOY_IMAGE)
                .withCopyToContainer(Transferable.of(config), "/etc/alloy/config.alloy")
                .withCopyToContainer(Transferable.of("placeholder"), "$SA_DIR/token")
                .withCopyToContainer(Transferable.of(ServiceAccountCa.pem()), "$SA_DIR/ca.crt")
                .withEnv("KUBERNETES_SERVICE_HOST", "127.0.0.1")
                .withEnv("KUBERNETES_SERVICE_PORT", "1")
                .withEnv("HOSTNAME", HOSTNAME_LABEL)
                .withEnv("CLUSTER_NAME", CLUSTER_LABEL)
                .withEnv("PYROSCOPE_WRITE_URL", "http://${bridgeAddress(pyroscope)}:${PyroscopeManifestBuilder.SERVER_PORT}")
                .withEnv("TENANT", TENANT)
                .withCommand("run", "--server.http.listen-addr=0.0.0.0:$ALLOY_UI_PORT", "/etc/alloy/config.alloy")
                .withExposedPorts(ALLOY_UI_PORT)
                .waitingFor(Wait.forHttp("/-/ready").forPort(ALLOY_UI_PORT).withStartupTimeout(Duration.ofMinutes(2))),
        )
    }

    /** Posts [jfr] to the relay on Alloy's loopback, as the reconciler does, and returns the HTTP status. */
    private fun postThroughRelay(
        alloy: GenericContainer<*>,
        jfr: Path,
    ): String {
        val name = URLEncoder.encode("$SERVICE{hostname=$HOSTNAME_LABEL,cluster=$CLUSTER_LABEL}", Charsets.UTF_8)
        val until = Instant.now().epochSecond
        val url = "http://127.0.0.1:${Constants.K8s.ALLOY_RELAY_PORT}/ingest?name=$name&format=jfr&from=${until - 60}&until=$until"
        RemoteDockerImage(DockerImageName.parse(CURL_IMAGE)).get()
        val id =
            docker
                .createContainerCmd(CURL_IMAGE)
                .withCmd(
                    "--silent",
                    "--show-error",
                    "--output",
                    "/dev/null",
                    "--write-out",
                    "status=%{http_code}",
                    "--request",
                    "POST",
                    "--header",
                    "Content-Type: application/octet-stream",
                    "--header",
                    "${Constants.Observability.TENANT_HEADER}: $TENANT",
                    "--data-binary",
                    "@$CHUNK",
                    url,
                ).withLabels(
                    DockerClientFactory.DEFAULT_LABELS +
                        (DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID),
                ).withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:${alloy.containerId}"))
                .exec()
                .id
        try {
            val tar = ByteArrayOutputStream()
            TarArchiveOutputStream(tar).use { Transferable.of(Files.readAllBytes(jfr)).transferTo(it, CHUNK.removePrefix("/")) }
            docker
                .copyArchiveToContainerCmd(id)
                .withTarInputStream(ByteArrayInputStream(tar.toByteArray()))
                .withRemotePath("/")
                .exec()
            docker.startContainerCmd(id).exec()
            // curl exits non-zero when it cannot connect; the status it printed is the account either way.
            docker
                .waitContainerCmd(id)
                .start()
                .awaitStatusCode(SENDER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            val logs = LogUtils.getOutput(docker, id)
            return logs.lineSequence().lastOrNull { it.startsWith("status=") } ?: logs
        } finally {
            runCatching { docker.removeContainerCmd(id).withForce(true).exec() }
        }
    }

    /** The label sets of the CPU series Pyroscope holds for [SERVICE] under [tenant]. */
    private fun cpuSeries(
        pyroscope: GenericContainer<*>,
        tenant: String,
    ): String {
        val base = "http://${pyroscope.host}:${pyroscope.getMappedPort(PyroscopeManifestBuilder.SERVER_PORT)}"
        val body =
            """{"matchers":["{service_name=\"$SERVICE\",__profile_type__=\"process_cpu:cpu:nanoseconds:cpu:nanoseconds\"}"],""" +
                """"start":${Instant.now().minusSeconds(3600).toEpochMilli()},"end":${Instant.now().plusSeconds(60).toEpochMilli()}}"""
        val request =
            HttpRequest
                .newBuilder(URI("$base/querier.v1.QuerierService/Series"))
                .header(Constants.Observability.TENANT_HEADER, tenant)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    /**
     * What Pyroscope did with the chunk, for a failed assertion: a 200 from the relay means the
     * segment was written and its metadata either reached the metastore or went to the dead-letter
     * queue, so the segment writer's and metastore's counters and both logs say where it stopped.
     */
    private fun ingestionEvidence(
        pyroscope: GenericContainer<*>,
        alloy: GenericContainer<*>,
    ): String {
        val base = "http://${pyroscope.host}:${pyroscope.getMappedPort(PyroscopeManifestBuilder.SERVER_PORT)}"
        val metrics =
            runCatching {
                http
                    .send(HttpRequest.newBuilder(URI("$base/metrics")).build(), HttpResponse.BodyHandlers.ofString())
                    .body()
                    .lineSequence()
                    .filter { !it.startsWith("#") && !it.contains("_bucket{") && EVIDENCE_METRICS.containsMatchIn(it) }
                    .joinToString("\n")
            }.getOrElse { "metrics unreadable: $it" }
        return """
            |Pyroscope ingestion metrics:
            |$metrics
            |Pyroscope log (last $EVIDENCE_LOG_LINES lines):
            |${pyroscope.logs.lines().takeLast(EVIDENCE_LOG_LINES).joinToString("\n")}
            |Alloy log (last $EVIDENCE_LOG_LINES lines):
            |${alloy.logs.lines().takeLast(EVIDENCE_LOG_LINES).joinToString("\n")}
            """.trimMargin()
    }

    @Test
    fun `a JFR chunk posted to the relay lands in Pyroscope under the tenant with its series name and labels`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        val pyroscope =
            started(
                GenericContainer(PyroscopeManifestBuilder.SERVER_IMAGE)
                    .withCreateContainerCmdModifier { it.withUser("root") }
                    .withCopyToContainer(Transferable.of(serverConfig()), "/etc/pyroscope/config.yaml")
                    .withCommand("-config.file=/etc/pyroscope/config.yaml")
                    .withExposedPorts(PyroscopeManifestBuilder.SERVER_PORT)
                    .waitingFor(
                        Wait
                            .forHttp("/ready")
                            .forPort(PyroscopeManifestBuilder.SERVER_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(3)),
                    ),
            )
        val alloy = startAlloy(pyroscope)

        assertThat(postThroughRelay(alloy, recordJfr())).isEqualTo("status=200")

        val deadline = System.nanoTime() + ARRIVAL.toNanos()
        var series = cpuSeries(pyroscope, TENANT)
        while (!series.contains(CLUSTER_LABEL) && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            series = cpuSeries(pyroscope, TENANT)
        }
        assertThat(series)
            .describedAs { "CPU series tenant $TENANT sees for $SERVICE\n${ingestionEvidence(pyroscope, alloy)}" }
            .contains("\"value\":\"$CLUSTER_LABEL\"", "\"value\":\"$HOSTNAME_LABEL\"", "\"value\":\"$SERVICE\"")
        assertThat(cpuSeries(pyroscope, "other")).describedAs("another tenant sees nothing").doesNotContain(CLUSTER_LABEL)
    }
}
