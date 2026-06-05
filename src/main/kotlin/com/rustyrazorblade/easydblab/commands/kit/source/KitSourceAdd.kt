package com.rustyrazorblade.easydblab.commands.kit.source

import com.rustyrazorblade.easydblab.services.AddSourceResult
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Registers a named additional kit source directory in the active profile.
 *
 * Analogous to `git remote add` — each source has a name and a path. If the name already
 * exists its path is updated (upsert). Registered sources appear in `kit list`,
 * `kit info`, and `kit install`.
 */
@Command(
    name = "add",
    description = ["Register a named kit source directory (e.g. kit source add myproject ~/myproject/kits)"],
    mixinStandardHelpOptions = true,
)
class KitSourceAdd :
    Runnable,
    KoinComponent {
    private val kitSourcesProvider: KitSourcesProvider by inject()

    @Parameters(
        index = "0",
        description = ["Name for this source (e.g. myproject)"],
    )
    lateinit var name: String

    @Parameters(
        index = "1",
        description = ["Path to a directory containing kit subdirectories"],
    )
    lateinit var path: String

    override fun run() {
        val dir = File(path)
        require(dir.isDirectory) { "Path does not exist or is not a directory: $path" }
        when (kitSourcesProvider.add(name, path)) {
            AddSourceResult.ADDED -> println("Added kit source '$name': ${dir.canonicalPath}")
            AddSourceResult.UPDATED -> println("Updated kit source '$name': ${dir.canonicalPath}")
        }
    }
}
