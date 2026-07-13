package com.rustyrazorblade.easydblab.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

private val log = KotlinLogging.logger {}

/** Prefix every `sshd` sends at the very start of its identification string (RFC 4253). */
private const val SSH_BANNER_PREFIX = "SSH-"

/**
 * Proves a SOCKS5 tunnel carries traffic end-to-end, not merely that its local listener is open.
 *
 * This is the injectable seam [ProcessSocksProxyService] uses to decide whether a freshly started
 * proxy is actually usable. It is an interface so the verify loop's decisions (liveness-first,
 * probe-as-success-condition, timeout) can be driven deterministically in tests without a real
 * ssh tunnel, while production wires in [SocksTunnelReachabilityProbe].
 */
fun interface TunnelReachabilityProbe {
    /**
     * @param localSocksPort the local port the SOCKS5 proxy listens on.
     * @param targetHost host to reach THROUGH the tunnel (the control node's private IP).
     * @param targetPort port to reach on [targetHost].
     * @return true only if the tunnel demonstrably carried traffic to the target and back.
     */
    fun isReachable(
        localSocksPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Boolean
}

/**
 * The production [TunnelReachabilityProbe]: a thin JDK wrapper that performs a real SOCKS5 CONNECT
 * through the local proxy to the target and reads back the first bytes.
 *
 * OpenSSH opens the local `-D` listener regardless of whether the remote side of the tunnel is
 * healthy, so a bare local TCP connect passes even against a dead tunnel. Routing an actual SOCKS5
 * CONNECT to the control node's own `sshd` — which is definitionally up because the tunnel rides on
 * it — and reading its immediate `SSH-2.0-...` identification string proves the full chain: local
 * listener → ssh tunnel → control node → onward TCP to a private IP → bytes back.
 */
class SocksTunnelReachabilityProbe(
    private val connectTimeoutMs: Int,
) : TunnelReachabilityProbe {
    override fun isReachable(
        localSocksPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Boolean =
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localSocksPort))
            Socket(proxy).use { socket ->
                socket.soTimeout = connectTimeoutMs
                socket.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                // Read the SSH banner to prove bytes flow BACK through the tunnel, not just that the
                // SOCKS CONNECT setup succeeded. sshd sends "SSH-2.0-..." immediately on connect.
                val buf = ByteArray(SSH_BANNER_PREFIX.length)
                val read = socket.getInputStream().read(buf)
                read >= SSH_BANNER_PREFIX.length && String(buf, 0, SSH_BANNER_PREFIX.length) == SSH_BANNER_PREFIX
            }
        } catch (e: Exception) {
            log.debug(e) { "SOCKS tunnel probe to $targetHost:$targetPort via 127.0.0.1:$localSocksPort failed" }
            false
        }
}
