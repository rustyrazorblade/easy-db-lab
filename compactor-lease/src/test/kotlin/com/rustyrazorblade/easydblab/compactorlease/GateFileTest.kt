package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.time.Instant

/** The files the gate script and the lease container's readiness probe read. */
class GateFileTest {
    @TempDir
    lateinit var dir: Path

    private val gate by lazy { GateFile(dir) }

    @Test
    fun `a grant writes the valid-until time in epoch seconds and leaves no temporary file`() {
        gate.grant(Instant.parse("2026-11-07T18:01:00Z"))

        assertThat(Files.readString(dir.resolve(GateFile.VALID_UNTIL))).isEqualTo("1794074460\n")
        assertThat(Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }).containsExactly(GateFile.VALID_UNTIL)
    }

    /** The loop retries a failed grant every renewal, so a leftover temporary file would pile up in the emptyDir. */
    @Test
    fun `a grant whose move fails throws and removes its temporary file`() {
        Files.createDirectories(dir.resolve(GateFile.VALID_UNTIL).resolve("occupied"))

        assertThatThrownBy { gate.grant(Instant.fromEpochSeconds(100)) }.isInstanceOf(IOException::class.java)

        assertThat(Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }).containsExactly(GateFile.VALID_UNTIL)
    }

    @Test
    fun `the valid-until file is readable by every user, since the Loki and Tempo gates run as 10001`() {
        gate.grant(Instant.fromEpochSeconds(100))

        assertThat(Files.getPosixFilePermissions(dir.resolve(GateFile.VALID_UNTIL)))
            .contains(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)
    }

    @Test
    fun `a reset removes the grant and the readiness a restarted container left behind`() {
        gate.grant(Instant.fromEpochSeconds(100))
        gate.ready()

        gate.reset()

        assertThat(dir.resolve(GateFile.VALID_UNTIL)).doesNotExist()
        assertThat(dir.resolve(GateFile.READY)).doesNotExist()
    }

    @Test
    fun `a later grant replaces the earlier one`() {
        gate.grant(Instant.fromEpochSeconds(100))

        gate.grant(Instant.fromEpochSeconds(200))

        assertThat(Files.readString(dir.resolve(GateFile.VALID_UNTIL))).isEqualTo("200\n")
    }

    @Test
    fun `a revoke removes the valid-until file`() {
        gate.grant(Instant.fromEpochSeconds(100))

        gate.revoke()

        assertThat(dir.resolve(GateFile.VALID_UNTIL)).doesNotExist()
    }

    @Test
    fun `a revoke with nothing granted succeeds`() {
        gate.revoke()

        assertThat(dir.resolve(GateFile.VALID_UNTIL)).doesNotExist()
    }

    @Test
    fun `ready writes the readiness file`() {
        gate.ready()

        assertThat(dir.resolve(GateFile.READY)).exists()
    }
}
