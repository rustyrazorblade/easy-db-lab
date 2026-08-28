package com.rustyrazorblade.easydblab.ssh

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A git URL is a normal way to reach a private fork, and it can carry credentials in its userinfo.
 * Those must never survive into a log line, an exception message, or a serialized event.
 */
class RedactionTest {
    @Test
    fun `redacts userinfo credentials from a url`() {
        val redacted = redactUrlCredentials("git clone https://alice:ghp_secrettoken@github.com/acme/cassandra.git")

        assertThat(redacted).doesNotContain("ghp_secrettoken").doesNotContain("alice")
        assertThat(redacted).isEqualTo("git clone https://***@github.com/acme/cassandra.git")
    }

    @Test
    fun `redacts a single-field token, the documented GitHub PAT form`() {
        val redacted = redactUrlCredentials("git clone https://ghp_secrettoken@github.com/acme/cassandra.git")

        assertThat(redacted).doesNotContain("ghp_secrettoken")
        assertThat(redacted).isEqualTo("git clone https://***@github.com/acme/cassandra.git")
    }

    @Test
    fun `redacts the x-access-token form`() {
        val redacted = redactUrlCredentials("https://x-access-token:ghp_secrettoken@github.com/acme/cassandra.git")

        assertThat(redacted).doesNotContain("ghp_secrettoken").doesNotContain("x-access-token")
        assertThat(redacted).isEqualTo("https://***@github.com/acme/cassandra.git")
    }

    @Test
    fun `redacts every url in a multi-line string`() {
        val text =
            """
            ERROR: clone failed for https://bob:hunter2@git.example.com/x.git
            retrying https://bob:hunter2@git.example.com/x.git
            """.trimIndent()

        assertThat(redactUrlCredentials(text)).doesNotContain("hunter2")
        assertThat(redactUrlCredentials(text).lines()).allMatch { it.contains("***@") }
    }

    @Test
    fun `leaves a scp-style git url alone`() {
        // git@github.com is a username, not a credential — redacting it would be noise
        val url = "git@github.com:acme/cassandra.git"

        assertThat(redactUrlCredentials(url)).isEqualTo(url)
    }

    @Test
    fun `leaves urls without credentials alone`() {
        val command = "install-cassandra-version 5.0 --url https://example.com/apache-cassandra-5.0-bin.tar.gz"

        assertThat(redactUrlCredentials(command)).isEqualTo(command)
    }

    @Test
    fun `leaves text with no url alone`() {
        assertThat(redactUrlCredentials("sudo use-cassandra 5.0")).isEqualTo("sudo use-cassandra 5.0")
    }
}
