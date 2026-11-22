package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * [recordedProcess] is what test cleanups use to kill a process known only by the PID it wrote to a
 * file. Unit tests run in parallel forks, so by cleanup time that PID can belong to another test's
 * process; these tests hold such a process and check the cleanup leaves it running.
 */
internal class RecordedProcessTest {
    @TempDir
    lateinit var dir: File

    private val started = mutableListOf<Process>()

    @AfterEach
    fun cleanUp() {
        started.forEach { it.destroyForcibly() }
    }

    @Test
    fun `a live process that took a recorded PID after the record was written is left running`() {
        val unrelated = start("sleep", "61")
        val record = File(dir, "tool.pid").apply { writeText("${unrelated.pid()}\n") }
        Files.setLastModifiedTime(record.toPath(), FileTime.from(Instant.now().minus(Duration.ofMinutes(1))))

        recordedProcess(record, "sleep 61")?.destroyForcibly()

        assertThat(unrelated.waitFor(SETTLE_MILLIS, TimeUnit.MILLISECONDS)).isFalse()
    }

    @Test
    fun `a live process with a recorded PID but another command is left running`() {
        val unrelated = start("sleep", "62")
        val record = File(dir, "tool.pid").apply { writeText("${unrelated.pid()}\n") }

        recordedProcess(record, "sleep 300")?.destroyForcibly()

        assertThat(unrelated.waitFor(SETTLE_MILLIS, TimeUnit.MILLISECONDS)).isFalse()
    }

    @Test
    fun `the process that recorded its own PID is the one returned`() {
        val record = File(dir, "tool.pid")
        val tool = start("/bin/sh", "-c", "echo \$\$ > \"$1\"; exec sleep 63", "tool", record.absolutePath)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIMIT_MILLIS)
        while (record.readTextOrNull()?.trim()?.toLongOrNull() == null && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)

        val handle = recordedProcess(record, "sleep 63")

        assertThat(handle?.pid()).isEqualTo(tool.pid())
    }

    @Test
    fun `a missing or empty record names no process`() {
        val empty = File(dir, "empty.pid").apply { writeText("") }

        assertThat(recordedProcess(File(dir, "missing.pid"), "sleep")).isNull()
        assertThat(recordedProcess(empty, "sleep")).isNull()
    }

    private fun start(vararg command: String): Process = ProcessBuilder(*command).start().also { started.add(it) }

    private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()

    private companion object {
        const val SETTLE_MILLIS = 300L
        const val LIMIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
