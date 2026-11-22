package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.github.dockerjava.api.model.HostConfig
import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.ObservabilityBackends
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServiceDns
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockRenderer
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockSource
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.TemplateService
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.LogUtils
import org.testcontainers.utility.MountableFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Proves that the pod's ingest front turns the pushes Pyroscope refuses while it shuts down into
 * answers every client retries, and that the Java agent loses no profile across the stop.
 *
 * From SIGTERM until it exits, Pyroscope 2.3.1 (and 2.4.4) keeps its port open with its
 * distributor already stopped, and answers every push 422 "service is unavailable". Agent 2.3.0
 * retries only a 429 or a 5xx, so it dropped each of those snapshots. The front
 * (`ingest-front.cfg`, rendered from the server's ConfigMap, run in the server's network namespace
 * as in the pod) answers that one refusal 503 instead.
 *
 * The refusal window lasts from under a second to a few seconds, so a pusher starts a push of a
 * JFR profile with known CPU samples ([CpuSampleJfr]) every [PUSH_PERIOD], each on its own, from
 * before SIGTERM until the server is gone, and records the status of every answer. Pushes are not
 * sent one after another: the stopping server holds the push it is handling until it exits, and a
 * pusher waiting on it sent nothing in the window. SIGTERM is sent only once the pusher has had a
 * push stored, so it is known to be reaching the server when the window opens. Through the front
 * it must see a 503 refusal and no 422; straight to the
 * server it must see a 422, which shows the test reaches the window the front covers. That the
 * agent retries a 503 is the agent's documented behaviour, not something this test can force: its
 * uploads land in the window only by chance, so the agent's part is only that it dropped nothing
 * and every profile window, before, during and after the stop, holds data.
 *
 * The server keeps its blocks on the filesystem here, so its shutdown flushes to local disk and it
 * refuses pushes instead of holding them: on LocalStack the stopping server holds a push past the
 * agent's 10-second timeout, and no refusal ever reaches a client. Everything else is the
 * production config.
 */
class PyroscopeShutdownRefusalIntegrationTest : BaseKoinTest() {
    private companion object {
        const val TENANT = "acme"
        const val SERVICE = "shutdown-refusal"
        const val PUSHER_SERVICE = "shutdown-pusher"
        val PYROSCOPE_HOST = "${ServiceDns(TestScopes.EC2.namespace).host(Constants.K8s.PYROSCOPE_APP_LABEL)}.cluster.local"
        const val CURL_IMAGE = "curlimages/curl:8.11.1"
        const val PUSHER_SCRIPT = "/push-through-shutdown.sh"
        const val PUSHER_SCRIPT_RESOURCE = "push-through-shutdown.sh"
        const val PUSHER_BODY = "/profile.jfr"
        const val HTTP_REFUSED = 422
        const val HTTP_UNAVAILABLE = 503

        /** The test-only store block: the server's blocks on its data volume instead of in S3. */
        const val FILESYSTEM_STORE = "backend: filesystem\nfilesystem:\n  dir: /data/store\n"

        val UPLOAD_INTERVAL: Duration = Duration.ofSeconds(1)
        val PUSH_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * How often the pusher starts a push. Each runs on its own, so a push the stopping server
         * holds never delays the next, and the refusal window gets a push every period.
         */
        val PUSH_PERIOD: Duration = Duration.ofMillis(50)

        /** The pusher's own bound, past the server's grace and every push's timeout. */
        val PUSHER_LIMIT: Duration = Duration.ofMinutes(2)

        /** The pusher's line for its first stored push; SIGTERM is sent only after it. */
        const val READY_LINE = "READY"

        /** How long the pusher may take to have its first push stored. */
        val PUSHER_READY: Duration = Duration.ofMinutes(1)
        val READY_POLL: Duration = Duration.ofMillis(100)

        /** Uploads before and after the stop, so profiles on both sides can be checked. */
        val RUNNING: Duration = Duration.ofSeconds(20)
        val WAIT: Duration = Duration.ofMinutes(3)
        val POLL: Duration = Duration.ofSeconds(2)
        val WINDOW: Duration = Duration.ofSeconds(5)

        /** The SIGTERM grace a pod restart gives the server, as in `ObservabilityBackends.stopGracefully`. */
        val GRACE: Duration = Duration.ofSeconds(30)
    }

