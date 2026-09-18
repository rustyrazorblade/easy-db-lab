package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventContext
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command

/**
 * Base class for PicoCLI commands that need remote operations.
 *
 * Provides injected services for SSH operations, cluster state, and event bus.
 * Most commands extend this class to get access to common infrastructure services.
 *
 * All dependencies are injected via Koin, including Context. Commands are instantiated
 * by KoinCommandFactory which ensures proper dependency injection.
 *
 * Automatically pushes/pops [EventContext] around command execution so that events
 * emitted during a command carry the correct command name. When commands call other
 * commands (e.g., Init calls Up), the stack ensures the innermost command name is reported.
 */
abstract class PicoBaseCommand :
    PicoCommand,
    KoinComponent {
    /** Injected Context for accessing configuration and state directories. */
    protected val context: Context by inject()

    /** Injected RemoteOperationsService for SSH operations. */
    protected val remoteOps: RemoteOperationsService by inject()

    /** Injected EventBus for emitting structured domain events. */
    protected val eventBus: EventBus by inject()

    /** Injected ClusterStateManager for cluster state management. */
    protected val clusterStateManager: ClusterStateManager by inject()

    /** Convenience property to get the current ClusterState. */
    protected val clusterState by lazy { clusterStateManager.load() }

    /**
     * Refuses to run a command that depends on the local observability stack when the cluster is in
     * telemetry-redirect mode. A redirect cluster has no local VictoriaMetrics, VictoriaLogs, Tempo,
     * Pyroscope, or Grafana — that data lives on the external stack — so a command that reads or
     * reconfigures those backends has nothing here to act on. It refuses cleanly rather than failing
     * later with an opaque connection error.
     *
     * @param commandName the user-facing command name, used in the message (e.g. "metrics ls").
     */
    protected fun requireLocalTelemetryStack(commandName: String) {
        if (clusterState.initConfig?.telemetryRedirect != null) {
            eventBus.emit(Event.Provision.RedirectCommandUnavailable(commandName))
            error("$commandName is unavailable on a telemetry-redirect cluster.")
        }
    }

    /**
     * Wraps the PicoCommand lifecycle with EventContext push/pop.
     * The command name is derived from the @Command annotation.
     */
    override fun call(): Int {
        val commandName = this::class.java.getAnnotation(Command::class.java)?.name
        if (commandName != null) {
            EventContext.push(commandName)
        }
        try {
            return super.call()
        } finally {
            if (commandName != null) {
                EventContext.pop()
            }
        }
    }
}
