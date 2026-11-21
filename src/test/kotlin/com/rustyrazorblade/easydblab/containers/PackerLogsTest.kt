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
 * Tests for [PackerLogs]: every attempt of a build gets its own log directory, readable by its
 * owner only, because an `ssm` build's log holds the session token.
 */
class PackerLogsTest {
    @TempDir
    lateinit var root: File

    private val logs by lazy {
        PackerLogs(File(root, "packer-logs"), Clock.fixed(Instant.parse("2026-11-20T04:05:06Z"), ZoneOffset.UTC))
    }

    @Test
    fun `each attempt of a build logs to its own owner-only directory`() {
        val first = logs.newAttempt("base.pkr.hcl", 1)
        val second = logs.newAttempt("base.pkr.hcl", 2)

        assertThat(first.parentFile).isNotEqualTo(second.parentFile)
        assertThat(first.parentFile.name).isEqualTo("base-20261120-040506-attempt-1")
        assertThat(listOf(first, second)).allSatisfy { log ->
            assertThat(log.name).isEqualTo("packer.log")
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log.parentFile.toPath())))
                .isEqualTo("rwx------")
        }
    }
}
