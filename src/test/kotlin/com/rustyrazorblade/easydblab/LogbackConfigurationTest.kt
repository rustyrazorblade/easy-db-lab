package com.rustyrazorblade.easydblab

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the shipped logging configuration against re-opening a credential leak.
 *
 * Apache MINA logs every remote command verbatim at DEBUG, one layer below where this codebase
 * redacts, and a remote command legitimately carries a git URL with an embedded token. The root
 * logger runs at DEBUG into a file kept for 30 days, so the SSH transport's own logging has to be
 * held above DEBUG.
 */
class LogbackConfigurationTest {
    private fun shippedConfiguration(): LoggerContext {
        val context = LoggerContext()
        val configuration = requireNotNull(javaClass.classLoader.getResourceAsStream("easydblab-logback.xml"))
        JoranConfigurator().apply { setContext(context) }.doConfigure(configuration)
        return context
    }

    @Test
    fun `the ssh transport never logs at debug, where it would echo the raw remote command`() {
        val context = shippedConfiguration()

        assertThat(context.getLogger("org.apache.sshd").effectiveLevel).isEqualTo(Level.INFO)
        assertThat(context.getLogger("org.apache.sshd.client.channel.ChannelExec").isDebugEnabled).isFalse()
    }

    @Test
    fun `application logging still runs at debug`() {
        val context = shippedConfiguration()

        assertThat(context.getLogger("com.rustyrazorblade.easydblab").isDebugEnabled).isTrue()
    }
}
