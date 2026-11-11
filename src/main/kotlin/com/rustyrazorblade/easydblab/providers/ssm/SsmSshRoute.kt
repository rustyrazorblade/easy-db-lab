package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.providers.ssh.SshEndpoint
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The `ssm` SSH transport: every SSH connection travels through AWS Systems Manager Session
 * Manager, so no inbound port on the node has to be reachable from this machine.
 *
 * OpenSSH gets an `AWS-StartSSHSession` `ProxyCommand` per host. The in-process client (Apache
 * MINA SSHD) cannot run a `ProxyCommand`, so it dials a loopback port that an
 * `AWS-StartPortForwardingSession` process forwards to the node's sshd. There is one forwarding
 * process per instance, kept for the life of the JVM. A forward whose process has died, for
 * example after an SSM idle timeout, is replaced on the next request, so the SSH provider's
 * existing stale-session reconnect recovers without help.
 *
 * A forward that fails to start throws [SsmForwardNotReadyException], an unchecked exception, so
 * `up`'s SSH-readiness retry and the SSH operations retry both see it. That covers the window
 * before a fresh node's SSM agent registers.
 *
 * Forwarding processes are stopped by [close] and by a JVM shutdown hook, because nothing stops
 * the SSH provider explicitly when a command ends. Both reach a process from the moment it is
 * spawned, not only once it is ready. Stopping a forward also stops its descendants: the AWS CLI
 * hands each session to a `session-manager-plugin` child, which would otherwise outlive it.
 *
 * Killing those processes does not end the session in Session Manager, which would stay
 * "Connected" until its 20-minute idle timeout. Every stop (close, the shutdown hook, replacing a
 * dead forward, the ready-timeout kill, an interrupted start) therefore also calls TerminateSession
 * for the session ID the CLI printed, bounded by [terminateTimeout]. A failed call is logged and
 * never replaces the error that caused the stop.
 *
 * @param commands builds the `start-session` command lines
 * @param sshPort the port sshd listens on, on every node
 * @param sessionTerminator ends a stopped forward's session on the AWS side
 * @param terminateTimeout how long a stop waits for those TerminateSession calls
 * @param readyTimeout how long a new forwarding session gets to report ready
 * @param freeLocalPort picks the loopback port for a new forwarding session
 * @param startProcess spawns a forwarding process; a parameter so tests can observe each process
 */
