package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/** TCP port the control node's own `sshd` listens on — the end-to-end reachability target. */
internal const val SSH_PORT = 22

/** Matches `ssh -v` diagnostic lines (`debug1:`, `debug2:`, `debug3:`) so they can be dropped. */
private val SSH_DEBUG_LINE = Regex("""^debug\d*:.*""")

/**
 * Reduces an `ssh -v` transcript to the lines most likely to explain a failure.
 *
 * `ssh -v` prefixes its chatter with `debug1:`/`debug2:`/`debug3:`; genuine errors and warnings
 * (a changed host key, "Permission denied", "Connection refused") are emitted WITHOUT that prefix.
 * Dropping the debug-prefixed lines and keeping the tail of the remainder surfaces whatever the
 * real cause was without hardcoding a list of known error strings.
 *
 * @param tailLines how many trailing non-debug, non-blank lines to keep.
 */
internal fun stripSshDebugNoise(
    lines: List<String>,
    tailLines: Int,
): List<String> =
    lines
        .filterNot { SSH_DEBUG_LINE.matches(it) }
        .filter { it.isNotBlank() }
        .takeLast(tailLines)

/**
 * SOCKS5 proxy service that launches a detached `ssh -N -D` OS process.
 *
 * Unlike the previous in-process implementation, the SSH process outlives the JVM.
 * On each [ensureRunning] call the service checks `.socks5-proxy-state` for a reusable
 * process (PID alive + same controlIP + same sshConfig path + port genuinely accepting
 * connections) before starting a new one. A live PID whose port is not accepting connections
 * is a zombie tunnel and is never reused — a fresh proxy is started instead, and that start
 * fails the calling command if it cannot be verified either.
 * When started, the proxy port is published via the private [Constants.Proxy.PORT_PROPERTY] system
 * property. The clients that need the tunnel (fabric8 K8s, OkHttp) read that port and configure the
 * SOCKS proxy explicitly. We deliberately do NOT set the standard `socksProxyHost`/`socksProxyPort`
 * properties — those would make java.net route every socket (including the AWS SDK / S3) through the
 * tunnel, which breaks direct AWS access on corporate networks.
 *
 * The process is killed at cluster teardown by the `Down` command via `cleanupSocks5Proxy()`.
 */
