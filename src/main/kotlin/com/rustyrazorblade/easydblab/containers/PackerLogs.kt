package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.Constants
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where each `packer build` attempt writes its debug log on the host.
 *
 * Every attempt gets its own directory under [root], named by the template, the time, the build id
 * and the attempt, so a retried build keeps the log of the attempt that lost its session and two
 * builds started in the same second never share one. Under the `ssm` transport the log holds the
 * Session Manager session token, so the directory and the log file are readable by their owner
 * only: the file is created owner-only before Packer opens it, and Packer appends to it.
 */
class PackerLogs(
    private val root: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern(Constants.Packer.LOG_DIR_TIMESTAMP_PATTERN).withZone(ZoneOffset.UTC)

    /**
     * Creates the owner-only directory and the empty owner-only log file for [attempt] of the build
     * [buildId] of [template], and returns the log file.
     */
    fun newAttempt(
        template: String,
        buildId: String,
        attempt: Int,
    ): File {
        val name = "${template.substringBefore('.')}-${stamp.format(clock.instant())}-$buildId-attempt-$attempt"
        val dir = File(root, name)
        Files.createDirectories(root.toPath())
        Files.createDirectory(dir.toPath(), ownerOnly(Constants.Packer.HOST_LOG_DIR_PERMISSIONS))
        val log = File(dir, Constants.Packer.LOG_FILE_NAME)
        Files.createFile(log.toPath(), ownerOnly(Constants.Packer.HOST_LOG_FILE_PERMISSIONS))
        return log
    }

    private fun ownerOnly(permissions: String) = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
}
