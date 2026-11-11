package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.RetryUtil
import com.rustyrazorblade.easydblab.providers.aws.isSshNotReady
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.github.resilience4j.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * `up`'s wait for SSH on freshly provisioned nodes: the control node and the database hosts must
 * answer a trivial command before setup goes on.
 *
 * The control node is included because the SOCKS tunnel and K3s setup both dial it next; confirming
 * it is reachable here, rather than discovering it is not mid-tunnel-setup, is what the database
 * hosts' version download alone never verified. It is its own class so `Up` stays a sequence of steps.
 *
 * At 1,000 nodes the wait probes the hosts in parallel on [HostOperationsService]'s bounded pool,
 * probes only the hosts that have not answered yet, and is bounded by [timeout] of wall-clock time
 * rather than a count of sweeps, which would stretch with the number of hosts.
 *
 * @param startupDelay the pause before the first probe, giving freshly booted nodes time to start sshd
 * @param retryInterval the pause between probe attempts
 * @param timeout how long, in all, the hosts may take to answer
 */
class SshReadinessWait(
    private val hostOperationsService: HostOperationsService,
    private val remoteOps: RemoteOperationsService,
    private val eventBus: EventBus,
    private val startupDelay: Duration,
    private val retryInterval: Duration,
    private val timeout: Duration = Duration.ofSeconds(Constants.Scale.SSH_READY_TIMEOUT_SECONDS),
) {
    /**
     * Waits until every control node and every database host matched by [databaseHostFilter] in
     * [hosts] answers over SSH, retrying while sshd starts.
     *
     * @throws IllegalStateException naming the hosts that never answered, once [timeout] is spent
     */
    fun await(
        hosts: Map<ServerType, List<ClusterHost>>,
        databaseHostFilter: String,
    ) {
        eventBus.emit(Event.Provision.SshWaiting)
        Thread.sleep(startupDelay.toMillis())

        // The control node is never what `--hosts` scopes — that filter targets db/app nodes for
        // scale-out. Applying it here would make `up --hosts db2` filter the control check down to
        // zero hosts, verifying nothing and reopening the readiness gap the control check closes.
        val targets =
            hostOperationsService.filteredHosts(hosts, ServerType.Control) +
                hostOperationsService.filteredHosts(hosts, ServerType.Cassandra, databaseHostFilter)
        val deadline = Instant.now().plus(timeout)

        var pending: List<ClusterHost> = targets
        var lastError: String? = null
        val retry =
            Retry.of("ssh-readiness", readinessRetryConfig(deadline)).also {
                it.eventPublisher.onRetry { event ->
                    eventBus.emit(Event.Provision.SshRetrying(event.numberOfRetryAttempts, lastError))
                }
            }

        val stillPending =
            Retry
                .decorateCheckedSupplier(retry) {
                    val failures = probe(pending)
                    lastError = failures.values.firstOrNull()?.message
                    pending = pending.filter { it in failures }
                    pending
                }.get()

        check(stillPending.isEmpty()) {
            val names = stillPending.map { it.alias }
            "SSH did not come up within ${timeout.toSeconds()}s on ${names.size} host(s): " +
                names.joinToString(
                    ", ",
                    limit = Constants.Scale.MAX_NAMED_IN_ERROR,
                    truncated = "and ${names.size - Constants.Scale.MAX_NAMED_IN_ERROR} more",
                ) +
                lastError?.let { ". Last error: ${it.lineSequence().first()}" }.orEmpty()
        }
    }

    /**
     * Probes [hosts] in parallel and returns the ones whose SSH is not up yet, with why. A failure
     * that waiting will not clear is thrown at once.
     */
    private fun probe(hosts: List<ClusterHost>): Map<ClusterHost, Throwable> {
        val results =
            hostOperationsService.collectFromHosts(hosts, parallel = true) {
                // Only that the host answers matters; echoing its output printed a bare "1" per host.
                remoteOps.executeRemotely(it.toHost(), "echo 1", output = false).text
            }
        val failures = results.mapNotNull { r -> r.result.exceptionOrNull()?.let { r.host to it } }
        failures.firstOrNull { (_, e) -> !isSshNotReady(e) }?.let { (_, e) -> throw e }
        return failures.toMap()
    }

    /**
     * Repeats a sweep [retryInterval] apart while hosts are pending and [deadline] has not passed.
     * A wall-clock deadline, not an attempt count, bounds the wait, so it does not change with how
     * long one sweep over 1,000 hosts takes. A thrown sweep is never retried: [probe] already
     * returned every host that is only not up yet, so what it throws is a hard SSH failure.
     */
    private fun readinessRetryConfig(deadline: Instant) =
        RetryUtil.createPollUntilRetryConfig<List<ClusterHost>>(
            maxAttempts = Int.MAX_VALUE,
            interval = retryInterval,
            done = { it.isEmpty() },
            deadline = deadline,
            retryExceptions = false,
        )
}
