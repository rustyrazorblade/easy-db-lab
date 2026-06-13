package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

/**
 * Lists all available extension aliases from the kit's extensions.yaml.
 *
 * Displays alias name, image template, shared_preload_libraries, and CREATE EXTENSION
 * statements so users can discover which extensions are available and what they configure.
 */
@CommandLine.Command(
    name = "extensions",
    description = ["List available extension aliases"],
)
class KitExtensionsCommand(
    private val kitDir: File,
) : Callable<Int> {
    override fun call(): Int {
        val extensionsFile = File(kitDir, "extensions.yaml")
        val registry = ExtensionRegistry.fromFile(extensionsFile)
        val all = registry.all()

        if (all.isEmpty()) {
            println("No extension aliases found in ${extensionsFile.absolutePath}")
            return 0
        }

        val nameWidth = all.keys.maxOf { it.length }.coerceAtLeast(5)
        val header = "%-${nameWidth}s  %-50s  %-30s  %s".format("ALIAS", "IMAGE", "PRELOAD LIBRARIES", "CREATE EXTENSIONS")
        println(header)
        println("-".repeat(header.length))

        for ((name, alias) in all.entries.sortedBy { it.key }) {
            val preload = alias.sharedPreloadLibraries.joinToString(",").ifEmpty { "-" }
            val create = alias.createExtensions.joinToString(",").ifEmpty { "-" }
            println("%-${nameWidth}s  %-50s  %-30s  %s".format(name, alias.image, preload, create))
        }

        return 0
    }
}
