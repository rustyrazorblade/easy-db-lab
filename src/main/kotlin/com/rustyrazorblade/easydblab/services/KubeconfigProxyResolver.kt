package com.rustyrazorblade.easydblab.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Result of resolving the kubeconfig a kit shell step should hand to local `kubectl`/`helm`.
 *
 * Carries the path to use plus ownership of any throwaway copy that must be removed once the
 * command finishes. Implements [AutoCloseable] so the invoking command can wrap resolution in a
 * `use { }` block and guarantee cleanup on both the success and failure paths.
 */
class ResolvedKubeconfig internal constructor(
    val path: File,
    private val temporary: File?,
) : AutoCloseable {
    override fun close() {
        temporary?.delete()
    }
}

/**
 * Produces the KUBECONFIG that local `kubectl`/`helm` binaries — invoked by kit `type: shell`
 * steps on the developer's machine — should use.
 *
 * On a SOCKS-only cluster (Tailscale disabled) the workspace kubeconfig points at the control
 * node's private IP with no proxy, so a laptop-local kubectl/helm cannot reach the private
 * Kubernetes API and every shell step that waits on kubectl spins forever. When the SOCKS5
 * tunnel is published ([Constants.Proxy.PORT_PROPERTY] is set), this writes a throwaway copy of
 * the workspace kubeconfig with a `proxy-url: socks5://127.0.0.1:<port>` on each cluster entry,
 * so kubectl/helm route through the existing tunnel — and *only* kubectl/helm. The kubeconfig
 * `proxy-url` field is per-client, so `aws`/`curl`/anything else the shell step runs stays
 * direct (avoiding the #725 AWS-through-SOCKS failure class).
 *
 * The JVM-global `socksProxyHost`/`socksProxyPort` properties are never set, cleared, or touched
 * (see [Constants.Proxy.PORT_PROPERTY] and networking REQ-NET-005). The canonical workspace
 * kubeconfig is never patched in place — fabric8's proxied client reads that same S3-backed file
 * — only a derived temp copy is ever written.
 *
 * When no proxy port is published (Tailscale active, or no proxy running), the workspace
 * kubeconfig is returned unchanged, byte-for-byte identical to the prior behavior.
 */
class KubeconfigProxyResolver {
    private val yamlMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()

    /**
     * Resolves the kubeconfig for a shell step against [workspaceKubeconfig].
     *
     * Returns the workspace path unchanged when no SOCKS port is published or the workspace
     * kubeconfig does not exist; otherwise returns a temp copy carrying the SOCKS `proxy-url`.
     */
    fun resolve(workspaceKubeconfig: File): ResolvedKubeconfig {
        val port = System.getProperty(Constants.Proxy.PORT_PROPERTY)?.toIntOrNull()
        if (port == null || !workspaceKubeconfig.isFile) {
            return ResolvedKubeconfig(path = workspaceKubeconfig, temporary = null)
        }

        @Suppress("UNCHECKED_CAST")
        val kubeconfigMap = yamlMapper.readValue(workspaceKubeconfig, Map::class.java) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val clusters = kubeconfigMap["clusters"] as? List<Map<String, Any>>
        val proxiedClusters =
            clusters?.count { cluster ->
                @Suppress("UNCHECKED_CAST")
                val clusterData = cluster["cluster"] as? MutableMap<String, Any>
                if (clusterData == null) {
                    false
                } else {
                    clusterData["proxy-url"] = "socks5://127.0.0.1:$port"
                    true
                }
            } ?: 0
        // Fail fast on a malformed kubeconfig: silently returning a copy with no proxy-url would
        // leave laptop kubectl/helm unable to reach the private API, hanging pod-wait loops — the
        // exact failure this resolver exists to prevent.
        check(proxiedClusters > 0) {
            "Workspace kubeconfig ${workspaceKubeconfig.absolutePath} has no cluster entry to attach a " +
                "SOCKS proxy-url to; cannot route kubectl/helm through the tunnel"
        }

        val tempKubeconfig = File.createTempFile("edl-kubeconfig-proxy-", ".yaml")
        tempKubeconfig.deleteOnExit()
        yamlMapper.writeValue(tempKubeconfig, kubeconfigMap)
        log.debug { "Wrote proxied kubeconfig ${tempKubeconfig.absolutePath} routing kubectl/helm through SOCKS port $port" }
        return ResolvedKubeconfig(path = tempKubeconfig, temporary = tempKubeconfig)
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
