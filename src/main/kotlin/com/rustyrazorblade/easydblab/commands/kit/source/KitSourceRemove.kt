package com.rustyrazorblade.easydblab.commands.kit.source

import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters

/**
 * Unregisters a named additional kit source directory from the active profile.
 */
@Command(
    name = "remove",
    description = ["Remove a registered kit source by name"],
    mixinStandardHelpOptions = true,
)
class KitSourceRemove :
    Runnable,
    KoinComponent {
    private val kitSourcesProvider: KitSourcesProvider by inject()

    @Parameters(
        index = "0",
        description = ["Name of the source to remove (e.g. myproject)"],
    )
    lateinit var name: String

    override fun run() {
        if (kitSourcesProvider.remove(name)) {
            println("Removed kit source '$name'")
        } else {
            println("No kit source named '$name'")
        }
    }
}
