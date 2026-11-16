package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.shellQuote
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A file mode written as octal digits, as `chmod` takes it: `0644`, `755`, `0600`. */
private val FILE_MODE = Regex(Constants.Ssh.FILE_MODE_PATTERN)

/**
 * Puts a local file at [remotePath] on [host] with [mode].
 *
 * SSH logs in as an unprivileged user, so a file bound for a system path (`/etc`, `/usr/local/bin`)
 * is uploaded to the login directory first and then moved with `sudo`. This is that sequence, once:
 * the file is staged under a name no other upload can share (two installs of the same file name,
 * from one run or two, never overwrite each other's stage), given [mode] while still staged, and
 * then moved with `sudo mv`. The mode is set before the move so the file appears at its path
 * already readable by whoever needs it: a reader that polls the path never sees the mode the
 * upload happened to give it. Both paths are shell-quoted. A failed `chmod` or `mv` removes the
 * staged copy (it can hold a secret) and throws, as any remote command does.
 *
 * Declared as an extension, not a member of [RemoteOperationsService], so that every implementation
 * and every test double of the interface gets it without having to implement it.
 *
 * @param mode octal digits for `chmod`, such as `0755` for a script or `0600` for a secret
 * @throws IllegalArgumentException if [mode] is not octal digits
 */
fun RemoteOperationsService.installFile(
    host: Host,
    source: Path,
    remotePath: String,
    mode: String,
) {
    require(FILE_MODE.matches(mode)) { "File mode must be octal digits such as 0644, got '$mode'" }
    val staged = "${Constants.Ssh.INSTALL_STAGING_PREFIX}${UUID.randomUUID()}-${remotePath.substringAfterLast('/')}"
    upload(host, source, staged)
    val stagedPath = staged.shellQuote()
    val target = remotePath.shellQuote()
    // A failed chmod or mv removes the staged copy, which may hold a secret, then fails the command.
    executeRemotely(
        host,
        "sudo chmod $mode $stagedPath && sudo mv $stagedPath $target || { rm -f $stagedPath; exit 1; }",
        output = false,
    )
}

/**
 * [installFile] for a classpath resource, such as a script packaged in the distribution.
 *
 * The resource is copied to a temporary local file for the upload, which is deleted afterwards.
 *
 * @param resourcePath absolute classpath path, such as `/com/rustyrazorblade/easydblab/commands/x.sh`
 * @throws IllegalStateException if the resource does not exist
 */
fun RemoteOperationsService.installFile(
    host: Host,
    resourcePath: String,
    remotePath: String,
    mode: String,
) {
    val content =
        RemoteOperationsService::class.java.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
    val local = Files.createTempFile("edl-install-", "-${remotePath.substringAfterLast('/')}")
    try {
        content.use { input -> Files.newOutputStream(local).use { output -> input.copyTo(output) } }
        installFile(host, local, remotePath, mode)
    } finally {
        Files.deleteIfExists(local)
    }
}
