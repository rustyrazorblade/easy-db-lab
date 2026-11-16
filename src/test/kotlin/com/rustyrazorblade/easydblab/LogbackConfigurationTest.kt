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
 * held above DEBUG. HTTP clients log raw headers and bodies (credentials included) on dedicated
 * wire and header loggers, which have to be off entirely.
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

    /**
     * HTTP wire and header logging echoes request headers and bodies verbatim: the SSO bearer
     * token, the role credentials the SSO portal returns, Authorization headers, and the account
     * ID in query strings. None of it may reach a log file at any level.
     */
    @Test
    fun `http wire and header loggers write nothing at any level`() {
        val context = shippedConfiguration()

        listOf(
            "org.apache.http.wire",
            "org.apache.http.headers",
            "org.apache.hc.client5.http.wire",
            "org.apache.hc.client5.http.headers",
            "org.apache.hc.core5.http2.frame",
            "org.apache.hc.core5.http2.frame.payload",
            "software.amazon.awssdk.request",
            // The SigV4 canonical request carries the x-amz-security-token header's value.
            "software.amazon.awssdk.http.auth.aws.internal.signer.DefaultV4RequestSigner",
            "software.amazon.awssdk.auth.signer",
            // Request lines carry the query string: the SSO credentials call's account_id.
            "org.apache.hc.client5.http.impl.classic.MainClientExec",
            "org.apache.hc.client5.http.impl.async.HttpAsyncMainClientExec",
            "org.apache.http.impl.execchain.MainClientExec",
            "okhttp3.OkHttpClient",
            "io.fabric8.kubernetes.client.http.HttpLoggingInterceptor",
            "io.netty.handler.logging",
        ).forEach { name ->
            assertThat(context.getLogger(name).isEnabledFor(Level.ERROR)).describedAs(name).isFalse()
        }
    }

    /** Silencing the wire must not silence the rest of a library's debug logging. */
    @Test
    fun `http clients' other debug logging is kept`() {
        val context = shippedConfiguration()

        assertThat(context.getLogger("org.apache.hc.client5.http.impl.io.DefaultManagedHttpClientConnection").isDebugEnabled).isTrue()
        assertThat(context.getLogger("software.amazon.awssdk.auth").isDebugEnabled).isTrue()
    }

    @Test
    fun `application logging still runs at debug`() {
        val context = shippedConfiguration()

        assertThat(context.getLogger("com.rustyrazorblade.easydblab").isDebugEnabled).isTrue()
    }
}
