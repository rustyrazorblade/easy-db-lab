package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.stream.Stream

/**
 * A settings.yaml that does not parse must never put its own text into an error: the file holds
 * the AWS secret key, the AxonOps key and the Tailscale client secret, and the error reaches the
 * debug log and its archives.
 */
class UserConfigProviderRedactionTest {
    @TempDir
    lateinit var profileDir: File

    /**
     * A document that does not load as a [User].
     *
     * @param mapFails whether reading it as a plain map fails too; a value of the wrong type is
     *   valid YAML, so only the typed read rejects it
     */
    class Malformed(
        private val name: String,
        val text: String,
        val mapFails: Boolean = true,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private const val SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        private const val SECRET_FRAGMENT_LENGTH = 12
        private const val VALID_HEAD =
            "email: a@b.c\nregion: us-west-2\nkeyName: k\nawsProfile: \"\"\nawsAccessKey: AKIAX\n"

        /** Every way of breaking a document that has a secret on one of its lines. */
        @JvmStatic
        fun malformedDocuments(): Stream<Malformed> =
            listOf(
                Malformed("unterminated quote on awsSecret", "${VALID_HEAD}awsSecret: \"$SECRET\n"),
                Malformed("unterminated quote on axonOpsKey", "${VALID_HEAD}awsSecret: x\naxonOpsKey: \"$SECRET\n"),
                Malformed(
                    "unterminated quote on tailscaleClientSecret",
                    "${VALID_HEAD}awsSecret: x\ntailscaleClientSecret: '$SECRET\n",
                ),
                Malformed("bad indentation after the secret", "${VALID_HEAD}awsSecret: $SECRET\n   bad: [\n"),
                Malformed(
                    "unclosed mapping holding the secret",
                    "${VALID_HEAD}awsSecret: x\nsshTransport: {a: $SECRET\n",
                ),
                Malformed(
                    "secret in a value of the wrong type",
                    "${VALID_HEAD}awsSecret: [$SECRET, {x: $SECRET}]\n",
                    mapFails = false,
                ),
            ).stream()
    }

    private val settingsFile get() = File(profileDir, "settings.yaml")

    private fun provider(text: String): UserConfigProvider {
        settingsFile.writeText(text)
        return UserConfigProvider(profileDir)
    }

    /** The stack trace and every message and localized message down the cause chain. */
    private fun Throwable.everything(): String {
        val trace = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()
        val messages =
            generateSequence(this) { it.cause }
                .joinToString("\n") { "${it.message} ${it.localizedMessage}" }
        return "$trace\n$messages"
    }

    private fun assertRedacted(failure: Throwable?) {
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).startsWith("Cannot read ${settingsFile.path}")
        assertThat(failure?.everything().orEmpty())
            .doesNotContain(SECRET)
            .doesNotContain(SECRET.take(SECRET_FRAGMENT_LENGTH))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedDocuments")
    fun `getUserConfig fails with a message that holds no text of the document`(document: Malformed) {
        val provider = provider(document.text)

        assertRedacted(catchThrowable { provider.getUserConfig() })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedDocuments")
    fun `loadExistingConfig fails with a message that holds no text of the document`(document: Malformed) {
        val provider = provider(document.text)

        if (document.mapFails) {
            assertRedacted(catchThrowable { provider.loadExistingConfig() })
        } else {
            assertThatCode { provider.loadExistingConfig() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `a value of the wrong type is reported by field name`() {
        val provider = provider("${VALID_HEAD}awsSecret: [$SECRET]\n")

        val failure = catchThrowable { provider.getUserConfig() }

        assertRedacted(failure)
        assertThat(failure.message).contains("field awsSecret")
    }

    @Test
    fun `a parser error names the line and carries no cause`() {
        val provider = provider("${VALID_HEAD}awsSecret: \"$SECRET\n")

        val failure = catchThrowable { provider.getUserConfig() }

        assertRedacted(failure)
        assertThat(failure.message).containsPattern(" at line \\d+, column \\d+")
        assertThat(failure.cause).isNull()
    }

    @Test
    fun `a validation failure from our own code keeps its message and is the cause`() {
        val provider = provider("${VALID_HEAD}awsSecret: $SECRET\nsshTransport: tunnel\n")

        val failure = catchThrowable { provider.getUserConfig() }

        assertRedacted(failure)
        assertThat(failure.message).contains("'tunnel'").contains("direct, ssm")
        assertThat(failure.cause).isExactlyInstanceOf(IllegalArgumentException::class.java)
    }
}
