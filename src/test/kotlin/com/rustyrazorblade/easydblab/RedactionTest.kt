package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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

    /** Only the userinfo that [redactUrlCredentials] writes counts; a live credential or a stray `***@` does not. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "https://***@github.com/acme/cassandra.git, true",
        "https://tok3n@github.com/acme/cassandra.git, false",
        "https://github.com/acme/cassandra.git, false",
        "https://github.com/acme/***@cassandra.git, false",
        "git@github.com:acme/cassandra.git, false",
    )
    fun `a url has redacted credentials only when its userinfo is the redaction marker`(
        url: String,
        expected: Boolean,
    ) {
        assertThat(hasRedactedCredentials(url)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "tailscale up --authkey=tskey-auth-abc123 --hostname db0 | tailscale up --authkey=*** --hostname db0",
            "curl ?token=ghp_abc&x=1 | curl ?token=*** ",
            "SECRET=hunter2 PASSWORD=p4ss | SECRET=*** PASSWORD=***",
            "api key=abcd1234 | api key=***",
            "Authorization: Bearer eyJhbGciOi.payload | Authorization: ***",
            "authorization: Basic dXNlcjpwYXNz rest | authorization: *** rest",
            "aws_access_key_id=AKIAABCDEFGHIJKLMNOP | aws_access_key_id=***",
            "id AKIAABCDEFGHIJKLMNOP in use | id *** in use",
            "https://u:p@h/x | https://***@h/x",
        ],
    )
    fun `redact removes every kind of credential a node line can carry`(
        input: String,
        expected: String,
    ) {
        assertThat(redact(input)).isEqualTo(expected.trimEnd())
    }

    @Test
    fun `redact leaves ordinary output alone`() {
        val line = "Discarding blocks...Done. keyspace=system monkey=1 tokens 256"

        assertThat(redact(line)).isEqualTo(line)
    }

    @Test
    fun `every url redaction changes is then recognized as redacted`() {
        val withCredentials =
            listOf("https://a:b@h/x", "https://tok@h/x", "s3://key:secret@bucket/x", "http://x-access-token:t@h:8080/r.git")

        withCredentials.forEach { assertThat(hasRedactedCredentials(redactUrlCredentials(it))).describedAs(it).isTrue() }
    }
}
