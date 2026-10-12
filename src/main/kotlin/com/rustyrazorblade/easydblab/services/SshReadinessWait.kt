package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.resilience4j.retry.Retry
import java.time.Duration

/**
 * `up`'s wait for SSH on freshly provisioned nodes: the control node and the database hosts must
 * answer a trivial command before setup goes on.
 *
 * The control node is included because the SOCKS tunnel and K3s setup both dial it next; confirming
 * it is reachable here, rather than discovering it is not mid-tunnel-setup, is what the database
 * hosts' version download alone never verified. It is its own class so `Up` stays a sequence of steps.
 *
 * @param startupDelay the pause before the first probe, giving freshly booted nodes time to start sshd
 * @param retryInterval the pause between probe attempts
 */
class SshReadinessWait(
    private val hostOperationsService: HostOperationsService,
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
    private val startupDelay: Duration,
    private val retryInterval: Duration,
) {
    /**
     * Waits until every control node and every database host matched by [databaseHostFilter] in
     * [hosts] answers over SSH, retrying while sshd starts.
     */
    fun await(
        hosts: Map<ServerType, List<ClusterHost>>,
        databaseHostFilter: String,
    ) {
        eventBus.emit(Event.Provision.SshWaiting)
        Thread.sleep(startupDelay.toMillis())

        val retry =
            Retry.of("ssh-connection", RetryUtil.createSshConnectionRetryConfig(retryInterval)).also {
                it.eventPublisher.onRetry { event ->
                    eventBus.emit(Event.Provision.SshRetrying(event.numberOfRetryAttempts, event.lastThrowable?.message))
                }
            }

        // Checked, because MINA's SshException (a refused connection while sshd starts) is a
        // checked IOException that the plain decorateRunnable never sees.
        Retry
            .decorateCheckedRunnable(retry) {
                // The control node is never what `--hosts` scopes — that filter targets db/app
                // nodes for scale-out. Applying it here would make `up --hosts db2` filter the
                // control check down to zero hosts (HostOperationsService.withHosts silently
                // no-ops on an empty match), verifying nothing and reopening the readiness gap the
                // control check exists to close. Always check control with no filter.
                checkSshReady(hosts, ServerType.Control, hostFilter = "")
                checkSshReady(hosts, ServerType.Cassandra, hostFilter = databaseHostFilter)
            }.run()
    }

    private fun checkSshReady(
        hosts: Map<ServerType, List<ClusterHost>>,
        serverType: ServerType,
        hostFilter: String,
    ) {
        hostOperationsService.withHosts(hosts, serverType, hostFilter) { clusterHost ->
            // Only that the host answers matters; echoing its output printed a bare "1" per host.
            remoteOps.executeRemotely(clusterHost.toHost(), "echo 1", output = false).text
        }
    }
}