    private val docker = DockerClientFactory.instance().client()
    private val volume = "pyroscope-shutdown-${UUID.randomUUID().toString().take(8)}"
    private val network: Network = Network.newNetwork()
    private val containers = mutableListOf<GenericContainer<*>>()
    private val helpers = mutableListOf<String>()
    private val state =
        ClusterState(
            name = "pyroscope-shutdown-refusal",
            s3Bucket = "unused",
            initConfig = InitConfig(region = "us-west-2", tenant = TENANT),
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
        helpers.forEach { runCatching { docker.removeContainerCmd(it).withForce(true).exec() } }
        containers.forEach { it.stop() }
        runCatching { docker.removeVolumeCmd(volume).exec() }
        network.close()
    }

    /**
     * The server from the production ConfigMap with the filesystem store, under the Service name,
     * with its ingest front unless [withFront] is false.
     */
    private fun startPyroscope(withFront: Boolean = true): GenericContainer<*> {
        val builder =
            PyroscopeManifestBuilder(getKoin().get(), StoreBlockRenderer(StoreBlockSource { FILESYSTEM_STORE }))
        val configMap = builder.buildServerConfigMap(TestScopes.EC2).data
        val pyroscope = ObservabilityBackends.startPyroscope(configMap.getValue("config.yaml"), volume)
        containers.add(pyroscope)
        if (withFront) {
            helpers.add(ObservabilityBackends.startPyroscopeFront(pyroscope, configMap.getValue(PyroscopeManifestBuilder.FRONT_CONFIG_KEY)))
        }
        ObservabilityBackends.attachAsService(pyroscope, network.id, PYROSCOPE_HOST)
        return pyroscope
    }

    /** The busy JVM with the pinned agent and the tool's outage options, sending through the front. */
    private fun startProfiledJvm(): GenericContainer<*> {
        val appDir = PyroscopeAgentSupport.APP_DIR
        val command =
            listOf(
                "java",
                "-javaagent:$appDir/pyroscope.jar",
                "-Dpyroscope.application.name=$SERVICE",
                // The Service's port 4040 targets the front's port; Docker has no such mapping.
                "-Dpyroscope.server.address=http://$PYROSCOPE_HOST:${Constants.PyroscopeServer.FRONT_PORT}",
                "-Dpyroscope.format=jfr",
                "-Dpyroscope.profiler.event=itimer",
                "-Dpyroscope.tenant.id=$TENANT",
                "-Dpyroscope.upload.interval=${UPLOAD_INTERVAL.seconds}s",
            ) + Constants.PyroscopeJavaAgent.OUTAGE_FLAGS + "$appDir/Busy.java"
        return GenericContainer(PyroscopeAgentSupport.JDK_IMAGE)
            .withNetwork(network)
            .withCopyToContainer(MountableFile.forHostPath(PyroscopeAgentSupport.agentJar().toPath()), "$appDir/pyroscope.jar")
            .withCopyToContainer(Transferable.of(PyroscopeAgentSupport.BUSY_SOURCE), "$appDir/Busy.java")
            .withCommand(*command.toTypedArray())
            .waitingFor(Wait.forLogMessage(PyroscopeAgentSupport.AGENT_STARTED, 1).withStartupTimeout(WAIT))
            .also {
                it.start()
                containers.add(it)
            }
    }

