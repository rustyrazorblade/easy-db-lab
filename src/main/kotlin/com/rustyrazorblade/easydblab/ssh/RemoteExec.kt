package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.Constants
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.util.io.output.NoCloseOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = KotlinLogging.logger {}

/**
 * Runs one remote command and returns when it ends, writing its output to the given streams. A
 * non-zero exit throws [java.rmi.RemoteException]. [SSHClient] does its redaction, streaming and
 * failure reporting around this call, so tests can drive that logic with a fake.
 */
fun interface RemoteExec {
    fun run(
        command: String,
        stdout: OutputStream,
        stderr: OutputStream,
    )
}

/**
 * [RemoteExec] on an exec channel of [session] that an interrupt stops, remote process included.
 *
 * MINA's `ClientSession.executeRemoteCommand` waits in `ChannelExec.waitFor`, which catches an
 * [InterruptedException], drops it and keeps waiting, so Ctrl-C in the REPL could not stop a
 * running command: it ran to its end. This waits on the channel's close instead.
 *
 * Closing the channel alone does not stop the remote process: the channel has no pty, so sshd
 * sends it no SIGHUP. An interrupt therefore sends the RFC 4254 `signal` request `INT`, then
 * `KILL` if the command is still running after [signalGrace], and closes the channel once the
 * command ends or another [signalGrace] passes. OpenSSH delivers a signal to the command's process
 * group, and refuses it for a root login; nodes log in as `ubuntu`.
 * The [InterruptedException] is then rethrown. An interrupt while the channel opens is rethrown as
 * an [InterruptedException] too. The session stays open for the next command.
 */
class ChannelRemoteExec(
    private val session: ClientSession,
    private val signalGrace: Duration = Duration.ofSeconds(Constants.Ssh.INTERRUPT_SIGNAL_GRACE_SECONDS),
) : RemoteExec {
    override fun run(
        command: String,
        stdout: OutputStream,
        stderr: OutputStream,
    ) {
        session.createExecChannel(command, Charset.defaultCharset(), null, emptyMap<String, Any>()).use { channel ->
            // MINA closes a channel's streams with it; the caller owns these and closes them itself.
            channel.setOut(NoCloseOutputStream(stdout))
            channel.setErr(NoCloseOutputStream(stderr))
            val closed = CompletableFuture<Unit>()
            channel.addCloseFutureListener { closed.complete(Unit) }
            openInterruptibly(channel)
            try {
                closed.get()
            } catch (e: InterruptedException) {
                stopRemoteCommand(channel, closed)
                throw e
            }
            ClientChannel.validateCommandExitStatusCode(command, channel.exitStatus)
        }
    }

    /** Opens [channel]. MINA reports an interrupt of its open wait as an [InterruptedIOException]. */
    private fun openInterruptibly(channel: ChannelExec) {
        try {
            channel.open().verify()
        } catch (e: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while opening the exec channel").apply { initCause(e) }
        }
    }

    /**
     * Signals the remote command to stop, `INT` and then `KILL`, and closes [channel]. An interrupt
     * during the grace waits (a second Ctrl-C) skips straight to the close.
     */
    private fun stopRemoteCommand(
        channel: ChannelExec,
        closed: CompletableFuture<Unit>,
    ) {
        try {
            for (signal in STOP_SIGNALS) {
                if (closed.isDone) break
                sendSignal(channel, signal)
                awaitClose(closed)
            }
        } catch (_: InterruptedException) {
            // Already stopping on the first interrupt; the caller rethrows that one.
        } finally {
            channel.close(true)
        }
    }

    private fun awaitClose(closed: CompletableFuture<Unit>) {
        try {
            closed.get(signalGrace.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Still running: the next signal, or the close.
        }
    }

    /** Sends the RFC 4254 `signal` channel request [name] (without the `SIG` prefix); no reply is asked for. */
    private fun sendSignal(
        channel: ChannelExec,
        name: String,
    ) {
        val buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST)
        buffer.putUInt(channel.recipient)
        buffer.putString(SIGNAL_REQUEST)
        buffer.putBoolean(false)
        buffer.putString(name)
        try {
            channel.writePacket(buffer)
        } catch (e: IOException) {
            // The channel closed under us: the command has ended, and the close below is a no-op.
            log.debug(e) { "Could not send signal $name to the remote command" }
        }
    }

    private companion object {
        const val SIGNAL_REQUEST = "signal"
        val STOP_SIGNALS = listOf("INT", "KILL")
    }
}
