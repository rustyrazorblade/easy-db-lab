package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import java.io.File

/**
 * The kit directory one `kit install <name> --source <dir>` call names, read from the raw
 * command-line arguments.
 *
 * A dynamic install subcommand takes its options from the kit's `kit.yaml`, so the parser must
 * know which copy of the kit to load before picocli parses anything. This type is that early
 * read. It never consults or changes the kit sources registered with `kit source add`, which
 * are shared by every run of the same user.
 *
 * [dir] is a kit source directory: a parent whose `<name>/` subdirectory holds the kit.
 */
data class KitInstallSourceOverride(
    val kitName: String,
    val dir: File,
) {
    companion object {
        const val OPTION = Constants.Kit.INSTALL_SOURCE_OPTION

        /** Tokens of `kit install <name>`: the kit name follows `kit install`, the options follow it. */
        private val COMMAND_PATH = listOf("kit", "install")

        /**
         * Returns the override that [arguments] request, or null when they are not a
         * `kit install <name>` call with `--source`. A `--source` with no value is left to
         * picocli, which reports the missing parameter.
         */
        fun from(arguments: Array<String>): KitInstallSourceOverride? {
            val nameIndex = kitNameIndex(arguments) ?: return null
            val kitName = arguments[nameIndex]
            val rest = arguments.drop(nameIndex + 1)
            val value =
                rest.indexOf(OPTION).takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }
                    ?: rest.firstOrNull { it.startsWith("$OPTION=") }?.removePrefix("$OPTION=")
                    ?: return null
            return KitInstallSourceOverride(kitName, File(value))
        }

        /** The kit name of a `kit install <name>` call in [arguments], or null when they are not one. */
        fun kitName(arguments: Array<String>): String? = kitNameIndex(arguments)?.let { arguments[it] }

        private fun kitNameIndex(arguments: Array<String>): Int? =
            arguments
                .indices
                .firstOrNull { arguments.drop(it).take(COMMAND_PATH.size) == COMMAND_PATH }
                ?.plus(COMMAND_PATH.size)
                ?.takeIf { arguments.getOrNull(it)?.startsWith("-") == false }
    }
}
