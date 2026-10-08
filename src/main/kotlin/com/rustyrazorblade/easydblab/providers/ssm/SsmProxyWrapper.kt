package com.rustyrazorblade.easydblab.providers.ssm

import java.io.File

/**
 * Installs the `edl-ssm-proxy` wrapper that every `ssm` ProxyCommand runs through.
 *
 * When ssh exits or is killed, the AWS CLI does not end the `session-manager-plugin` it started,
 * and a plugin whose WebSocket is stuck does not notice its stdin closing, so both survive,
 * re-parented to PID 1. The wrapper runs the `aws ssm start-session` command and ends its whole
 * process tree when ssh goes away. It is a packaged resource written into the profile directory,
 * so it works from an installed distribution without a source checkout.
 *
 * @param directory where the wrapper is written; the profile directory in production
 */
class SsmProxyWrapper(
    private val directory: File,
) {
    /**
     * The wrapper's absolute path, writing it first when it is missing or out of date.
     *
     * @throws IllegalStateException if the packaged resource is missing from the distribution
     */
    fun install(): String {
        val script = requireNotNull(javaClass.getResource(RESOURCE)) { "Missing packaged resource $RESOURCE" }.readText()
        val target = File(directory, FILE_NAME)
        if (!target.isFile || target.readText() != script) {
            directory.mkdirs()
            val staged = File(directory, "$FILE_NAME.tmp")
            staged.writeText(script)
            check(staged.setExecutable(true, true)) { "Could not make ${staged.path} executable" }
            check(staged.renameTo(target)) { "Could not move ${staged.path} to ${target.path}" }
        }
        return target.absolutePath
    }

    companion object {
        /** The packaged wrapper script. */
        const val RESOURCE = "/com/rustyrazorblade/easydblab/ssm/edl-ssm-proxy.sh"

        /** The file name the wrapper is installed under. */
        const val FILE_NAME = "edl-ssm-proxy"
    }
}
