package com.rustyrazorblade.easydblab.compactorlease

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * The [Gate] as files in a directory the lease container shares with the compactor containers.
 *
 * - [VALID_UNTIL] holds the grant as epoch seconds and a newline. It is written to a temporary file
 *   and renamed over the old one, so a gate never reads a half-written time; a revoke removes it.
 *   The time is wall-clock, compared with the same node's wall clock, so a clock step on the node
 *   moves the window; [LeaseTiming] says how much step the margin tolerates.
 * - [READY] exists once the agent has read the lease; the lease container's readiness probe checks it.
 *
 * @property dir the shared directory, an emptyDir in the pod.
 */
class GateFile(
    private val dir: Path,
) : Gate {
    companion object {
        /** The file `compactor-gate.sh` reads. */
        const val VALID_UNTIL = "valid-until"

        /** The file the lease container's readiness probe checks. */
        const val READY = "ready"

        private val WORLD_READABLE = PosixFilePermissions.fromString("rw-r--r--")
    }

    override fun grant(validUntil: Instant) {
        // A temporary file is created owner-only; the Loki and Tempo gates run as another user.
        val temporary = Files.createTempFile(dir, ".$VALID_UNTIL", ".tmp")
        Files.writeString(temporary, "${validUntil.epochSecond}\n")
        Files.setPosixFilePermissions(temporary, WORLD_READABLE)
        Files.move(temporary, dir.resolve(VALID_UNTIL), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun revoke() {
        Files.deleteIfExists(dir.resolve(VALID_UNTIL))
    }

    /**
     * Removes what a previous run of the container left: a grant it no longer renews, and a
     * readiness this run has not earned yet.
     */
    fun reset() {
        revoke()
        Files.deleteIfExists(dir.resolve(READY))
    }

    override fun ready() {
        Files.writeString(dir.resolve(READY), "")
    }
}
