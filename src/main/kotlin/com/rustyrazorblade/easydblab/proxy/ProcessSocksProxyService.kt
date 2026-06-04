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
 * When started, the proxy port is published as JVM system properties so all OkHttp and
 * fabric8 connections route through the tunnel automatically via `ProxySelector.getDefault()`.
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
        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", "$port")
        log.debug { "Set JVM system properties socksProxyHost=127.0.0.1 socksProxyPort=$port" }
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
