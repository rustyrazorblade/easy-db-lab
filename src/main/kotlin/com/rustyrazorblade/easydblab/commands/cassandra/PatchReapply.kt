package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.commands.mixins.HostsMixin
import com.rustyrazorblade.easydblab.services.CommandExecutor

/**
 * Applies `cassandra.patch.yaml` again on [hosts] after their conf directory changed (a version
 * switch or a reinstall): `download-config`, then `update-config`, both limited to [hosts], because
 * each host's conf directory is the one of the version it runs. The executor reports a failed step
 * as an exit code, never as an exception.
 *
 * @return the name of the step that failed, or null when both succeeded
 */
internal fun reapplyPatch(
    commandExecutor: CommandExecutor,
    hosts: HostsMixin,
): String? =
    when {
        commandExecutor.execute { DownloadConfig().apply { this.hosts = hosts } } != 0 -> "download-config"
        commandExecutor.execute { UpdateConfig().apply { this.hosts = hosts } } != 0 -> "update-config"
        else -> null
    }
