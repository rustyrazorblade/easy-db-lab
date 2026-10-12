package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The shared fixtures of the [ProcessSocksProxyService] unit tests: a workspace in a temp directory, a
 * service built on an injected fake [SshProcessLauncher], [TunnelReachabilityProbe] and
 * [TunnelProcessControl], fake processes, and the events the service emitted. No test here starts a
 * real ssh or binds a real socket.
 */
internal abstract class ProcessSocksProxyServiceTestBase {
    protected companion object {
        /** Tiny verify delay so the verify loop's retries do not sleep the production 500ms each. */
        val VERIFY_DELAY: Duration = Duration.ofMillis(1)

        /** Arbitrary PID returned by fake processes; never inspected for liveness by these tests. */
        const val FAKE_PID = 4242L

        /** Port the default fake selector hands out; nothing ever binds it. */
        const val DEFAULT_TEST_PORT = 52000

        /** Port a recorded tunnel listens on in the stop tests; nothing binds it. */
        const val STOP_PORT = 41234

        /** Port a replacement tunnel is started on; nothing binds it. */
        const val NEW_PORT = 45678

        /** Just past the probe freshness window, so the next reuse probes. */
        val PAST_FRESHNESS: Duration = Constants.Proxy.REUSE_PROBE_FRESHNESS.plusSeconds(1)
    }

    @TempDir
    lateinit var tempDir: File

    protected val json = Json { prettyPrint = true }
    protected val testHost =
        ClusterHost(
            publicIp = "54.1.2.3",
            privateIp = "10.0.1.5",
            alias = "control0",
            availabilityZone = "us-east-1a",
        )

    /** Every event the services under test emitted, in order. */
    protected val events = mutableListOf<Event>()

