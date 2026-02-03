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
 * Default implementation using Java Driver v4.
 *
 * Always uses SOCKS proxy for reliable connections to Cassandra.
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
        private const val CQL_LOG_PREVIEW_LENGTH = 100
        private const val MIN_COLUMN_WIDTH = 10
        private const val HEX_DISPLAY_LIMIT = 16
    }

    // Cached session for reuse
    private var cachedSession: CqlSession? = null
    private var cachedDatacenter: String? = null
    private var registeredWithResourceManager = false

    override fun execute(cql: String): Result<String> =
        runCatching {
            val session = getOrCreateSession()
            log.debug { "Executing CQL: ${cql.take(CQL_LOG_PREVIEW_LENGTH)}..." }

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
                ?: error("No region/datacenter found in cluster state")

        // Ensure SOCKS proxy is running
        val gatewayHost = cassandraHosts.first()
        socksProxyService.ensureRunning(gatewayHost)

        // Return cached session if valid
        cachedSession?.let { session ->
            if (!session.isClosed && cachedDatacenter == datacenter) {
                return session
            }
            session.close()
        }

        // Get all private IPs
        val contactPoints = cassandraHosts.map { it.privateIp }

        // Create session through SOCKS proxy
        val proxyPort = socksProxyService.getLocalPort()
        log.info { "Creating new CqlSession through SOCKS proxy on port $proxyPort" }
        val session = sessionFactory.createSession(contactPoints, datacenter, proxyPort)

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
        sb.appendLine(headers.map { "-".repeat(it.length.coerceAtLeast(MIN_COLUMN_WIDTH)) }.joinToString("-+-"))

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
        return if (hex.length > HEX_DISPLAY_LIMIT) "0x${hex.take(HEX_DISPLAY_LIMIT)}..." else "0x$hex"
    }
}
