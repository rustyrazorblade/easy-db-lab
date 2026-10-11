package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.writeTextAtomically
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.retry.Retry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.time.Clock
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
 * What `ssh` prints when its `-D` listener cannot bind: `bind [127.0.0.1]:<port>: Address already in
 * use` for each loopback address, then `cannot listen to port: <port>` once every address failed.
 */
private val LOCAL_PORT_BIND_FAILURE = Regex("""Address already in use|cannot listen to port""")

/**
 * Whether an `ssh` transcript shows the dynamic forward failed because its local port was already
 * bound by another process — the one ssh failure a fresh port fixes.
 */
internal fun isLocalPortBindFailure(transcript: List<String>): Boolean = transcript.any { LOCAL_PORT_BIND_FAILURE.containsMatchIn(it) }

/**
 * SOCKS5 proxy service that launches a detached `<workspace>/bin/edl-socks-tunnel` OS process, which
 * runs `ssh -N -D` and starts it again on the same port when its connection dies.
 *
 * Unlike the previous in-process implementation, the tunnel process outlives the JVM. Its PID is the
 * one recorded, and [TunnelProcessControl] stops it and its ssh together.
 * On each [ensureRunning] call the service checks its in-memory tunnel, then `.socks5-proxy-state`,
 * for a reusable process (PID alive + same controlIP + same sshConfig path + the tunnel verified
 * end-to-end by the [TunnelReachabilityProbe]) before starting a new one. A live ssh whose tunnel
 * no longer reaches the control node is a zombie tunnel and is never reused: it is stopped, a fresh
 * proxy is started instead, and that start fails the calling command if it cannot be verified either.
 * When started, the proxy port is published via the private [Constants.Proxy.PORT_PROPERTY] system
 * property. The clients that need the tunnel (fabric8 K8s, OkHttp) read that port and configure the
 * SOCKS proxy explicitly. We deliberately do NOT set the standard `socksProxyHost`/`socksProxyPort`
 * properties — those would make java.net route every socket (including the AWS SDK / S3) through the
 * tunnel, which breaks direct AWS access on corporate networks.
 *
 * Each verified start or reuse also records the port in the workspace's [ProxyEnvFile], which is
 * where the shell-side tool wrappers read it; a start removes the old port first, so a failed start
 * leaves none recorded. The state file is replaced atomically, so a reader never sees a partial one.
 *
 * The process is killed at cluster teardown by the `Down` command via `cleanupSocks5Proxy()`.
 *
 * @param verifyAttempts how many times a fresh tunnel is probed before it counts as failed; the
 *   SSH route supplies it, because a tunnel over SSM Session Manager takes far longer to come up
 */
