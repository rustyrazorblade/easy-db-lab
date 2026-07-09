package com.rustyrazorblade.easydblab.services.sql

import java.util.Properties

/**
 * Routes a JDBC connection through the easy-db-lab SOCKS5 tunnel when one is active.
 *
 * On a SOCKS-only cluster (no Tailscale) the database nodes are reachable only through the
 * `ssh -D` tunnel on `127.0.0.1:<port>`. The JDBC drivers connect straight to the node's private
 * IP, so without SOCKS routing every `<kit> sql` query fails with `Connection reset`
 * (see easy-db-lab#735). This configurer injects the correct per-driver SOCKS knob so the socket
 * traverses the tunnel — and, crucially, does so per-connection rather than via the JVM-global
 * `socksProxyHost`/`socksProxyPort` system properties, which would also capture the AWS SDK's
 * sockets and break direct AWS access.
 *
 * Driver support differs, so the knob is keyed on the JDBC URL scheme:
 * - **trino / presto**: the `socksProxy=host:port` connection property (their HTTP client honours it).
 * - **mysql**: the `socksProxyHost` / `socksProxyPort` connection properties (Connector/J native support).
 * - anything else (**postgresql**, **clickhouse**, …): no per-connection knob exists, so the caller
 *   must fall back to scoping the JVM-global `socksProxyHost`/`socksProxyPort` around the connect
 *   call ([requiresGlobalFallback] returns true) — safe for a run-and-exit `sql` command.
 */
object JdbcSocksProxyConfigurer {
    private const val SOCKS_HOST = "127.0.0.1"

    /** JDBC URL schemes whose drivers accept the Trino-style `socksProxy=host:port` property. */
    private val TRINO_STYLE_SCHEMES = setOf("trino", "presto")

    /** JDBC URL schemes whose drivers accept `socksProxyHost` / `socksProxyPort` connection properties. */
    private val HOST_PORT_PROP_SCHEMES = setOf("mysql")

    /**
     * Returns the JDBC [properties] augmented with the SOCKS proxy knob appropriate for [scheme],
     * pointed at `127.0.0.1:[port]`. Returns the properties unchanged when the driver has no
     * per-connection knob (callers detect that case via [requiresGlobalFallback]).
     *
     * The input [properties] is not mutated; a copy is returned.
     */
    fun applyToProperties(
        scheme: String,
        properties: Properties,
        port: Int,
    ): Properties {
        val result = Properties()
        result.putAll(properties)
        when (scheme.lowercase()) {
            in TRINO_STYLE_SCHEMES -> result.setProperty("socksProxy", "$SOCKS_HOST:$port")
            in HOST_PORT_PROP_SCHEMES -> {
                result.setProperty("socksProxyHost", SOCKS_HOST)
                result.setProperty("socksProxyPort", "$port")
            }
            // Other schemes have no per-connection knob — see requiresGlobalFallback().
        }
        return result
    }

    /**
     * True when [scheme]'s driver has no per-connection SOCKS knob, so the only way to route it
     * through the tunnel is the JVM-global `socksProxyHost`/`socksProxyPort` properties. The caller
     * should set those only for the duration of the connect call and clear them immediately after.
     */
    fun requiresGlobalFallback(scheme: String): Boolean {
        val s = scheme.lowercase()
        return s !in TRINO_STYLE_SCHEMES && s !in HOST_PORT_PROP_SCHEMES
    }
}
