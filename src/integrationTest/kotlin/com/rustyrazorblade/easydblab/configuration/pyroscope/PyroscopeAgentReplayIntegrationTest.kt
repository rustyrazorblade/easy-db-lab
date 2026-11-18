package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
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
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID

/**
 * Proves that a Pyroscope restart loses no Java-agent profile (issue #991): a JVM running the
 * pinned Pyroscope Java agent, with the options the tool starts every agent with
 * ([Constants.PyroscopeJavaAgent.OUTAGE_FLAGS]), keeps profiling while the server is down and
 * uploads every snapshot of the gap once a new server is ready on the same data volume.
 *
 * The [OUTAGE] is chosen to fail the agent's defaults: they keep 8 snapshots (80 seconds) and give
 * up on one after 8 tries, about two minutes, so a 150-second outage lost its first minute of
 * profiles before the fix. The server answers to its in-cluster Service name only once it is ready,
 * as in `CollectorReplayIntegrationTest`.
 */
class PyroscopeAgentReplayIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val SERVICE = "agent-replay"
        const val JDK_IMAGE = "eclipse-temurin:21-jdk"
        const val APP_DIR = "/app"
        const val AGENT_STARTED = ".*Profiling started.*"

        /** The agent takes and uploads one snapshot every 10 seconds; each is checked on its own. */
        val SNAPSHOT: Duration = Duration.ofSeconds(10)
        val OUTAGE: Duration = Duration.ofSeconds(150)
        val WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(5)
        val PYROSCOPE_HOST = "${Constants.K8s.PYROSCOPE_APP_LABEL}.default.svc.cluster.local"

        /** A JVM that keeps one CPU busy, so every snapshot holds CPU samples. */
        val BUSY_SOURCE =
            """
            public class Busy {
                public static void main(String[] args) {
                    long x = 0;
                    while (true) {
                        for (int i = 0; i < 1_000_000; i++) {
                            x += i * 31L ^ (x >>> 3);
                        }
                        if (x == 42) System.out.println(x);
                    }
                }
            }
            """.trimIndent()
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "pyroscope-agent-replay-${UUID.randomUUID().toString().take(8)}"
    private val volume = "pyroscope-replay-${UUID.randomUUID().toString().take(8)}"
    private val network: Network = Network.newNetwork()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val state =
        ClusterState(
            name = "pyroscope-agent-replay",
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
        containers.forEach { it.stop() }
        runCatching { docker.removeVolumeCmd(volume).exec() }
        network.close()
    }

    private fun startPyroscope(): GenericContainer<*> {
        val rendered = PyroscopeManifestBuilder(getKoin().get()).buildServerConfigMap().data.getValue("config.yaml")
        val pyroscope = ObservabilityBackends.startPyroscope(ObservabilityBackends.pyroscopeConfig(rendered), volume)
        containers.add(pyroscope)
        ObservabilityBackends.attachAsService(pyroscope, network.id, PYROSCOPE_HOST)
        return pyroscope
    }

    /**
     * The pinned agent jar, from the same release URL the AMI and EMR install it from. It is kept in
     * the machine's temp directory under its version, so only the first run on a machine downloads
     * it; the download goes to a temporary file and is renamed into place, so a cut-off download is
     * never used.
     */
    private fun agentJar(): File {
        val jar = File(System.getProperty("java.io.tmpdir"), "easy-db-lab-pyroscope-${Constants.PyroscopeJavaAgent.VERSION}.jar")
        if (jar.isFile) return jar
        val partial = File.createTempFile("pyroscope-agent", ".part", jar.parentFile)
        try {
            val response =
                http.send(
                    HttpRequest.newBuilder(URI(Constants.PyroscopeJavaAgent.DOWNLOAD_URL)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(partial.toPath()),
                )
            check(response.statusCode() == HTTP_OK) { "agent download answered ${response.statusCode()}" }
            Files.move(partial.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
        return jar
    }

    /**
     * Starts the busy JVM with the agent and the tool's outage options. It profiles with `itimer`
     * instead of the cluster's `cpu` event, because perf events are not available inside a Docker
     * container; the upload queue and retries, which this test is about, do not depend on the event.
     */
    private fun startProfiledJvm(): GenericContainer<*> {
        val command =
            listOf(
                "java",
                "-javaagent:$APP_DIR/pyroscope.jar",
                "-Dpyroscope.application.name=$SERVICE",
                "-Dpyroscope.server.address=http://$PYROSCOPE_HOST:${PyroscopeManifestBuilder.SERVER_PORT}",
                "-Dpyroscope.format=jfr",
                "-Dpyroscope.profiler.event=itimer",
                "-Dpyroscope.tenant.id=$TENANT",
            ) + Constants.PyroscopeJavaAgent.OUTAGE_FLAGS + "$APP_DIR/Busy.java"
        return GenericContainer(JDK_IMAGE)
            .withNetwork(network)
            .withCopyToContainer(MountableFile.forHostPath(agentJar().toPath()), "$APP_DIR/pyroscope.jar")
            .withCopyToContainer(Transferable.of(BUSY_SOURCE), "$APP_DIR/Busy.java")
            .withCommand(*command.toTypedArray())
            .waitingFor(Wait.forLogMessage(AGENT_STARTED, 1).withStartupTimeout(WAIT))
            .also {
                it.start()
                containers.add(it)
            }
    }

    /** CPU samples the tenant sees for the busy JVM between [fromSecond] and [untilSecond]. */
    private fun cpuTicks(
        pyroscope: GenericContainer<*>,
        fromSecond: Long,
        untilSecond: Long,
    ): Long {
        val query =
            URLEncoder.encode("process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"$SERVICE\"}", Charsets.UTF_8)
        val url =
            "${ObservabilityBackends.baseUrl(pyroscope, PyroscopeManifestBuilder.SERVER_PORT)}/pyroscope/render" +
                "?query=$query&from=$fromSecond&until=$untilSecond&format=json"
        val response = runCatching { ObservabilityBackends.get(url, TENANT) }.getOrNull()
        if (response?.statusCode() != HTTP_OK) return 0
        return Regex("\"numTicks\":(\\d+)")
            .find(response.body())
            ?.groupValues
            ?.get(1)
            ?.toLong() ?: 0
    }

    /** The start of each snapshot window in [windows] that holds no CPU samples. */
    private fun emptyWindows(
        pyroscope: GenericContainer<*>,
        windows: List<Long>,
    ): List<Long> = windows.filter { cpuTicks(pyroscope, it, it + SNAPSHOT.seconds) == 0L }

    @Test
    fun `a Pyroscope restart loses no Java-agent profile`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val first = startPyroscope()
        startProfiledJvm()
        awaitUntil { cpuTicks(first, nowSecond() - WAIT.seconds, nowSecond()) > 0 }

        val down = nowSecond()
        ObservabilityBackends.stopGracefully(first)
        containers.remove(first)
        Thread.sleep(maxOf(0L, (down + OUTAGE.seconds - nowSecond()) * MILLIS_PER_SECOND))
        val restarted = nowSecond()
        val second = startPyroscope()

        // Whole snapshot windows inside the outage: no edge window is half before it.
        val windows = (down + SNAPSHOT.seconds until restarted - SNAPSHOT.seconds step SNAPSHOT.seconds).toList()
        var missing = emptyWindows(second, windows)
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (missing.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
            missing = emptyWindows(second, missing)
        }
        assertThat(missing.map { it - down })
            .describedAs(
                "seconds into the ${OUTAGE.seconds}s outage of each 10s window with no profile " +
                    "(${windows.size} windows checked)",
            ).isEmpty()
    }

    private fun nowSecond(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(POLL.toMillis())
        }
        assertThat(condition()).describedAs("the agent's first profile reached Pyroscope").isTrue()
    }
}

private const val HTTP_OK = 200
private const val MILLIS_PER_SECOND = 1000L
