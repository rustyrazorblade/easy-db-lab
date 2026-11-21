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
 * Every attempt gets its own directory under [root], so a retried build keeps the log of the attempt
 * that lost its session, and the log is what classifies the failure. The directory is readable by
 * its owner only: under the `ssm` transport the log holds the Session Manager session token.
 */
class PackerLogs(
    private val root: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        const val LOG_FILE_NAME = "packer.log"
    }

    /** Creates the owner-only directory for [attempt] of the build of [template], and returns its log file. */
    fun newAttempt(
        template: String,
        attempt: Int,
    ): File {
        val name = "${template.substringBefore('.')}-${stamp.format(clock.instant())}-attempt-$attempt"
        val dir = File(root, name)
        Files.createDirectories(root.toPath())
        Files.createDirectory(
            dir.toPath(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(Constants.Packer.HOST_LOG_DIR_PERMISSIONS)),
        )
        return File(dir, LOG_FILE_NAME)
    }
}
