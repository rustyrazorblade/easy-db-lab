package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for managing Cassandra database lifecycle operations.
 *
 * This service encapsulates all Cassandra lifecycle logic (start, stop, restart)
 * that was previously scattered across multiple command classes. It provides
 * a centralized interface for Cassandra operations on individual hosts.
 *
 * All operations return Result types for explicit error handling.
 */
interface CassandraService {
    /**
     * Starts the Cassandra service on the specified host.
     *
     * @param host The host where Cassandra should be started
     * @param wait If true (default), waits for the node to reach UP/NORMAL status after starting
     * @return Result indicating success or failure with exception details
     */
    fun start(
        host: Host,
        wait: Boolean = true,
    ): Result<Unit>

    /**
     * Stops the Cassandra service on the specified host.
     *
     * @param host The host where Cassandra should be stopped
     * @return Result indicating success or failure with exception details
     */
    fun stop(host: Host): Result<Unit>

    /**
     * Restarts the Cassandra service on the specified host and waits for it to be ready.
     *
     * @param host The host where Cassandra should be restarted
     * @return Result indicating success or failure with exception details
     */
    fun restart(host: Host): Result<Unit>

    /**
     * Waits for Cassandra to reach UP/NORMAL status on the specified host.
     *
     * This method blocks until the node reports UP/NORMAL status or times out.
     *
     * @param host The host to wait for
     * @return Result indicating success (node is up) or failure (timeout/error)
     */
    fun waitForUpNormal(host: Host): Result<Unit>

    /**
     * Checks if Cassandra is currently running on the specified host.
     *
     * @param host The host to check
     * @return Result containing true if Cassandra is active, false if inactive
     */
    fun isRunning(host: Host): Result<Boolean>
}

/**
 * Default implementation of CassandraService using SSH for remote operations.
 *
 * This implementation extends AbstractSystemDServiceManager to leverage common
 * systemd service management functionality while providing Cassandra-specific
 * customizations.
 *
 * Key customizations:
 * - start() method supports optional wait parameter for UP/NORMAL status
 * - restart() uses custom restart script that includes waiting logic
 * - Additional waitForUpNormal() method for explicit status waiting
 *
 * @property remoteOps Service for executing SSH commands on remote hosts
 */
class DefaultCassandraService(
    remoteOps: RemoteOperationsService,
    eventBus: EventBus,
) : AbstractSystemDServiceManager("cassandra", remoteOps, eventBus),
    CassandraService {
    override val log: KLogger = KotlinLogging.logger {}

    override fun start(
        host: Host,
        wait: Boolean,
    ): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cassandra.Starting(host.alias))

            remoteOps.executeRemotely(
                host,
                "sudo systemctl start $serviceName",
            )

            if (wait) {
                eventBus.emit(Event.Cassandra.StartedWaitingReady(host.alias))
                remoteOps.executeRemotely(
                    host,
                    "sudo wait-for-up-normal",
                )
                log.info { "${host.alias} is now UP/NORMAL" }
            } else {
                log.info { "Successfully started Cassandra on ${host.alias} (not waiting for UP/NORMAL)" }
            }
        }

    override fun restart(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cassandra.Restarting(host.alias))

            remoteOps.executeRemotely(
                host,
                "/usr/local/bin/restart-cassandra-and-wait",
            )

            log.info { "Successfully restarted Cassandra on ${host.alias}" }
        }

    override fun waitForUpNormal(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Cassandra.WaitingReady(host.alias))

            remoteOps.executeRemotely(
                host,
                "sudo wait-for-up-normal",
            )

            log.info { "${host.alias} is now UP/NORMAL" }
        }

    // stop() and isRunning() are inherited from AbstractSystemDServiceManager
    // getStatus() is also inherited but not exposed through CassandraService interface
}
