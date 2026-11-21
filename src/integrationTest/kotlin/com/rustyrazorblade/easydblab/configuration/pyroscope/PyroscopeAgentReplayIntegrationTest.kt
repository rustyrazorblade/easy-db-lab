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
 *
 * Empty windows alone cannot show one lost snapshot, so the test also reads the agent's log and
 * fails on any upload it dropped, apart from at most one push refused in the restarted server's
 * start race.
 *
 * The agent reaches the server through the pod's ingest front (`ingest-front.cfg`), run in the
 * server's network namespace as in the pod. The shutdown refusal the front turns into a 503 is
 * proven by `PyroscopeShutdownRefusalIntegrationTest`, not here: on LocalStack the stopping server
 * holds a push past the agent's 10-second timeout instead of refusing it, so this test sees only
 * timeouts, which the agent retries.
 */
class PyroscopeAgentReplayIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val SERVICE = "agent-replay"

        /** The agent takes and uploads one snapshot every 10 seconds; each is checked on its own. */
        val SNAPSHOT: Duration = Duration.ofSeconds(10)
        val OUTAGE: Duration = Duration.ofSeconds(150)
        val WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(5)

        val PYROSCOPE_HOST = "${Constants.K8s.PYROSCOPE_APP_LABEL}.default.svc.cluster.local"
    }

    private val s3 = SharedLocalStack.s3Client()
    private val docker = DockerClientFactory.instance().client()
    private val bucket = "pyroscope-agent-replay-${UUID.randomUUID().toString().take(8)}"
    private val volume = "pyroscope-replay-${UUID.randomUUID().toString().take(8)}"
    private val network: Network = Network.newNetwork()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val fronts = mutableListOf<String>()
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
        fronts.forEach { runCatching { docker.removeContainerCmd(it).withForce(true).exec() } }
        containers.forEach { it.stop() }
        runCatching { docker.removeVolumeCmd(volume).exec() }
        network.close()
    }

    /** Starts the server and the ingest front in its network namespace, then gives them the Service name. */
    private fun startPyroscope(): GenericContainer<*> {
        val configMap = PyroscopeManifestBuilder(getKoin().get()).buildServerConfigMap().data
        val rendered = configMap.getValue("config.yaml")
        val pyroscope = ObservabilityBackends.startPyroscope(ObservabilityBackends.pyroscopeConfig(rendered), volume)
        containers.add(pyroscope)
        fronts.add(ObservabilityBackends.startPyroscopeFront(pyroscope, configMap.getValue(PyroscopeManifestBuilder.FRONT_CONFIG_KEY)))
        ObservabilityBackends.attachAsService(pyroscope, network.id, PYROSCOPE_HOST)
        return pyroscope
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
                // The Service's port 4040 targets the front's port; Docker has no such mapping.
                "-Dpyroscope.server.address=http://$PYROSCOPE_HOST:${Constants.PyroscopeServer.FRONT_PORT}",
                "-Dpyroscope.format=jfr",
                "-Dpyroscope.profiler.event=itimer",
                "-Dpyroscope.tenant.id=$TENANT",
            ) + Constants.PyroscopeJavaAgent.OUTAGE_FLAGS + "$APP_DIR/Busy.java"
        return GenericContainer(PyroscopeAgentSupport.JDK_IMAGE)
            .withNetwork(network)
            .withCopyToContainer(MountableFile.forHostPath(PyroscopeAgentSupport.agentJar().toPath()), "$APP_DIR/pyroscope.jar")
            .withCopyToContainer(Transferable.of(PyroscopeAgentSupport.BUSY_SOURCE), "$APP_DIR/Busy.java")
            .withCommand(*command.toTypedArray())
            .waitingFor(Wait.forLogMessage(PyroscopeAgentSupport.AGENT_STARTED, 1).withStartupTimeout(WAIT))
            .also {
                it.start()
                containers.add(it)
            }
    }

    /** The start of each snapshot window in [windows] that holds no CPU samples. */
    private fun emptyWindows(
        pyroscope: GenericContainer<*>,
        windows: List<Long>,
    ): List<Long> = windows.filter { PyroscopeAgentSupport.cpuTicks(pyroscope, TENANT, SERVICE, it, it + SNAPSHOT.seconds) == 0L }

    @Test
    fun `a Pyroscope restart loses no Java-agent profile`() {
        SharedLocalStack.createBucketIfMissing(s3, bucket)
        Testcontainers.exposeHostPorts(SharedLocalStack.hostPort())
        docker.createVolumeCmd().withName(volume).exec()
        val first = startPyroscope()
        val jvm = startProfiledJvm()
        awaitUntil { PyroscopeAgentSupport.cpuTicks(first, TENANT, SERVICE, nowSecond() - WAIT.seconds, nowSecond()) > 0 }

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
        // One dropped snapshot leaves no window empty, since its neighbours overlap both windows
        // it touches, so the agent's own log is what shows a drop. The restarted server opens its
        // port about 20ms before it reads its ring tokens, and a push in that instant is refused
        // with an empty ring: at most that one drop is allowed (the race the design states).
        // The agent logs every failed upload with the prefix the check below reads, a connection
        // error as well as a refused status. A 150s outage fails many uploads, so a log without
        // the prefix means the agent's message changed and the check could no longer see a drop.
        assertThat(jvm.logs.lines().count { UPLOAD_ERROR in it })
            .describedAs("upload errors the agent logged in the outage; JVM log:\n${jvm.logs}")
            .isPositive()
        val dropped = PyroscopeAgentSupport.droppedUploads(jvm.logs)
        val (startRace, other) = dropped.partition { EMPTY_RING in it.lowercase() }
        assertThat(other).describedAs("uploads the agent dropped; JVM log:\n${jvm.logs}").isEmpty()
        assertThat(startRace).describedAs("uploads refused in the start race; JVM log:\n${jvm.logs}").hasSizeLessThanOrEqualTo(1)
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

private const val MILLIS_PER_SECOND = 1000L
private const val EMPTY_RING = PyroscopeAgentSupport.EMPTY_RING
private const val UPLOAD_ERROR = PyroscopeAgentSupport.UPLOAD_ERROR
private const val APP_DIR = PyroscopeAgentSupport.APP_DIR
