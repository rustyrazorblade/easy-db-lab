package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests for [PackerLogs]: every attempt of every build gets its own log directory, even for two
 * builds started in the same second, and both the directory and the log are readable by their
 * owner only, because an `ssm` build's log holds the session token.
 */
class PackerLogsTest {
    @TempDir
    lateinit var root: File

    private val logs by lazy {
        PackerLogs(File(root, "packer-logs"), Clock.fixed(Instant.parse("2026-11-20T04:05:06Z"), ZoneOffset.UTC))
    }

    private fun permissions(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `each attempt of each build logs to its own owner-only directory and file`() {
        val logsMade =
            listOf(
                logs.newAttempt("base.pkr.hcl", "aaaa11112222", 1),
                logs.newAttempt("base.pkr.hcl", "aaaa11112222", 2),
                // Another build in the same second, as two concurrent builds would be.
                logs.newAttempt("base.pkr.hcl", "bbbb33334444", 1),
            )

        assertThat(logsMade.map { it.parentFile.name }).containsExactly(
            "base-20261120-040506-aaaa11112222-attempt-1",
            "base-20261120-040506-aaaa11112222-attempt-2",
            "base-20261120-040506-bbbb33334444-attempt-1",
        )
        assertThat(logsMade).allSatisfy { log ->
            assertThat(log.name).isEqualTo("packer.log")
            assertThat(log).exists().isEmpty()
            assertThat(permissions(log.parentFile)).isEqualTo("rwx------")
            assertThat(permissions(log)).isEqualTo("rw-------")
        }
    }
}
