package com.rustyrazorblade.easydblab.services

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.driver.CqlSessionFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.access.CassandraPodForward
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
 * What a single-endpoint CQL session needs on a cluster reached by port-forward: the forward to one
 * Cassandra pod and that pod's datacenter.
 *
 * @property forward the port-forward the session dials as its only node
 * @property datacenter the forwarded pod's datacenter, the driver's local datacenter
 */
class SingleEndpointCql(
    val forward: CassandraPodForward,
    val datacenter: String,
)

/**
 * Default implementation using Java Driver v4.
 *
 * On a cluster reached by port-forward ([Exposure.PortForward]) the session talks to one Cassandra
 * pod through [singleEndpoint]'s forward, which is checked before every statement and reopened on the
 * same port when it has died, so the session keeps its only node. Otherwise it follows the recorded
 * exposure: [Exposure.Direct] connects to every node with no proxy, [Exposure.Socks] goes through the
 * SOCKS tunnel, as on AWS.
 *
 * The session is cached for reuse in REPL/Server mode.
 * Registers with [ResourceManager] when session is created for centralized cleanup.
 *
 * @param singleEndpoint builds the forward for a port-forwarded cluster, on its first use only
 */
class DefaultCqlSessionService(
    private val socksProxyService: SocksProxyService,
    private val clusterStateManager: ClusterStateManager,
    private val sessionFactory: CqlSessionFactory,
    private val resourceManager: ResourceManager,
    private val singleEndpoint: () -> SingleEndpointCql = { error("Single-endpoint CQL is not configured") },
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
    private var openedSingleEndpoint: SingleEndpointCql? = null

    override fun execute(cql: String): Result<String> =
        runCatching {
            val session = getOrCreateSession()
            log.debug { "Executing CQL: ${cql.take(CQL_LOG_PREVIEW_LENGTH)}..." }

            val statement =
                SimpleStatement
                    .newInstance(cql)
                    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
            val resultSet = session.execute(statement)
            formatResultSet(resultSet)
        }

    override fun close() {
        log.debug { "Closing CqlSession" }
        cachedSession?.close()
        cachedSession = null
        cachedDatacenter = null
        registeredWithResourceManager = false
        openedSingleEndpoint?.forward?.close()
        openedSingleEndpoint = null
    }

    private fun getOrCreateSession(): CqlSession {
        val clusterState = clusterStateManager.load()
        if (clusterState.exposure == Exposure.PortForward) return getOrCreateSingleEndpointSession()
        val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()

        require(cassandraHosts.isNotEmpty()) { "No Cassandra hosts found in cluster state" }

        val datacenter =
            clusterState.initConfig?.region
                ?: error("No region/datacenter found in cluster state")

        // Return cached session if valid
        cachedSession?.let { session ->
            if (!session.isClosed && cachedDatacenter == datacenter) {
                return session
            }
            session.close()
        }

        val contactPoints = cassandraHosts.map { it.privateIp }

        val session =
            when (clusterState.exposure) {
                Exposure.Direct -> {
                    log.info { "Creating new CqlSession via direct connection" }
                    sessionFactory.createDirectSession(contactPoints, datacenter)
                }
                Exposure.Socks -> {
                    val proxyPort = socksProxyService.getLocalPort()
                    log.info { "Creating new CqlSession through SOCKS proxy on port $proxyPort" }
                    sessionFactory.createSession(contactPoints, datacenter, proxyPort)
                }
                Exposure.PortForward -> error("A port-forwarded cluster uses the single-endpoint session")
            }

        cachedSession = session
        cachedDatacenter = datacenter
        registerOnce()

        return session
    }

    private fun getOrCreateSingleEndpointSession(): CqlSession {
        val target = openedSingleEndpoint ?: singleEndpoint().also { openedSingleEndpoint = it }
        // Reopens a dead forward on the same port before the statement, so the cached session reconnects.
        val endpoint = target.forward.endpoint()
        cachedSession?.let { session ->
            if (!session.isClosed && cachedDatacenter == target.datacenter) return session
            session.close()
        }
        log.info { "Creating new CqlSession on the port-forward at $endpoint" }
        val session = sessionFactory.createSingleEndpointSession(endpoint, target.datacenter)
        cachedSession = session
        cachedDatacenter = target.datacenter
        registerOnce()
        return session
    }

    private fun registerOnce() {
        if (!registeredWithResourceManager) {
            resourceManager.register(this)
            registeredWithResourceManager = true
        }
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
        sb.appendLine(headers.joinToString("-+-") { "-".repeat(it.length.coerceAtLeast(MIN_COLUMN_WIDTH)) })

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
