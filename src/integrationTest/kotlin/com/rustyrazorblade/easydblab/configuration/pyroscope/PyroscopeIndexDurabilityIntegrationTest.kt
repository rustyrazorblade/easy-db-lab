package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.YamlTestSupport.scalarAt
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ObservabilityStore
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Runs the Pyroscope server at the image the cluster deploys, with the configuration the cluster
 * renders, against S3 (LocalStack). It proves the rendered configuration is accepted by the real
 * binary (its parser rejects unknown keys), that a profile written with the cluster's tenant is
 * stored under `pyroscope/` and is visible only to that tenant, and that the v2
 * metastore index — the only record of where the blocks are — survives a restart on the same data
 * volume, so the profile is still returned afterwards. It also proves the rendered per-profile
 * sample limit is the one the binary enforces: a profile over Pyroscope's default of 16,000 samples
 * is stored, and one sample over the rendered limit is refused.
 *
 * The data directory is a Docker volume, standing in for the control node's hostPath; a restarted
 * server reuses its volume. The only edit to the rendered configuration points the S3
 * client at LocalStack.
 */
class PyroscopeIndexDurabilityIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val SERVICE = "it-profiled"
        val WAIT: Duration = Duration.ofMinutes(2)
        val POLL: Duration = Duration.ofSeconds(2)

        /** Past the ~5.5s a tokenless segment writer refuses pushes after start. */
        val AFTER_START: Duration = Duration.ofSeconds(10)
        val PUSH_PERIOD: Duration = Duration.ofMillis(200)

        /** Bounds one push, so a server that accepts the connection and then hangs fails the test. */
        val PUSH_TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * Pyroscope 2.3.1 opens its HTTP port about 20ms before the segment writer reads its tokens
         * file, and no setting delays the port, so a push in those first milliseconds is refused
         * even with the tokens kept. Without the file the refusals last about 5.5s.
         */
        val START_RACE: Duration = Duration.ofMillis(100)

        /** Pyroscope's default `max_profile_stacktrace_samples`; edbl-ab1172 sent 18,995 and lost them. */
        const val DEFAULT_SAMPLE_LIMIT = 16_000
        const val STACK_DEPTH = 20
        const val FRAMES_PER_LEVEL = 4
        const val RENDER_BODY_EXCERPT = 500
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "pyroscope-durability-${UUID.randomUUID().toString().take(8)}"
    private val dataVolume = newVolumeName()
    private val volumes = mutableListOf(dataVolume)
    private val state =
        ClusterState(
            name = "pyroscope-it",
            s3Bucket = bucket,
            dataBucket = "easy-db-lab-data-should-not-be-used",
            initConfig = InitConfig(region = SharedLocalStack.region(), tenant = TENANT),
        )
    private val http = HttpClient.newHttpClient()
    private val containers = mutableListOf<GenericContainer<*>>()

    /** The status and the start of the body of the last render query, for a failed wait to show. */
    private var lastRender = "no render query sent"

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @AfterEach
    fun cleanUp() {
        containers.forEach { it.stop() }
        volumes.forEach { volume -> runCatching { docker.removeVolumeCmd(volume).exec() } }
    }

    private fun newVolumeName() = "pyroscope-data-${UUID.randomUUID().toString().take(8)}"

    /** The cluster's rendered config.yaml, with the S3 client pointed at LocalStack. */
    private fun testConfig(): String =
        ObservabilityBackends.pyroscopeConfig(
            PyroscopeManifestBuilder(getKoin().get()).buildServerConfigMap().data.getValue("config.yaml"),
        )

    /** Starts a Pyroscope server with [config] on the data volume ([ObservabilityBackends.startPyroscope]). */
    private fun startPyroscope(config: String): GenericContainer<*> =
        ObservabilityBackends.startPyroscope(config, dataVolume).also { containers.add(it) }

    private fun baseUrl(pyroscope: GenericContainer<*>) =
        "http://${pyroscope.host}:${pyroscope.getMappedPort(PyroscopeManifestBuilder.SERVER_PORT)}"

    private fun ingest(
        pyroscope: GenericContainer<*>,
        service: String = SERVICE,
    ) {
        val response = sendFolded(pyroscope, service, "main;work;compute 100\nmain;work;io 50\n")
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
    }

    /** Sends [folded] stacks for [service] in the cluster's tenant and returns the server's answer. */
    private fun sendFolded(
        pyroscope: GenericContainer<*>,
        service: String,
        folded: String,
    ): HttpResponse<String> {
        val name = URLEncoder.encode("$service{cluster=${state.clusterLabelName()}}", Charsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder(URI("${baseUrl(pyroscope)}/ingest?name=$name&format=folded"))
                .header(Constants.Observability.TENANT_HEADER, TENANT)
                .timeout(PUSH_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(folded))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Pushes a small profile every [PUSH_PERIOD] for [AFTER_START] from the moment [pyroscope]'s
     * port opens, and returns `+elapsed status: body` of each push it refused after [START_RACE]. A
     * push that cannot connect yet is not counted: the agent retries those.
     */
    private fun refusedPushesAfterStart(pyroscope: GenericContainer<*>): List<String> {
        val refused = mutableListOf<String>()
        val start = System.nanoTime()
        val deadline = start + AFTER_START.toNanos()
        while (System.nanoTime() < deadline) {
            runCatching { sendFolded(pyroscope, "it-after-restart", "main;work;compute 1\n") }
                .getOrNull()
                ?.takeIf { it.statusCode() != 200 }
                ?.let {
                    val at = Duration.ofNanos(System.nanoTime() - start)
                    if (at > START_RACE) {
                        refused.add("+${at.toMillis()}ms ${it.statusCode()}: ${it.body().take(RENDER_BODY_EXCERPT)}")
                    }
                }
            Thread.sleep(PUSH_PERIOD.toMillis())
        }
        return refused
    }

    /**
     * [count] distinct 20-frame stacks, one sample each. Pyroscope stores one sample per distinct
     * stack, so the profile holds exactly [count] samples.
     */
    private fun distinctStacks(count: Int): String =
        buildString {
            repeat(count) { index ->
                append("main")
                var rest = index
                repeat(STACK_DEPTH) {
                    append(";frame_").append(rest % FRAMES_PER_LEVEL)
                    rest /= FRAMES_PER_LEVEL
                }
                append(" 1\n")
            }
        }

    /** The server's `pyroscope_discarded_samples_total` for the cluster's tenant, summed over reasons. */
    private fun discardedProfiles(pyroscope: GenericContainer<*>): Double {
        val request = HttpRequest.newBuilder(URI("${baseUrl(pyroscope)}/metrics")).GET().build()
        return http
            .send(request, HttpResponse.BodyHandlers.ofString())
            .body()
            .lineSequence()
            .filter { it.startsWith("pyroscope_discarded_samples_total{") && it.contains("tenant=\"$TENANT\"") }
            .sumOf { it.substringAfterLast(' ').toDouble() }
    }

    /** Total CPU samples [tenant] sees for [service] over the last hour. */
    private fun cpuTicks(
        pyroscope: GenericContainer<*>,
        tenant: String,
        service: String = SERVICE,
    ): Long {
        val query = URLEncoder.encode("process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$service\"}", Charsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder(URI("${baseUrl(pyroscope)}/pyroscope/render?query=$query&from=now-1h&until=now&format=json"))
                .header(Constants.Observability.TENANT_HEADER, tenant)
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        lastRender = "status ${response.statusCode()}, body: ${body.take(RENDER_BODY_EXCERPT)}"
        return Regex("\"numTicks\":(\\d+)")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toLong() ?: 0
    }

    private fun awaitTicks(
        pyroscope: GenericContainer<*>,
        service: String = SERVICE,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var ticks = cpuTicks(pyroscope, TENANT, service)
        while (ticks == 0L && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            ticks = cpuTicks(pyroscope, TENANT, service)
        }
        // A render that errors reads as zero ticks, so the last answer is part of the failure.
        assertThat(ticks)
            .describedAs("CPU samples tenant $TENANT sees for $service; last render: $lastRender")
            .isPositive()
    }

    private fun keysUnder(prefix: String): List<String> =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }

    @Test
    fun `a tenant's profiles land in the account bucket and stay queryable across a restart`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(dataVolume).exec()
        val config = testConfig()

        val first = startPyroscope(config)
        ingest(first)
        awaitTicks(first)
        assertThat(cpuTicks(first, "other")).describedAs("another tenant sees nothing").isZero()

        val profilesPrefix = ObservabilityStore.from(state).profilesPrefix() + "/"
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (keysUnder(profilesPrefix).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(keysUnder(profilesPrefix)).isNotEmpty()
        assertThat(keysUnder("")).allMatch { it.startsWith(profilesPrefix) }

        first.dockerClient
            .stopContainerCmd(first.containerId)
            .withTimeout(30)
            .exec()
        // A Java agent with queued profiles pushes as soon as the restarted server's port opens, before
        // /ready. A segment writer that rejoins its ring without its tokens refuses every push for
        // about 5.5s, and the agent drops each refused one, so none after the first [START_RACE] may be
        // refused.
        val restartedAt = System.nanoTime()
        val second = ObservabilityBackends.startPyroscopeListening(config, dataVolume).also { containers.add(it) }
        val refused = refusedPushesAfterStart(second)
        assertThat(refused)
            .describedAs { "pushes refused after a restart (time after port open, status: body); server log:\n${second.logs}" }
            .isEmpty()
        // A restart over existing data must still pass /ready inside the startup-probe budget.
        ObservabilityBackends.awaitPyroscopeReady(second, restartedAt)
        awaitTicks(second)
    }

    @Test
    fun `a profile over Pyroscope's default sample limit is stored, and one over the rendered limit is refused`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(dataVolume).exec()
        val config = testConfig()
        val renderedLimit = requireNotNull(scalarAt(config, "limits", "max_profile_stacktrace_samples")).toInt()
        val pyroscope = startPyroscope(config)

        val large = sendFolded(pyroscope, "it-large", distinctStacks(DEFAULT_SAMPLE_LIMIT + 4_000))
        assertThat(large.statusCode()).describedAs(large.body()).isEqualTo(200)
        assertThat(discardedProfiles(pyroscope)).describedAs("profiles the server discarded").isZero()
        awaitTicks(pyroscope, "it-large")

        val over = sendFolded(pyroscope, "it-over", distinctStacks(renderedLimit + 1))
        assertThat(over.statusCode()).isEqualTo(400)
        assertThat(over.body()).contains("max_profile_stacktrace_samples", "limit: $renderedLimit")
        // The refusal must show in the same counter, or the zero above proves nothing.
        assertThat(discardedProfiles(pyroscope)).describedAs("profiles the server discarded").isEqualTo(1.0)
    }
}
