package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterS3Path
import com.rustyrazorblade.easydblab.events.Event
import java.time.Instant

/**
 * Central event dispatcher that receives domain events and fans them out to registered listeners.
 *
 * Usage:
 * ```kotlin
 * eventBus.emit(Event.Cassandra.Starting(host = "cassandra0"))
 * ```
 *
 * The bus reads [EventContext.current] for the command name, creates an [EventEnvelope]
 * with the current timestamp, and dispatches it to all registered [EventListener] instances.
 *
 * Thread-safe: the listener list is synchronized.
 */
class EventBus {
    private val listeners = mutableListOf<EventListener>()

    /**
     * Emit a domain event. The bus wraps it in an [EventEnvelope] with metadata
     * from [EventContext] and the current timestamp, then dispatches to all listeners.
     */
    fun emit(event: Event) {
        val envelope =
            EventEnvelope(
                event = event,
                timestamp = Instant.now().toString(),
                commandName = EventContext.current(),
            )
        val currentListeners = synchronized(listeners) { listeners.toList() }
        currentListeners.forEach { it.onEvent(envelope) }
    }

    /**
     * Register a listener to receive events.
     */
    fun addListener(listener: EventListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeListener(listener: EventListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Close all listeners and clear the list.
     */
    fun close() {
        val currentListeners =
            synchronized(listeners) {
                val copy = listeners.toList()
                listeners.clear()
                copy
            }
        currentListeners.forEach { it.close() }
    }

    /**
     * Display observability stack access information.
     * Used by GrafanaUpdateConfig (after deployment) and Status (for reference).
     */
    fun displayObservabilityAccess(controlNodeIp: String) {
        emit(
            Event.Provision.ObservabilityAccessInfo(
                controlNodeIp = controlNodeIp,
                grafanaPort = Constants.K8s.GRAFANA_PORT,
                victoriaMetricsPort = Constants.K8s.VICTORIAMETRICS_PORT,
                victoriaLogsPort = Constants.K8s.VICTORIALOGS_PORT,
                tempoPort = Constants.K8s.TEMPO_PORT,
                pyroscopePort = Constants.K8s.PYROSCOPE_PORT,
            ),
        )
    }

    /**
     * Display ClickHouse access information.
     * Used by Status to display ClickHouse connection details.
     * @param dbNodeIp IP address of a db node where ClickHouse pods are scheduled
     */
    fun displayClickHouseAccess(dbNodeIp: String) {
        emit(
            Event.Provision.ClickHouseAccessInfo(
                dbNodeIp,
                Constants.ClickHouse.HTTP_PORT,
                Constants.ClickHouse.NATIVE_PORT,
            ),
        )
    }

    /**
     * Display S3Manager access information linking to the cluster's S3 directory.
     * @param controlNodeIp IP address of the control node where S3Manager runs
     * @param s3Path The cluster's S3 path (provides bucket and key)
     */
    fun displayS3ManagerAccess(
        controlNodeIp: String,
        s3Path: ClusterS3Path,
    ) {
        emit(
            Event.Provision.S3ManagerAccessInfo(
                controlNodeIp,
                Constants.K8s.S3MANAGER_PORT,
                s3Path.bucket,
                s3Path.getKey(),
            ),
        )
    }

    /**
     * Display container registry access information with Jib push instructions.
     * @param controlNodeIp IP address of the control node where the registry runs
     * @param socksPort SOCKS5 proxy port (defaults to 1080)
     */
    fun displayRegistryAccess(
        controlNodeIp: String,
        socksPort: Int = Constants.Proxy.DEFAULT_SOCKS5_PORT,
    ) {
        emit(
            Event.Provision.RegistryAccessInfo(
                controlNodeIp,
                Constants.K8s.REGISTRY_PORT,
                socksPort,
            ),
        )
    }
}