class ProcessSocksProxyService(
    private val context: Context,
    private val reachabilityProbe: TunnelReachabilityProbe,
    private val verifyDelay: Duration = Duration.ofMillis(VERIFY_DELAY_MS),
    private val processLauncher: SshProcessLauncher = DefaultSshProcessLauncher,
    private val portSelector: LocalPortSelector = LoopbackPortSelector(),
    private val verifyAttempts: Int = Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS,
    private val envFile: ProxyEnvFile = ProxyEnvFile(context.workingDirectory),
    private val tunnelProcesses: TunnelProcessControl = TunnelProcessControl(),
    private val clock: Clock = Clock.systemUTC(),
    private val toolWrappers: ToolWrapperInstaller = ToolWrapperInstaller(),
) : SocksProxyService {
    companion object {
        private const val VERIFY_DELAY_MS = 500L
        private const val VERIFY_CONNECT_TIMEOUT_MS = 1000
        private const val SSH_ERROR_TAIL_LINES = 15
        private const val LOGS_DIR = "logs"
        private val json = Json { prettyPrint = true }
    }

    private val lock = ReentrantLock()
    private var state: SocksProxyState? = null
    private var pid: Int = 0

    /** The tunnel that last passed the end-to-end probe in this process, and when it passed. */
    private data class VerifiedTunnel(
        val pid: Int,
        val port: Int,
        val at: Instant,
    )

    private var lastVerified: VerifiedTunnel? = null

    override fun ensureRunning(gatewayHost: ClusterHost): SocksProxyState =
        lock.withLock {
            val current = state
            if (current != null && isAlive(pid)) {
                reuseInMemory(current, gatewayHost)?.let { return@withLock it }
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
                        val reused = buildProxyState(loaded.port, gatewayHost).copy(reused = true)
                        state = reused
                        pid = loaded.pid
                        markVerified(loaded.pid, loaded.port)
                        applySystemProperties(loaded.port)
                        envFile.recordPort(loaded.port)
                        return@withLock reused
                    } else {
                        log.info { "Stale SOCKS5 proxy state, starting fresh" }
                        // The recorded proxy is not reusable (e.g. a live PID whose port stopped
                        // accepting — a zombie tunnel). startNewProxy() below overwrites the state
                        // file with a new PID, so this one becomes unrecorded and `Down` could
                        // never find it. Kill it now, before it is forgotten, or it leaks and
                        // survives teardown (issue #741).
                        terminateStaleProxy(loaded)
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

    override fun stop(): TunnelStopResult =
        lock.withLock {
            val stateFile = File(context.workingDirectory, Constants.Vpc.SOCKS5_PROXY_STATE_FILE)
            // A state file that cannot be read does not mean no tunnel: one this process started is
            // still known in memory, and goes through the same verified stop.
            val recorded = readStateFile(stateFile) ?: inMemoryRecord()
            val result = recorded?.let { tunnelProcesses.stop(it) } ?: TunnelStopResult.NotRunning
            when (result) {
                // The process still runs, so its PID and port stay recorded for the next attempt.
                is TunnelStopResult.StopFailed -> Unit
                is TunnelStopResult.Stopped, TunnelStopResult.NotRunning -> {
                    System.clearProperty(Constants.Proxy.PORT_PROPERTY)
                    envFile.removePort()
                    stateFile.delete()
                    forgetInMemory()
                }
            }
            result
        }

    /**
     * The in-memory tunnel, when it is still to [gatewayHost] and still carries traffic end-to-end;
     * otherwise null, after stopping it. A live ssh with an open local port can still be a zombie
     * tunnel (its SSH connection died silently), so the same probe isValidProxy() uses on the
     * state-file path decides. A tunnel that passed the probe less than
     * [Constants.Proxy.REUSE_PROBE_FRESHNESS] ago is reused without probing again, because cluster
     * HTTP clients ask for the tunnel on every request.
     */
    private fun reuseInMemory(
        current: SocksProxyState,
        gatewayHost: ClusterHost,
    ): SocksProxyState? {
        val replaceReason =
            when {
                current.gatewayHost.privateIp != gatewayHost.privateIp ->
                    "goes to ${current.gatewayHost.privateIp}, not ${gatewayHost.alias} (${gatewayHost.privateIp})"
                isFreshlyVerified(pid, current.localPort) -> null
                reachesGateway(current.localPort, gatewayHost) -> null.also { markVerified(pid, current.localPort) }
                else -> "no longer reaches ${gatewayHost.alias}"
            }
        if (replaceReason == null) {
            log.debug { "SOCKS5 proxy already running in-memory on port ${current.localPort} [PID $pid]" }
            envFile.recordPort(current.localPort)
            return current.copy(reused = true)
        }
        log.info { "SOCKS5 proxy on port ${current.localPort} [PID $pid] $replaceReason; replacing it" }
        inMemoryRecord()?.let { terminateStaleProxy(it) }
        forgetInMemory()
        return null
    }

    /** Records that the tunnel [tunnelPid] on [port] passed the end-to-end probe just now. */
    private fun markVerified(
        tunnelPid: Int,
        port: Int,
    ) {
        lastVerified = VerifiedTunnel(tunnelPid, port, clock.instant())
    }

    /** Whether the tunnel [tunnelPid] on [port] passed the probe less than the freshness window ago. */
    private fun isFreshlyVerified(
        tunnelPid: Int,
        port: Int,
    ): Boolean =
        lastVerified?.let {
            it.pid == tunnelPid && it.port == port && Duration.between(it.at, clock.instant()) < Constants.Proxy.REUSE_PROBE_FRESHNESS
        } ?: false

    /** Drops the in-memory tunnel and its probe time; the next call finds it again only through the state file. */
    private fun forgetInMemory() {
        state = null
        pid = 0
        lastVerified = null
    }

    /** The tunnel this process started or reused, as the state file would record it; null when there is none. */
    private fun inMemoryRecord(): Socks5ProxyStateFile? {
        val current = state ?: return null
        if (pid <= 0) return null
        return Socks5ProxyStateFile(
            pid = pid,
            port = current.localPort,
            controlHost = current.gatewayHost.alias,
            controlIP = current.gatewayHost.privateIp,
            clusterName = context.workingDirectory.name,
            startTime = current.startTime.toString(),
            sshConfig = File(context.workingDirectory, "sshConfig").absolutePath,
        )
    }

    /** The recorded proxy state, or null when the file is missing or cannot be read. */
    private fun readStateFile(stateFile: File): Socks5ProxyStateFile? =
        stateFile.takeIf { it.exists() }?.let { file ->
            runCatching { json.decodeFromString<Socks5ProxyStateFile>(file.readText()) }
                .onFailure { e -> log.warn(e) { "Could not read the SOCKS5 proxy state file ${file.absolutePath}" } }
                .getOrNull()
        }

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
     * The port is chosen per attempt. Another workspace starting its own proxy at the same moment
     * can find the same port free and bind it first; ssh then dies with "Address already in use".
     * That one failure is retried under [RetryUtil.createLocalPortBindRetryConfig], each attempt
     * on a freshly selected port, so concurrent clusters never collide on the local SOCKS port.
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
        // The same for shell-side tools: a wrapper must never route through the port of a tunnel
        // that is being replaced, so the env file records no port until the new one is verified.
        envFile.removePort()
        // Nor may an in-memory reuse skip the probe on the strength of the tunnel being replaced.
        lastVerified = null

        // ProcessBuilder's redirect will NOT create parent dirs; without this, ssh's stderr is
        // silently discarded and the transcript we rely on for diagnosis would be lost.
        val logDir = File(context.workingDirectory, LOGS_DIR)
        logDir.mkdirs()
        val logFile = File(logDir, Constants.Proxy.SOCKS5_PROXY_LOG_FILE)

        val retry = Retry.of("socks5-proxy-start", RetryUtil.createLocalPortBindRetryConfig())
        val launched =
            try {
                // executeCallable, not decorateSupplier: a Supplier retries only RuntimeExceptions,
                // and BindException is checked.
                retry.executeCallable { launchVerifiedProxy(gatewayHost, sshConfigPath, logFile) }
            } catch (e: BindException) {
                // Every attempt lost its port to another process. Surface it as the same tunnel
                // failure every other start error is, so callers handle one exception type.
                throw IllegalStateException(e.message, e)
            }
        val port = launched.port
        val newPid = launched.pid

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
        stateFile.writeTextAtomically(json.encodeToString(fileState))
        log.debug { "Proxy state written to ${stateFile.absolutePath}" }

        applySystemProperties(port)
        envFile.recordPort(port)

        val proxyState = buildProxyState(port, gatewayHost)
        state = proxyState
        pid = newPid
        markVerified(newPid, port)

        log.info { "SOCKS5 proxy started successfully on 127.0.0.1:$port via ${gatewayHost.alias}" }
        return proxyState
    }

    /** A launched `ssh -D` process whose tunnel was verified reachable: its local [port] and [pid]. */
    private data class LaunchedProxy(
        val port: Int,
        val pid: Int,
    )

    /**
     * One start attempt: selects a port, launches `ssh -D` on it, and verifies the tunnel.
     *
     * A failed attempt's process is destroyed, since nothing will ever record its PID and `down`
     * could never find it. When ssh died because its port was already bound by another process, the
     * failure is thrown as a [BindException] so the caller's retry selects a new port; every other
     * failure keeps its [IllegalStateException] and is not retried.
     */
    private fun launchVerifiedProxy(
        gatewayHost: ClusterHost,
        sshConfigPath: String,
        logFile: File,
    ): LaunchedProxy {
        val port = portSelector.select()
        log.info { "Starting SOCKS5 proxy to ${gatewayHost.alias} (${gatewayHost.privateIp}) on port $port" }
        // A workspace from before the tunnel script, or one whose bin/ was cleaned, may not have it yet.
        toolWrappers.install(context.workingDirectory)
        val process = processLauncher.launch(buildTunnelCommand(port, sshConfigPath, gatewayHost.alias), logFile)

        val newPid = process.pid().toInt()
        log.info { "SOCKS5 tunnel process started [PID $newPid]" }

        try {
            verifyTunnelReachable(process, port, gatewayHost.privateIp, logFile)
        } catch (e: IllegalStateException) {
            val sshExited = !process.isAlive
            // The script's ssh is taken first: once the script is killed, it is no longer a descendant.
            val children = process.descendants().toList()
            process.destroyForcibly()
            children.forEach { it.destroyForcibly() }
            if (sshExited && isLocalPortBindFailure(readTranscript(logFile))) {
                log.info { "SOCKS5 proxy port $port was taken by another process; selecting another port" }
                throw BindException(e.message)
            }
            throw e
        }
        return LaunchedProxy(port, newPid)
    }

    /**
     * Builds the command line that starts the workspace's `edl-socks-tunnel` for the dynamic SOCKS5
     * forward on [port] to [alias]. The script runs `ssh -v -o ExitOnForwardFailure=yes -N -D`, and
     * starts it again on the same port when it exits, so a dropped connection reconnects with no CLI
     * command. `nohup` keeps the tunnel alive when the terminal that started it closes.
     *
     * Internal (not private) purely so a test can assert the command dials the gateway's recorded
     * [alias] (never a hardcoded host), without spawning the script.
     */
    internal fun buildTunnelCommand(
        port: Int,
        sshConfigPath: String,
        alias: String,
    ): List<String> =
        listOf(
            "nohup",
            File(File(context.workingDirectory, Constants.ToolWrappers.DIRECTORY), Constants.ToolWrappers.TUNNEL_SCRIPT).absolutePath,
            "$port",
            sshConfigPath,
            "${Constants.Proxy.TUNNEL_RESTART_BACKOFF_SECONDS}",
            "${Constants.Proxy.TUNNEL_STARTUP_GRACE_SECONDS}",
            alias,
        )

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
        if (!reachesGateway(loaded.port, gatewayHost)) {
            log.info { "SOCKS5 proxy on port ${loaded.port} [PID ${loaded.pid}] no longer reaches ${gatewayHost.alias}; replacing it" }
            return false
        }
        return true
    }

    /**
     * Whether the running tunnel on [port] still carries traffic to [gatewayHost]'s `sshd`, by the same
     * [reachabilityProbe] a fresh start is verified with. A tunnel that is up answers at once, so it gets
     * only [Constants.Proxy.REUSE_TUNNEL_VERIFY_ATTEMPTS] tries, not a fresh start's window.
     */
    private fun reachesGateway(
        port: Int,
        gatewayHost: ClusterHost,
    ): Boolean =
        (1..Constants.Proxy.REUSE_TUNNEL_VERIFY_ATTEMPTS).any { attempt ->
            if (attempt > 1) Thread.sleep(verifyDelay.toMillis())
            reachabilityProbe.isReachable(port, gatewayHost.privateIp, SSH_PORT)
        }

    /**
     * Ends a superseded proxy `ssh` process so it does not leak.
     *
     * Called when a recorded proxy is being replaced rather than reused (e.g. a zombie tunnel:
     * PID alive but its `-D` port stopped accepting). Once [startNewProxy] overwrites the state
     * file, [stale] is the last record of that process — `Down` reads only the current state
     * file, so an un-stopped stale tunnel survives even `easy-db-lab down` (issue #741).
     *
     * The PID is signaled only when it is still that tunnel, by the same check `stop()` uses
     * ([TunnelProcessControl]): the OS may have given a dead tunnel's PID to another program.
     * Stopping it never blocks starting its replacement: a tunnel that will not end is logged.
     *
     * Internal (not private) purely so a test can drive it without exercising the whole
     * [ensureRunning] state-file path.
     */
    internal fun terminateStaleProxy(stale: Socks5ProxyStateFile) {
        when (val result = tunnelProcesses.stop(stale)) {
            is TunnelStopResult.Stopped -> log.info { "Terminated superseded SOCKS5 proxy process [PID ${result.pid}]" }
            TunnelStopResult.NotRunning -> log.debug { "Superseded SOCKS5 proxy [PID ${stale.pid}] is no longer running" }
            is TunnelStopResult.StopFailed -> log.warn { "Superseded SOCKS5 proxy process [PID ${result.pid}] did not stop" }
        }
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
     * Verifies the tunnel is reachable end-to-end for up to [verifyAttempts] * [verifyDelay],
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
        repeat(verifyAttempts) { attempt ->
            // A dead ssh (e.g. a changed host key kills it in ~50ms) will never open the tunnel;
            // stop immediately instead of waiting out the remaining attempts.
            if (!process.isAlive) {
                throw IllegalStateException(verificationFailureMessage(logFile, exitCode = process.exitValue()))
            }
            if (reachabilityProbe.isReachable(port, targetPrivateIp, SSH_PORT)) {
                log.debug { "SOCKS5 tunnel reachable end-to-end on port $port (attempt ${attempt + 1})" }
                return
            }
            log.debug { "SOCKS5 tunnel not reachable yet (attempt ${attempt + 1}/$verifyAttempts)" }
            if (attempt < verifyAttempts - 1) {
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
     *
     * Internal (not private) purely so the message construction — that the real, un-prefixed ssh
     * error is surfaced while `debug*:` chatter is dropped — can be driven directly in tests with a
     * synthetic transcript, without spawning a real ssh process against a refused port.
     */
    internal fun verificationFailureMessage(
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

    private fun readSshErrors(logFile: File): List<String> = stripSshDebugNoise(readTranscript(logFile), SSH_ERROR_TAIL_LINES)

    private fun readTranscript(logFile: File): List<String> =
        try {
            logFile.readLines()
        } catch (e: IOException) {
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
