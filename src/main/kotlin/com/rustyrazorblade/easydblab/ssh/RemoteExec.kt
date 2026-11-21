package com.rustyrazorblade.easydblab.ssh

import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.util.io.output.NoCloseOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

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
 * [RemoteExec] on an exec channel of [session] whose wait an interrupt ends.
 *
 * MINA's `ClientSession.executeRemoteCommand` waits in `ChannelExec.waitFor`, which catches an
 * [InterruptedException], drops it and keeps waiting, so Ctrl-C in the REPL could not stop a
 * running command: it ran to its end. This waits on the channel's close instead. An interrupt
 * closes the channel at once, which ends the remote command's session channel, and is rethrown.
 * The session stays open for the next command.
 */
class ChannelRemoteExec(
    private val session: ClientSession,
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
            channel.open().verify()
            try {
                closed.get()
            } catch (e: InterruptedException) {
                channel.close(true)
                throw e
            }
            ClientChannel.validateCommandExitStatusCode(command, channel.exitStatus)
        }
    }
}