    /** The answers a pusher saw: how many of each `<status> <refusal|other>`, and its whole output. */
    private data class Pushes(
        val counts: Map<String, Int>,
        val output: String,
    ) {
        fun count(
            status: Int,
            refusal: Boolean,
        ): Int = counts["$status ${if (refusal) "refusal" else "other"}"] ?: 0

        fun countStatus(status: Int): Int = counts.filterKeys { it.startsWith("$status ") }.values.sum()
    }

    /**
     * Starts the pusher on the test network, starting a push of a JFR profile to the Service name
     * on [port] every [PUSH_PERIOD]. It runs on the network, not in the server's namespace, so it
     * outlives the server and sees it go. Returns its container id.
     */
    private fun startPusher(port: Int): String {
        val script = checkNotNull(javaClass.getResource(PUSHER_SCRIPT_RESOURCE)) { "$PUSHER_SCRIPT_RESOURCE is missing" }.readText()
        RemoteDockerImage(DockerImageName.parse(CURL_IMAGE)).get()
        val url =
            "http://$PYROSCOPE_HOST:$port/ingest?name=$PUSHER_SERVICE&format=jfr" +
                "&sampleRate=100&spyName=javaspy&units=samples&aggregationType=sum"
        val id =
            docker
                .createContainerCmd(CURL_IMAGE)
                .withLabels(ObservabilityBackends.SESSION_LABELS)
                .withEntrypoint("/bin/sh", PUSHER_SCRIPT)
                .withEnv(
                    "URL=$url",
                    "TENANT=$TENANT",
                    "BODY=$PUSHER_BODY",
                    "PUSH_TIMEOUT_SECONDS=${PUSH_TIMEOUT.toSeconds()}",
                    "PERIOD_MS=${PUSH_PERIOD.toMillis()}",
                    "MAX_SECONDS=${PUSHER_LIMIT.toSeconds()}",
                ).withHostConfig(HostConfig.newHostConfig().withNetworkMode(network.id))
                .exec()
                .id
        helpers.add(id)
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use {
            Transferable.of(script).transferTo(it, PUSHER_SCRIPT.removePrefix("/"))
            Transferable.of(CpuSampleJfr.bytes()).transferTo(it, PUSHER_BODY.removePrefix("/"))
        }
        docker
            .copyArchiveToContainerCmd(id)
            .withTarInputStream(ByteArrayInputStream(tar.toByteArray()))
            .withRemotePath("/")
            .exec()
        docker.startContainerCmd(id).exec()
        return id
    }

    /**
     * Tells the pusher [id] to stop (TERM: it starts no more pushes and waits for those running),
     * waits for it to finish and counts its answers.
     */
    private fun pusherResult(id: String): Pushes {
        docker.killContainerCmd(id).withSignal("TERM").exec()
        val exit =
            docker
                .waitContainerCmd(id)
                .start()
                .awaitStatusCode(PUSHER_LIMIT.plus(PUSH_TIMEOUT).toSeconds(), TimeUnit.SECONDS)
        val output = LogUtils.getOutput(docker, id)
        check(exit == 0 && "DONE" in output) { "the pusher exited $exit:\n$output" }
        val counts =
            output
                .lines()
                .filter { it.startsWith("STATUS ") }
                .groupingBy { it.removePrefix("STATUS ").trim() }
                .eachCount()
        return Pushes(counts, output)
    }

    /**
     * Waits until the pusher [id] has had a push stored (it prints READY), so SIGTERM never comes
     * before the pusher reaches the server, and fails naming the pusher's output if it never does.
     */
    private fun awaitPusherReady(id: String) {
        val deadline = System.nanoTime() + PUSHER_READY.toNanos()
        while (System.nanoTime() < deadline) {
            if (READY_LINE in LogUtils.getOutput(docker, id).lines()) return
            Thread.sleep(READY_POLL.toMillis())
        }
        error("the pusher had no push stored within $PUSHER_READY:\n${LogUtils.getOutput(docker, id)}")
    }

