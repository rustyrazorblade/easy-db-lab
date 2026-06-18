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
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/**
 * SOCKS5 proxy service that launches a detached `ssh -N -D` OS process.
 *
 * Unlike the previous in-process implementation, the SSH process outlives the JVM.
 * On each [ensureRunning] call the service checks `.socks5-proxy-state` for a reusable
 * process (PID alive + same controlIP + same sshConfig path) before starting a new one.
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
) : SocksProxyService {
    companion object {
        private const val VERIFY_RETRIES = 10
        private const val VERIFY_DELAY_MS = 500L
        private const val VERIFY_CONNECT_TIMEOUT_MS = 1000
        private val json = Json { prettyPrint = true }
    }

    private val lock = ReentrantLock()
    private var state: SocksProxyState? = null
    private var pid: Int = 0

    override fun ensureRunning(gatewayHost: ClusterHost): SocksProxyState =
        lock.withLock {
            // Return in-memory state if still alive
            val current = state
            if (current != null && isAlive(pid)) {
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

        val logFile = File(context.workingDirectory, "socks5-proxy.log")
        val process =
            ProcessBuilder(
                "nohup",
                "ssh",
                "-v",
                "-N",
                "-D",
                "$port",
                "-F",
                sshConfigPath,
                "control0",
            ).apply {
                redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            }.start()

        val newPid = process.pid().toInt()
        log.info { "SSH proxy process started [PID $newPid]" }

        verifyProxyAcceptingConnections(port)

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

    @Suppress("MagicNumber")
    private fun verifyProxyAcceptingConnections(port: Int) {
        repeat(VERIFY_RETRIES) { attempt ->
            if (isPortAccepting(port)) {
                log.debug { "SOCKS5 proxy accepting connections on port $port (attempt ${attempt + 1})" }
                return
            }
            log.debug { "SOCKS5 proxy not ready yet (attempt ${attempt + 1}/$VERIFY_RETRIES)" }
            if (attempt < VERIFY_RETRIES - 1) {
                Thread.sleep(VERIFY_DELAY_MS)
            }
        }
        log.warn { "Could not verify SOCKS5 proxy is accepting connections on port $port — proceeding anyway" }
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