class ProcessSocksProxyService(
    private val context: Context,
    private val reachabilityProbe: TunnelReachabilityProbe,
    private val verifyDelay: Duration = Duration.ofMillis(VERIFY_DELAY_MS),
) : SocksProxyService {
    companion object {
        private const val VERIFY_RETRIES = 10
        private const val VERIFY_DELAY_MS = 500L
        private const val VERIFY_CONNECT_TIMEOUT_MS = 1000
        private const val SSH_ERROR_TAIL_LINES = 15
        private const val LOGS_DIR = "logs"
        private val json = Json { prettyPrint = true }
    }

    private val lock = ReentrantLock()
    private var state: SocksProxyState? = null
    private var pid: Int = 0

    override fun ensureRunning(gatewayHost: ClusterHost): SocksProxyState =
        lock.withLock {
            // Return in-memory state if still alive AND the tunnel is genuinely accepting
            // connections. A live PID with a dead port is a zombie tunnel (e.g. the remote side
            // dropped the forward without killing the local process) and must not be reused —
            // see isValidProxy() below, which enforces the same check on the state-file path.
            val current = state
            if (current != null && isAlive(pid) && isPortAccepting(current.localPort)) {
                log.debug { "SOCKS5 proxy already running in-memory on port ${current.localPort} [PID $pid]" }
                return@withLock current
            }

            // Try to reuse from state file
            val stateFile = File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)
            val sshConfigPath = File(context.workingDirectory, "sshConfig").absolutePath

            if (stateFile.exists()) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val loaded = json.decodeFromString<Socks5ProxyStateFile>(stateFile.readText())
                    if (isValidProxy(loaded, gatewayHost, sshConfigPath)) {
                        log.info { "Reusing existing SOCKS5 proxy on port ${loaded.port} [PID ${loaded.pid}]" }
                        val reused = buildProxyState(loaded.port, gatewayHost)
                        state = reused
                        pid = loaded.pid
                        applySystemProperties(loaded.port)
                        return@withLock reused
                    } else {
                        log.info { "Stale SOCKS5 proxy state, starting fresh" }
                    }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to read proxy state file, starting fresh" }
                }
            }

            startNewProxy(gatewayHost, sshConfigPath, stateFile)
        }

    override fun start(gatewayHost: ClusterHost): SocksProxyState = ensureRunning(gatewayHost)

    override fun isRunning(): Boolean =
        lock.withLock {
            val current = state ?: return@withLock false
            isAlive(pid) && isPortAccepting(current.localPort)
        }

    override fun getState(): SocksProxyState? = lock.withLock { state }

    override fun getLocalPort(): Int =
        lock.withLock {
            state?.localPort ?: run {
                val stateFile = File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)
                if (stateFile.exists()) {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        json.decodeFromString<Socks5ProxyStateFile>(stateFile.readText()).port
                    } catch (e: Exception) {
                        log.warn(e) { "Could not read proxy port from state file" }
                        0
                    }
                } else {
                    0
                }
            }
        }

    /**
     * Starts a fresh SOCKS5 proxy `ssh` process to [gatewayHost] and verifies the tunnel is
     * reachable end-to-end before publishing its port.
     *
     * Any previously published port is cleared up front, so a failed start here never leaves a
     * stale port advertised to clients — they fall back to no-proxy rather than routing through
     * a dead tunnel. [verifyTunnelReachable] runs before the new port is published; if it cannot
     * prove the tunnel carries traffic to the control node within its retry window (or the ssh
     * process dies first), it throws and [applySystemProperties] is never reached, so the dead
     * port is never republished either. The `ssh -v` transcript is written to
     * `logs/${Constants.Proxy.SOCKS5_PROXY_LOG_FILE}` and its real error is surfaced in the thrown
     * message.
     *
     * @throws IllegalStateException if the tunnel never becomes reachable or ssh exits early
     */
    private fun startNewProxy(
        gatewayHost: ClusterHost,
        sshConfigPath: String,
        stateFile: File,
    ): SocksProxyState {
        // Clear any previously published port before starting. If this start fails (the ssh
        // process can't spawn, or we're replacing a stale proxy), cluster clients fall back to
        // NO_PROXY (direct) instead of routing through a dead tunnel port. The new port is
        // republished by applySystemProperties() only after the proxy is verified.
        System.clearProperty(Constants.Proxy.PORT_PROPERTY)

        val port = selectPort()
        log.info { "Starting SOCKS5 proxy to ${gatewayHost.alias} (${gatewayHost.privateIp}) on port $port" }

        // ProcessBuilder's redirect will NOT create parent dirs; without this, ssh's stderr is
        // silently discarded and the transcript we rely on for diagnosis would be lost.
        val logDir = File(context.workingDirectory, LOGS_DIR)
        logDir.mkdirs()
        val logFile = File(logDir, Constants.Proxy.SOCKS5_PROXY_LOG_FILE)
        val process =
            ProcessBuilder(
                "nohup",
                "ssh",
                "-v",
                // Exit immediately if the dynamic forward can't be set up, instead of lingering
                // with a half-open connection — this is what lets us detect a dead ssh fast.
                "-o",
                "ExitOnForwardFailure=yes",
                "-N",
                "-D",
                "$port",
                "-F",
                sshConfigPath,
                gatewayHost.alias,
            ).apply {
                redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                // Overwrite (not append) so the log is always exactly this attempt's transcript.
                redirectError(ProcessBuilder.Redirect.to(logFile))
            }.start()

        val newPid = process.pid().toInt()
        log.info { "SSH proxy process started [PID $newPid]" }

        try {
            verifyTunnelReachable(process, port, gatewayHost.privateIp, logFile)
        } catch (e: IllegalStateException) {
            // Verification failed: nothing will ever record this PID (the state file write
            // below is never reached), so it would otherwise be an untracked orphan that
            // `down` can never find and kill. Destroy it here instead of leaking it.
            process.destroyForcibly()
            throw e
        }

        val clusterName = context.workingDirectory.name
        val fileState =
            Socks5ProxyStateFile(
                pid = newPid,
                port = port,
                controlHost = gatewayHost.alias,
                controlIP = gatewayHost.privateIp,
                clusterName = clusterName,
                startTime = Instant.now().toString(),
                sshConfig = sshConfigPath,
            )
        stateFile.writeText(json.encodeToString(fileState))
        log.debug { "Proxy state written to ${stateFile.absolutePath}" }

        applySystemProperties(port)

        val proxyState = buildProxyState(port, gatewayHost)
        state = proxyState
        pid = newPid

        log.info { "SOCKS5 proxy started successfully on 127.0.0.1:$port via ${gatewayHost.alias}" }
        return proxyState
    }

    private fun selectPort(): Int {
        val preferred = Constants.Proxy.DEFAULT_SOCKS5_PORT
        return try {
            ServerSocket(preferred).use { preferred }
        } catch (_: BindException) {
            log.debug { "Port $preferred is in use, selecting an available port" }
            ServerSocket(0).use { it.localPort }
        }
    }

    private fun applySystemProperties(port: Int) {
        // Publish ONLY the port under our private property. Never set socksProxyHost/socksProxyPort:
        // those make java.net tunnel every socket (including the AWS SDK / S3), which is wrong — AWS
        // public endpoints must be reached directly. Cluster clients read this port and opt in.
        System.setProperty(Constants.Proxy.PORT_PROPERTY, "$port")
        log.debug { "Published SOCKS5 proxy port $port via ${Constants.Proxy.PORT_PROPERTY} (global socksProxyHost left unset)" }
    }

    private fun isValidProxy(
        loaded: Socks5ProxyStateFile,
        gatewayHost: ClusterHost,
        sshConfigPath: String,
    ): Boolean {
        if (!isAlive(loaded.pid)) {
            log.debug { "Proxy PID ${loaded.pid} is no longer alive" }
            return false
        }
        if (loaded.sshConfig != sshConfigPath) {
            log.debug { "SSH config path changed (was ${loaded.sshConfig}, now $sshConfigPath)" }
            return false
        }
        if (loaded.controlIP != gatewayHost.privateIp) {
            log.debug { "Control IP changed (was ${loaded.controlIP}, now ${gatewayHost.privateIp})" }
            return false
        }
        if (!isPortAccepting(loaded.port)) {
            log.debug { "Proxy PID ${loaded.pid} is alive but port ${loaded.port} is not accepting connections" }
            return false
        }
        return true
    }

    private fun isAlive(processPid: Int): Boolean = processPid > 0 && ProcessHandle.of(processPid.toLong()).isPresent

    private fun isPortAccepting(port: Int): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), VERIFY_CONNECT_TIMEOUT_MS)
            }
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Verifies the tunnel is reachable end-to-end for up to [VERIFY_RETRIES] * [VERIFY_DELAY_MS],
     * bailing out the instant the ssh [process] dies rather than polling a corpse for the full
     * window.
     *
     * Success requires the [reachabilityProbe] to confirm an actual SOCKS5 round-trip to
     * [targetPrivateIp]:[SSH_PORT], not merely that the local `-D` listener opened — the listener
     * opens even when the remote side is dead. Fails fast by design: the caller must surface the
     * failure so the invoking command aborts rather than proceeding against a dead tunnel.
     *
     * Internal (not private) purely so the loop's decisions — liveness-first, probe as the success
     * condition, timeout, and exit-code reporting — can be driven directly in tests with a mock
     * [Process] and a mock probe, without spawning a real ssh process.
     *
     * @throws IllegalStateException if ssh exits early or the tunnel never becomes reachable
     */
    @Suppress("MagicNumber")
    internal fun verifyTunnelReachable(
        process: Process,
        port: Int,
        targetPrivateIp: String,
        logFile: File,
    ) {
        repeat(VERIFY_RETRIES) { attempt ->
            // A dead ssh (e.g. a changed host key kills it in ~50ms) will never open the tunnel;
            // stop immediately instead of waiting out the remaining attempts.
            if (!process.isAlive) {
                throw IllegalStateException(verificationFailureMessage(logFile, exitCode = process.exitValue()))
            }
            if (reachabilityProbe.isReachable(port, targetPrivateIp, SSH_PORT)) {
                log.debug { "SOCKS5 tunnel reachable end-to-end on port $port (attempt ${attempt + 1})" }
                return
            }
            log.debug { "SOCKS5 tunnel not reachable yet (attempt ${attempt + 1}/$VERIFY_RETRIES)" }
            if (attempt < VERIFY_RETRIES - 1) {
                Thread.sleep(verifyDelay.toMillis())
            }
        }
        val exitCode = if (process.isAlive) null else process.exitValue()
        throw IllegalStateException(verificationFailureMessage(logFile, exitCode))
    }

    /**
     * Builds the failure message for a proxy that never came up, naming the SOCKS proxy as the
     * failing component, citing the ssh exit code when it died, and surfacing the real ssh error
     * pulled from [logFile] (debug chatter stripped) plus the full transcript path.
     */
    private fun verificationFailureMessage(
        logFile: File,
        exitCode: Int?,
    ): String {
        val exitInfo = exitCode?.let { " (ssh exited with code $it)" } ?: ""
        val errors = readSshErrors(logFile)
        return buildString {
            append("SOCKS5 proxy failed to establish a working tunnel$exitInfo. ")
            append("See ${logFile.absolutePath} for the full ssh -v transcript.")
            if (errors.isNotEmpty()) {
                append("\nssh reported:\n")
                append(errors.joinToString("\n"))
            }
        }
    }

    private fun readSshErrors(logFile: File): List<String> =
        try {
            stripSshDebugNoise(logFile.readLines(), SSH_ERROR_TAIL_LINES)
        } catch (e: Exception) {
            log.debug(e) { "Could not read ssh transcript from ${logFile.absolutePath}" }
            emptyList()
        }

    private fun buildProxyState(
        port: Int,
        gatewayHost: ClusterHost,
    ): SocksProxyState =
        SocksProxyState(
            localPort = port,
            gatewayHost = gatewayHost,
            startTime = Instant.now(),
        )
}