    /**
     * Starts the pusher on [port], waits until it has a push stored, stops [pyroscope] as a pod
     * restart does, and returns the pusher's answers with the stopped server's log.
     */
    private fun pushThroughStop(
        pyroscope: GenericContainer<*>,
        port: Int,
    ): Pair<Pushes, String> {
        val pusher = startPusher(port)
        awaitPusherReady(pusher)
        // As ObservabilityBackends.stopGracefully, but the stopped server's log is read before it is removed.
        docker.stopContainerCmd(pyroscope.containerId).withTimeout(GRACE.toSeconds().toInt()).exec()
        val serverLog = pyroscope.logs
        pyroscope.stop()
        containers.remove(pyroscope)
        return pusherResult(pusher) to serverLog
    }

    @Test
    fun `pushes Pyroscope refuses while it shuts down are answered 503 by the front, and the agent loses no profile`() {
        docker.createVolumeCmd().withName(volume).exec()
        val first = startPyroscope()
        val jvm = startProfiledJvm()
        awaitWindowsFilled(first, listOf(nowSecond() - WINDOW.seconds))
        Thread.sleep(RUNNING.toMillis())

        val down = nowSecond()
        val (pushes, serverLog) = pushThroughStop(first, Constants.PyroscopeServer.FRONT_PORT)
        val second = startPyroscope()
        val restarted = nowSecond()
        Thread.sleep(RUNNING.toMillis())

        println("pusher through the front: ${pushes.counts}")
        val evidence = "pusher answers ${pushes.counts}; stopped server's log:\n$serverLog"
        assertThat(pushes.count(HTTP_UNAVAILABLE, refusal = true))
            .describedAs("shutdown refusals the front answered 503; $evidence")
            .isPositive()
        assertThat(pushes.countStatus(HTTP_REFUSED)).describedAs("pushes answered 422 through the front; $evidence").isZero()

        // The window before the stop, the stop and restart, and after.
        val windows = (down - RUNNING.seconds until restarted + RUNNING.seconds - WINDOW.seconds step WINDOW.seconds).toList()
        assertThat(awaitWindowsFilled(second, windows).map { it - down })
            .describedAs("seconds from the stop of each ${WINDOW.seconds}s window with no profile (${windows.size} checked)")
            .isEmpty()

        val log = jvm.logs
        val (startRace, dropped) =
            PyroscopeAgentSupport.droppedUploads(log).partition { PyroscopeAgentSupport.EMPTY_RING in it.lowercase() }
        assertThat(dropped).describedAs("uploads the agent dropped; JVM log:\n$log").isEmpty()
        assertThat(startRace).describedAs("uploads refused in the start race; JVM log:\n$log").hasSizeLessThanOrEqualTo(1)
    }

    @Test
    fun `without the front, Pyroscope answers pushes 422 while it shuts down`() {
        docker.createVolumeCmd().withName(volume).exec()
        val pyroscope = startPyroscope(withFront = false)

        val (pushes, serverLog) = pushThroughStop(pyroscope, PyroscopeManifestBuilder.SERVER_PORT)

        println("pusher straight to the server: ${pushes.counts}")
        assertThat(pushes.count(HTTP_REFUSED, refusal = true))
            .describedAs("shutdown refusals answered 422; pusher answers ${pushes.counts}; stopped server's log:\n$serverLog")
            .isPositive()
    }

    /** Polls until every window in [windows] holds a profile on [pyroscope], or [WAIT] passes; returns those still empty. */
    private fun awaitWindowsFilled(
        pyroscope: GenericContainer<*>,
        windows: List<Long>,
    ): List<Long> {
        var missing = windows
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (true) {
            missing = missing.filter { PyroscopeAgentSupport.cpuTicks(pyroscope, TENANT, SERVICE, it, it + WINDOW.seconds) == 0L }
            if (missing.isEmpty() || System.nanoTime() >= deadline) return missing
            Thread.sleep(POLL.toMillis())
        }
    }

    private fun nowSecond(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND
}

private const val MILLIS_PER_SECOND = 1000L
