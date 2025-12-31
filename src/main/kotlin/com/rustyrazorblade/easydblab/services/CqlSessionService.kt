package com.rustyrazorblade.easydblab.services

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer

/**
 * Service for executing CQL queries against Cassandra.
 *
 * Uses the Java Driver v4 with SOCKS proxy for reliable execution through
 * the existing SSH tunnel infrastructure.
 *
 * Implements [AutoCloseable] so it can be registered with [ResourceManager]
 * for centralized cleanup.
 */
interface CqlSessionService : AutoCloseable {
    /**
     * Execute a CQL statement and return the formatted output.
     */
    fun execute(cql: String): Result<String>
}

/**
 * Default implementation using Java Driver v4 with SOCKS proxy.
 *
 * The session is cached for reuse in REPL/Server mode.
 * Registers with [ResourceManager] when session is created for centralized cleanup.
 */
class DefaultCqlSessionService(
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
    private val sessionFactory: CqlSessionFactory,
    private val resourceManager: ResourceManager,
) : CqlSessionService {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    // Cached session for reuse
    private var cachedSession: CqlSession? = null
    private var cachedDatacenter: String? = null
    private var registeredWithResourceManager = false

    override fun execute(cql: String): Result<String> =
        runCatching {
            val session = getOrCreateSession()
            log.debug { "Executing CQL: ${cql.take(100)}..." }

            val resultSet = session.execute(cql)
            formatResultSet(resultSet)
        }

    override fun close() {
        log.debug { "Closing CqlSession and SOCKS proxy" }
        cachedSession?.close()
        cachedSession = null
        cachedDatacenter = null
        registeredWithResourceManager = false
        // Also stop the SOCKS proxy to allow the JVM to exit
        socksProxyService.stop()
    }

    private fun getOrCreateSession(): CqlSession {
        val clusterState = clusterStateManager.load()
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()

        require(cassandraHosts.isNotEmpty()) { "No Cassandra hosts found in cluster state" }

        // Use the datacenter from the first host or default to region
        val datacenter =
            clusterState.initConfig?.region
                ?: throw IllegalStateException("No region/datacenter found in cluster state")

        // Return cached session if valid
        cachedSession?.let { session ->
            if (!session.isClosed && cachedDatacenter == datacenter) {
                return session
            }
            session.close()
        }

        // Ensure SOCKS proxy is running through the first Cassandra host
        val gatewayHost = cassandraHosts.first()
        val proxyState = socksProxyService.ensureRunning(gatewayHost)

        log.info { "Creating new CqlSession through SOCKS proxy on port ${proxyState.localPort}" }

        // Get all private IPs
        val contactPoints = cassandraHosts.map { it.privateIp }

        // Create and cache the session
        val session = sessionFactory.createSession(contactPoints, datacenter, proxyState.localPort)
        cachedSession = session
        cachedDatacenter = datacenter

        // Register with ResourceManager for centralized cleanup (only once)
        if (!registeredWithResourceManager) {
            resourceManager.register(this)
            registeredWithResourceManager = true
        }

        return session
    }

    private fun formatResultSet(resultSet: ResultSet): String {
        val columnDefinitions = resultSet.columnDefinitions
        if (columnDefinitions.size() == 0) {
            return "" // No results (e.g., for DDL statements)
        }

        val sb = StringBuilder()

        // Header
        val headers = (0 until columnDefinitions.size()).map { columnDefinitions[it].name.asCql(false) }
        sb.appendLine(headers.joinToString(" | "))
        sb.appendLine(headers.map { "-".repeat(it.length.coerceAtLeast(10)) }.joinToString("-+-"))

        // Rows
        for (row in resultSet) {
            val values =
                (0 until columnDefinitions.size()).map { i ->
                    formatValue(row.getObject(i))
                }
            sb.appendLine(values.joinToString(" | "))
        }

        return sb.toString().trimEnd()
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteBuffer -> formatByteBuffer(value)
            else -> value.toString()
        }

    private fun formatByteBuffer(buffer: ByteBuffer): String {
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return if (hex.length > 16) "0x${hex.take(16)}..." else "0x$hex"
    }
}
