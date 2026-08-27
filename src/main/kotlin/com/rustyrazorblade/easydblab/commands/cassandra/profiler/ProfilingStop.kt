package com.rustyrazorblade.easydblab.commands.cassandra.profiler

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.profiling.DesiredProfilingState
import com.rustyrazorblade.easydblab.profiling.ProfilingConfig
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import picocli.CommandLine.Command
import java.time.Instant

/**
 * Disables profiling on the targeted Cassandra nodes.
 *
 * Writes an explicit disabled state rather than removing the desired-state document, so "profiling
 * is off" stays distinguishable from "nobody has configured this node". The reconciler then stops
 * the session cleanly, which finalizes the in-flight chunk so it still ships — retention and
 * shipping keep running for what is already on disk, under the bounds the operator chose rather
 * than under defaults.
 */
@McpCommand
@RequireProfileSetup
@RequireSSHKey
@Command(
    name = "stop",
    description = ["Disable continuous profiling on Cassandra nodes"],
)
class ProfilingStop : ProfilingHostCommand() {
    override fun execute() {
        forEachTarget { host ->
            profilingService.writeDesiredState(host, disabledStateFor(host))
            eventBus.emit(Event.Profiling.Stopped(host.alias))
        }
    }

    /**
     * The node's existing desired state with profiling switched off, and nothing else touched.
     *
     * Rebuilding the document from defaults instead would reset retention and the byte ceiling
     * behind the operator's back — so `start --retention 600` followed by `stop` would hand the
     * reconciler a 60-minute window and have it prune away exactly the profiles the operator
     * stopped in order to collect.
     *
     * When the node's document cannot be read there is nothing to preserve and the fallback happens
     * anyway — but it is announced, because a silent reset is indistinguishable from a clean stop
     * right up until the profiles are gone.
     */
    private fun disabledStateFor(host: Host): ProfilingConfig =
        when (val current = profilingService.readDesiredState(host)) {
            is DesiredProfilingState.Configured ->
                current.config.copy(
                    enabled = false,
                    updatedAt = Instant.now().toString(),
                )

            is DesiredProfilingState.Unreadable -> {
                eventBus.emit(
                    Event.Profiling.DesiredStateUnreadable(
                        host = host.alias,
                        retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
                        maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
                    ),
                )
                defaultDisabledState()
            }

            DesiredProfilingState.Unconfigured -> defaultDisabledState()
        }

    /** Profiling off, under the tool's own bounds — for a node with nothing worth preserving. */
    private fun defaultDisabledState(): ProfilingConfig =
        ProfilingConfig(
            enabled = false,
            asprofArgs = emptyList(),
            loopInterval = Constants.Profiling.DEFAULT_LOOP_INTERVAL,
            retentionMinutes = Constants.Profiling.DEFAULT_RETENTION_MINUTES,
            maxBytes = Constants.Profiling.DEFAULT_MAX_BYTES,
            pyroscopeUrl = pyroscopeIngestBaseUrl(controlNodePrivateIp()),
            clusterName = clusterState.clusterLabelName(),
            updatedAt = Instant.now().toString(),
        )
}
