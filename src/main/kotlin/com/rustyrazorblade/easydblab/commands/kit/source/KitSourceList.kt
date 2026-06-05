package com.rustyrazorblade.easydblab.commands.kit.source

import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import java.io.File

/**
 * Lists all registered additional kit source directories with their names.
 *
 * Paths that no longer exist on disk are flagged with `[missing]` so the user
 * can clean them up with `kit source remove <name>`.
 */
@Command(
    name = "list",
    description = ["List registered kit source directories"],
    mixinStandardHelpOptions = true,
)
class KitSourceList :
    Runnable,
    KoinComponent {
    private val kitSourcesProvider: KitSourcesProvider by inject()

    override fun run() {
        val sources = kitSourcesProvider.load().sources
        if (sources.isEmpty()) {
            println("No kit sources registered. Use 'kit source add <name> <path>' to register one.")
            return
        }
        val nameWidth = sources.maxOf { it.name.length }
        println("Registered kit sources:")
        for (source in sources) {
            val dir = File(source.path)
            val missing = if (!dir.isDirectory || !dir.canRead()) " [missing]" else ""
            println("  ${source.name.padEnd(nameWidth)}  ${source.path}$missing")
        }
    }
}