    protected val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events += envelope.event
                    }

                    override fun close() = Unit
                },
            )
        }

    @BeforeEach
    fun setUp() {
        File(tempDir, "sshConfig").writeText("Host control0\n  Hostname 10.0.1.5\n")
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)
    }

    /**
     * Builds a service with an injected [probe] and [launcher]. The default launcher fails loudly if
     * called, so tests that must not reach a fresh ssh spawn are protected against accidentally
     * doing so; tests that exercise a spawn pass an explicit fake.
     */
    protected fun service(
        probe: TunnelReachabilityProbe = TunnelReachabilityProbe { _, _, _ -> false },
        launcher: SshProcessLauncher =
            SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
        portSelector: LocalPortSelector = LocalPortSelector { DEFAULT_TEST_PORT },
        verifyAttempts: Int = Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS,
        tunnelProcesses: TunnelProcessControl = TunnelProcessControl(),
        clock: Clock = Clock.systemUTC(),
    ) = ProcessSocksProxyService(
        Context.forCli(tempDir).copy(workingDirectory = tempDir),
        probe,
        eventBus,
        verifyDelay = VERIFY_DELAY,
        processLauncher = launcher,
        portSelector = portSelector,
        verifyAttempts = verifyAttempts,
        tunnelProcesses = tunnelProcesses,
        clock = clock,
    )

    /** A service whose process lookup sees only [process]. */
    protected fun serviceSeeing(
        process: FakeTunnelProcess?,
        probe: TunnelReachabilityProbe = TunnelReachabilityProbe { _, _, _ -> false },
        launcher: SshProcessLauncher = SshProcessLauncher { _, _ -> error("ssh launch not expected in this test") },
    ) = service(
        probe = probe,
        launcher = launcher,
        portSelector = { STOP_PORT },
        tunnelProcesses =
            TunnelProcessControl(lookup = { pid -> process?.takeIf { it.pid == pid }?.handle }, stopWait = Duration.ofMillis(50)),
    )

    protected fun stateFile() = File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)

    protected fun recordedEnv() =
        ProxyEnvFile(tempDir).apply {
            recordTailscale(active = false)
            recordTunnel(STOP_PORT, ProcessHandle.current().pid())
        }

    /** The `-D` port each launched ssh command was handed, in launch order. */
    protected fun dynamicForwardPorts(launched: List<List<String>>): List<Int> =
        launched.map { command -> command[command.indexOfFirst { it.endsWith("/${Constants.ToolWrappers.TUNNEL_SCRIPT}") } + 1].toInt() }

    /** Writes what ssh prints when another process already holds its `-D` port. */
    protected fun writeBindFailureTranscript(
        logFile: File,
        port: Int,
    ) {
        logFile.appendText(
            """
            debug1: Local connections to LOCALHOST:$port forwarded to remote address socks:0
            bind [127.0.0.1]:$port: Address already in use
            channel_setup_fwd_listener_tcpip: cannot listen to port: $port
            Could not request local forwarding.
            """.trimIndent(),
        )
    }

    protected fun writeStateFile(
        pid: Int,
        port: Int,
        controlIP: String = testHost.privateIp,
    ) {
        val sshConfigPath = File(tempDir, "sshConfig").absolutePath
        val stateFile =
            Socks5ProxyStateFile(
                pid = pid,
                port = port,
                controlHost = "control0",
                controlIP = controlIP,
                clusterName = tempDir.name,
                startTime = Instant.now().toString(),
                sshConfig = sshConfigPath,
            )
        File(tempDir, Constants.Vpc.SOCKS5_PROXY_STATE_FILE).writeText(json.encodeToString(stateFile))
    }

    /** A log path under the workspace that may or may not exist — error extraction tolerates both. */
    protected fun logFile(): File = File(File(tempDir, "logs"), Constants.Proxy.SOCKS5_PROXY_LOG_FILE)

    /** A fake process that has already exited with [exitCode] — models a dead ssh. */
    protected fun deadProcess(exitCode: Int): Process =
        mock {
            on { isAlive } doReturn false
            on { exitValue() } doReturn exitCode
            on { pid() } doReturn FAKE_PID
        }

    /** A fake process that stays alive — models an ssh that hangs mid-handshake. */
    protected fun aliveProcess(): Process =
        mock {
            on { isAlive } doReturn true
            on { pid() } doReturn FAKE_PID
        }

    /**
     * A live PID that is not this JVM's, which the stop path refuses to signal: the test runner's parent.
     * The service's liveness check sees it alive; every lookup here is faked, so it is never signaled.
     */
    protected val livePid: Long = requireNotNull(ProcessHandle.current().parent().orElse(null)) { "the test JVM has no parent" }.pid()

    /** A process lookup that finds nothing, so a stale-tunnel stop never reaches a real process. */
    protected val noProcesses = TunnelProcessControl(lookup = { null })

    /** A launched ssh that stays alive under [livePid], so the in-memory reuse path sees it running. */
    protected fun liveTunnelProcess(): Process =
        mock {
            on { isAlive } doReturn true
            on { pid() } doReturn livePid
        }

    /** A probe that reports reachable only through the ports in [healthy], counting every call per port. */
    protected class PortProbe(
        vararg healthy: Int,
    ) : TunnelReachabilityProbe {
        val healthy = healthy.toMutableSet()
        val calls = mutableMapOf<Int, Int>()

        override fun isReachable(
            localSocksPort: Int,
            targetHost: String,
            targetPort: Int,
        ): Boolean {
            calls.merge(localSocksPort, 1, Int::plus)
            return localSocksPort in healthy
        }
    }

    /** A clock the test moves by hand, so the probe freshness window is tested without sleeping. */
    protected class ManualClock(
        private var now: Instant = Instant.parse("2026-10-10T12:00:00Z"),
    ) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    /** An in-memory service on [clock] whose tunnels are launched on [ports] in order and probed by [probe]. */
    protected fun inMemoryService(
        probe: PortProbe,
        clock: Clock,
        ports: ArrayDeque<Int>,
        launched: MutableList<List<String>> = mutableListOf(),
        launcher: SshProcessLauncher = SshProcessLauncher { command, _ -> liveTunnelProcess().also { launched += command } },
    ) = service(probe = probe, launcher = launcher, portSelector = { ports.removeFirst() }, tunnelProcesses = noProcesses, clock = clock)

    /** The record a superseded tunnel left: what ensureRunning has just found not reusable (issue #741). */
    protected fun staleRecord() =
        Socks5ProxyStateFile(
            pid = FAKE_PID.toInt(),
            port = STOP_PORT,
            controlHost = "control0",
            controlIP = testHost.privateIp,
            clusterName = tempDir.name,
            startTime = Instant.now().toString(),
            sshConfig = File(tempDir, "sshConfig").absolutePath,
        )
}
