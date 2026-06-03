package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.commands.install.BaseInstallCommand
import com.rustyrazorblade.easydblab.events.Event
import picocli.CommandLine.Command

/**
 * Lists all discoverable kit install templates from the classpath and user profile directory.
 *
 * Profile templates shadow built-in templates when names collide.
 * Ad-hoc templates (supplied via [Install.from]) are never included in this listing.
 *
 * Uses println() directly — listing available kits is a read-only display command.
 * No state changes; nothing an external system needs to be aware of.
 */
@Command(
    name = "list",
    description = ["List all discoverable kit install templates (profile + built-in)"],
    mixinStandardHelpOptions = true,
)
class KitList : BaseInstallCommand() {
    override fun execute() {
        val templates = resolver.listAvailableTemplateDetails()
        println(buildListText(templates))
    }

    companion object {
        fun buildListText(templates: List<Event.Install.TemplateDetail>): String {
            if (templates.isEmpty()) return "No install templates found."
            val nameWidth = templates.maxOf { it.name.length }
            val versionWidth = templates.maxOf { it.version.length }
            val rows =
                templates.joinToString("\n") { t ->
                    buildString {
                        append("  ")
                        append(t.name.padEnd(nameWidth))
                        if (versionWidth > 0) {
                            append("  ")
                            append(t.version.padEnd(versionWidth))
                        }
                        if (t.description.isNotEmpty()) {
                            append("  ")
                            append(t.description)
                        }
                    }
                }
            return "Available install templates:\n$rows"
        }
    }
}
