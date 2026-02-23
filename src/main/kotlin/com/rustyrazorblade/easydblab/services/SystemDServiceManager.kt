package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.oshai.kotlinlogging.KLogger

/**
 * Common interface for SystemD service management operations.
 *
 * This interface defines the standard lifecycle operations that all SystemD-based
 * services should support: start, stop, restart, status checking, and status retrieval.
 *
 * All operations return Result types for explicit error handling, making it easy to
 * handle failures gracefully in client code.
 */
interface SystemDServiceManager {
    /**
     * Starts the SystemD service on the specified host.
     *
     * @param host The host where the service should be started
     * @return Result indicating success or failure with exception details
     */
    fun start(host: Host): Result<Unit>

    /**
     * Stops the SystemD service on the specified host.
     *
     * @param host The host where the service should be stopped
     * @return Result indicating success or failure with exception details
     */
    fun stop(host: Host): Result<Unit>

    /**
     * Restarts the SystemD service on the specified host.
     *
     * This method can be overridden by implementations that need custom restart logic
     * (e.g., restart scripts that also wait for service readiness).
     *
     * @param host The host where the service should be restarted
     * @return Result indicating success or failure with exception details
     */
    fun restart(host: Host): Result<Unit>

    /**
     * Checks if the SystemD service is currently running on the specified host.
     *
     * @param host The host to check
     * @return Result containing true if the service is active, false if inactive or failed
     */
    fun isRunning(host: Host): Result<Boolean>

    /**
     * Gets the full systemd status output for the service on the specified host.
     *
     * This provides detailed status information including service state, recent logs,
     * and systemd metadata.
     *
     * @param host The host to query
     * @return Result containing the status output text
     */
    fun getStatus(host: Host): Result<String>
}

/**
 * Abstract base class providing common SystemD service management functionality.
 *
 * This class implements the shared logic for managing SystemD services via SSH,
 * eliminating code duplication across service-specific implementations. Each concrete
 * service only needs to specify its service name and can optionally override methods
 * for custom behavior.
 *
 * All operations use systemctl commands executed over SSH via RemoteOperationsService.
 * User-facing output is handled through OutputHandler, while detailed logging uses
 * the service-specific logger.
 *
 * Example usage:
 * ```
 * class MyService(
 *     remoteOps: RemoteOperationsService,
 *     eventBus: EventBus
 * ) : AbstractSystemDServiceManager("my-service", remoteOps, eventBus) {
 *     override val log: KLogger = KotlinLogging.logger {}
 *
 *     // Optionally override methods for custom behavior
 *     override fun restart(host: Host): Result<Unit> = runCatching {
 *         // Custom restart logic here
 *     }
 * }
 * ```
 *
 * @property serviceName The name of the SystemD service (e.g., "cassandra", "cassandra-sidecar")
 * @property remoteOps Service for executing SSH commands on remote hosts
 * @property eventBus Event bus for emitting structured domain events
 */
abstract class AbstractSystemDServiceManager(
    protected val serviceName: String,
    protected val remoteOps: RemoteOperationsService,
    protected val eventBus: EventBus,
) : SystemDServiceManager {
    /**
     * Logger instance for this service. Each concrete service should provide its own logger
     * to enable service-specific log filtering and configuration.
     */
    protected abstract val log: KLogger

    override fun start(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Service.Starting(serviceName, host.alias))

            remoteOps.executeRemotely(
                host,
                "sudo systemctl start $serviceName",
            )

            log.info { "Successfully started $serviceName on ${host.alias}" }
        }

    override fun stop(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Service.Stopping(serviceName, host.alias))

            remoteOps.executeRemotely(
                host,
                "sudo systemctl stop $serviceName",
            )

            log.info { "Successfully stopped $serviceName on ${host.alias}" }
        }

    open override fun restart(host: Host): Result<Unit> =
        runCatching {
            eventBus.emit(Event.Service.Restarting(serviceName, host.alias))

            remoteOps.executeRemotely(
                host,
                "sudo systemctl restart $serviceName",
            )

            log.info { "Successfully restarted $serviceName on ${host.alias}" }
        }

    override fun isRunning(host: Host): Result<Boolean> =
        runCatching {
            log.debug { "Checking if $serviceName is running on ${host.alias}" }

            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo systemctl is-active $serviceName",
                    output = false,
                )

            // systemctl is-active returns "active" if the service is running
            // Any other response (inactive, failed, etc.) means it's not running normally
            val isActive = response.text.trim().equals("active", ignoreCase = true)

            log.debug {
                if (isActive) {
                    "$serviceName is active on ${host.alias}"
                } else {
                    "$serviceName is not active on ${host.alias}: ${response.text.trim()}"
                }
            }

            isActive
        }

    override fun getStatus(host: Host): Result<String> =
        runCatching {
            log.debug { "Getting status of $serviceName on ${host.alias}" }

            val response =
                remoteOps.executeRemotely(
                    host,
                    "sudo systemctl status $serviceName",
                    output = false,
                )

            response.text
        }
}
