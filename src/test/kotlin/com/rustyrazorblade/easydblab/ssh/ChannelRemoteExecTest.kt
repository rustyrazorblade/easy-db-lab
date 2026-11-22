package com.rustyrazorblade.easydblab.ssh

import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.client.session.ClientSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException

/**
 * [ChannelRemoteExec]'s handling of an interrupt that lands while the channel opens, which MINA
 * reports as an [InterruptedIOException]. The wait on a running command is covered against a real
 * sshd in `SSHClientStreamingIntegrationTest`.
 */
class ChannelRemoteExecTest {
    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `an interrupt while the channel opens ends the call as an interrupt, with the flag kept`() {
        val openFailure = InterruptedIOException("open interrupted")
        val opening = mock<OpenFuture> { on { verify() } doThrow openFailure }
        val channel = mock<ChannelExec> { on { open() } doReturn opening }
        val session = mock<ClientSession> { on { createExecChannel(any<String>(), any(), anyOrNull(), any()) } doReturn channel }

        assertThatThrownBy { ChannelRemoteExec(session).run("sleep 60", null, ByteArrayOutputStream(), ByteArrayOutputStream()) }
            .isInstanceOf(InterruptedException::class.java)
            .hasCause(openFailure)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }
}