class SsmSshRoute(
    private val commands: SsmSessionCommandBuilder,
    private val sshPort: Int,
    private val sessionTerminator: SsmSessionTerminator,
    private val terminateTimeout: Duration = Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS),
    private val readyTimeout: Duration = Duration.ofSeconds(Constants.Ssm.PORT_FORWARD_READY_TIMEOUT_SECONDS),
    private val freeLocalPort: () -> Int = ::ephemeralLocalPort,
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
) : SshRoute {
    /**
     * One `AWS-StartPortForwardingSession` process and what is known about it: the loopback port
     * it listens on, its recent output, its Session Manager session ID once the CLI prints it,
     * whether it has reported ready, and whether this route asked it to stop. The last tells a
     * forward that died on its own from one that was stopped.
     */
    private class Forward(
        val instanceId: String,
        val process: Process,
        val localPort: Int,
    ) {
        val transcript = Transcript(Constants.Ssm.TRANSCRIPT_MAX_LINES)
        val ready = CompletableFuture<Unit>()
        val stopRequested = AtomicBoolean(false)

        @Volatile
        var sessionId: String? = null

        @Volatile
        var drain: Thread? = null
    }

    /** Ready forwards, keyed by instance ID. */
    private val forwards = ConcurrentHashMap<String, Forward>()

    /** Forwards spawned but not yet in [forwards], so a stop can reach a process that is still starting. */
    private val starting: MutableSet<Forward> = ConcurrentHashMap.newKeySet()

    // Per-instance locks let forwards to different nodes start concurrently, while two threads
    // asking for the same node share one session.
    private val startLocks = ConcurrentHashMap<String, Any>()

    private val shutdownHook = thread(start = false, name = "ssm-ssh-route-shutdown") { stopAll() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun endpoint(host: Host): SshEndpoint = SshEndpoint(Constants.Ssm.LOCAL_FORWARD_ADDRESS, localPortFor(instanceIdOf(host)))

    override fun proxyCommand(host: Host): String = commands.sshSession(instanceIdOf(host)).toShellCommand()

    override val tunnelVerifyAttempts: Int = Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS

    /**
     * 30s instead of MINA's 120s: key exchange and auth through the loopback forward take about
     * 1-1.5s when healthy, and a forward that accepts TCP but carries nothing should be retired
     * (see [invalidate]) and replaced quickly rather than held for two minutes per attempt.
     */
    override val authTimeout: Duration = Duration.ofSeconds(Constants.Ssm.SSH_AUTH_TIMEOUT_SECONDS)

    /**
     * Stops [host]'s forward, ending its session, so the next [endpoint] starts a new one. The SSH
     * layer calls this when a connection through the forward failed: a forward whose process is
     * alive but carries no SSH data would otherwise be handed out again and again.
     */
    override fun invalidate(host: Host) {
        val instanceId = instanceIdOf(host)
        synchronized(startLocks.computeIfAbsent(instanceId) { Any() }) {
            val forward = forwards.remove(instanceId) ?: return
            log.info { "SSM port forward to $instanceId on local port ${forward.localPort} failed an SSH connection; stopping it" }
            stop(listOf(forward))
        }
    }

    override fun close() {
        stopAll()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down, and the hook is running or has run.
        }
    }

    /**
     * A host without an instance ID cannot be targeted. This fails rather than falling back to a
     * direct connection, which on a network that needs SSM would only fail later and less clearly.
     */
    private fun instanceIdOf(host: Host): String {
        require(host.instanceId.isNotBlank()) {
            "Host ${host.alias} has no EC2 instance ID, so it cannot be reached over SSM Session Manager"
        }
        return host.instanceId
    }

    private fun localPortFor(instanceId: String): Int {
        synchronized(startLocks.computeIfAbsent(instanceId) { Any() }) {
            val existing = forwards[instanceId]
            if (existing != null && existing.process.isAlive) return existing.localPort
            if (existing != null) {
                log.info {
                    "SSM port forward to $instanceId on local port ${existing.localPort} has exited " +
                        "(exit code ${existing.process.exitValue()}); starting a new one"
                }
                forwards.remove(instanceId, existing)
                stop(listOf(existing))
            }
            val forward = start(instanceId)
            forwards[instanceId] = forward
            starting.remove(forward)
            return forward.localPort
        }
    }

    /**
     * Spawns a forward and waits for it to report ready. The forward is registered in [starting]
     * the moment it exists, and any failure while waiting, an interrupt included, stops it and its
     * descendants before the failure propagates.
     *
     * @throws SsmForwardNotReadyException if the session exits or times out before it is ready
     */
    private fun start(instanceId: String): Forward {
        // Gets the AWS client and credentials ready for TerminateSession while this forward starts.
        sessionTerminator.warmUp()
        val localPort = freeLocalPort()
        val command = commands.portForwardSession(instanceId, sshPort, localPort)
        log.info { "Starting SSM port forward ${Constants.Ssm.LOCAL_FORWARD_ADDRESS}:$localPort -> $instanceId:$sshPort" }

        // stdin stays an open, unwritten pipe: a port-forwarding session must not see EOF on it.
        val builder =
            ProcessBuilder(command.argv)
                .redirectErrorStream(true)
                .apply { environment().putAll(command.environment) }
        val forward = Forward(instanceId, startProcess(builder), localPort)
        starting.add(forward)

        var ready = false
        try {
            drainOutput(forward)
            awaitReady(forward)
            ready = true
            return forward
        } catch (e: InterruptedException) {
            // The caller asked this thread to stop; keep that visible after the process is stopped.
            Thread.currentThread().interrupt()
            throw e
        } finally {
            // A finally rather than a catch, so an Error or an interrupt stops the process too.
            if (!ready) {
                starting.remove(forward)
                stop(listOf(forward))
            }
        }
    }

    private fun awaitReady(forward: Forward) {
        try {
            forward.ready.get(readyTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw SsmForwardNotReadyException(
                forward.instanceId,
                forward.transcript.text(),
                "SSM port forward to ${forward.instanceId}:$sshPort did not become ready within ${readyTimeout.toMillis()} ms.",
                e,
            )
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Reads the session's output for its whole life, completing [Forward.ready] when the plugin
     * reports it is listening, or failing it if the process exits first. Draining continues after
     * the session is ready, so the plugin never blocks writing a line about an accepted connection.
     * A ready forward that exits without being asked to is logged, since every SSH connection
     * riding it drops at that moment.
     */
    private fun drainOutput(forward: Forward) {
        val instanceId = forward.instanceId
        forward.drain =
            thread(isDaemon = true, name = "ssm-port-forward-$instanceId") {
                try {
                    forward.process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            forward.transcript.add(line)
                            log.debug { "[ssm $instanceId] $line" }
                            if (line.startsWith(Constants.Ssm.SESSION_ID_MARKER)) {
                                forward.sessionId = line.removePrefix(Constants.Ssm.SESSION_ID_MARKER).trim()
                            }
                            if (line.contains(Constants.Ssm.PORT_FORWARD_READY_MARKER)) forward.ready.complete(Unit)
                        }
                    }
                } catch (e: IOException) {
                    log.debug(e) { "Stopped reading SSM port forward output for $instanceId" }
                }
                val exitCode = runCatching { forward.process.waitFor() }.getOrNull()
                val wasReady =
                    !forward.ready.completeExceptionally(
                        SsmForwardNotReadyException(
                            instanceId,
                            forward.transcript.text(),
                            "SSM port forward to $instanceId:$sshPort exited (exit code $exitCode) before it was ready.",
                        ),
                    )
                if (wasReady && !forward.stopRequested.get()) {
                    log.warn {
                        "SSM port forward to $instanceId on local port ${forward.localPort} exited " +
                            "(exit code $exitCode) after it was ready. Session Manager plugin output:\n" +
                            forward.transcript.text()
                    }
                }
            }
    }

    /**
     * Stops every forward, ready or still starting. Each ready forward is removed by its own key
     * before it is stopped, so a forward another thread inserts meanwhile is never dropped from
     * the map without being stopped.
     */
    private fun stopAll() {
        val ready = forwards.keys.mapNotNull { forwards.remove(it) }
        stop(ready + starting.toList())
    }

    /**
     * Signals every process and its descendants at once, then waits on one shared deadline before
     * killing stragglers, and ends each forward's Session Manager session. A pending interrupt is
     * set aside while waiting and restored afterwards, so a stop on an interrupted thread still
     * waits for the processes to exit and still ends their sessions.
     */
    private fun stop(toStop: List<Forward>) {
        toStop.forEach { it.stopRequested.set(true) }
        val handles = toStop.flatMap { it.process.descendants().toList() + it.process.toHandle() }
        handles.forEach { it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Constants.Ssm.PROCESS_STOP_GRACE_SECONDS)
        var interrupted = Thread.interrupted()
        handles.forEach { handle ->
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                handle.destroyForcibly()
            } catch (_: ExecutionException) {
                handle.destroyForcibly()
            } catch (_: InterruptedException) {
                interrupted = true
                handle.destroyForcibly()
            }
        }
        // The drain thread reads the session ID; let it finish the output the process left behind.
        toStop.forEach { forward ->
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            runCatching { forward.drain?.join(remaining.coerceAtLeast(1)) }.onFailure { interrupted = true }
        }
        if (!endSessions(toStop.mapNotNull { it.sessionId })) interrupted = true
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Calls TerminateSession for every ID at once and waits up to [terminateTimeout] in total. Each
     * call runs on a daemon thread, so one that never answers holds neither this stop nor JVM exit.
     *
     * @return false if this thread was interrupted while waiting
     */
    private fun endSessions(sessionIds: List<String>): Boolean {
        val calls =
            sessionIds.map { sessionId ->
                sessionId to
                    thread(isDaemon = true, name = "ssm-terminate-$sessionId") {
                        runCatching { sessionTerminator.terminate(sessionId) }
                            .onSuccess { log.info { "Ended SSM session $sessionId" } }
                            .onFailure { e ->
                                log.warn(e) { "Could not end SSM session $sessionId; it ends at Session Manager's idle timeout" }
                            }
                    }
            }
        val deadline = System.nanoTime() + terminateTimeout.toNanos()
        return calls.all { (sessionId, call) ->
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0))
            val joined = runCatching { call.join(remaining.coerceAtLeast(1)) }.isSuccess
            if (joined && call.isAlive) {
                log.warn { "TerminateSession for SSM session $sessionId did not answer within ${terminateTimeout.toMillis()} ms" }
            }
            joined
        }
    }

    /** The most recent lines of a session's output, kept for error messages. */
    private class Transcript(
        private val maxLines: Int,
    ) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(line)
            if (lines.size > maxLines) lines.removeFirst()
        }

        @Synchronized
        fun text(): String = lines.joinToString("\n").ifEmpty { "(no output)" }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/** An OS-assigned free port on the loopback interface. Another process could take it before it is used, which is an accepted risk. */
private fun ephemeralLocalPort(): Int = ServerSocket(0).use { it.localPort }
