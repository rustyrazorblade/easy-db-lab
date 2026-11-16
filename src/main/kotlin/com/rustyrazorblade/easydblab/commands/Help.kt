package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.services.HelpTopic
import com.rustyrazorblade.easydblab.services.HelpTopicService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import kotlin.system.exitProcess

/**
 * Prints task-oriented help topics packaged with the distribution.
 *
 * With no argument it lists every discovered topic and its description. With a topic argument it
 * prints that topic's markdown body verbatim. An unknown topic prints a clean error that names the
 * bad topic and lists the valid ones, then forces a non-zero exit.
 *
 * This is a read-only display command: it discovers topics through [HelpTopicService], writes with
 * `println()`, and emits no event. The non-zero exit for an unknown topic goes through the
 * injectable [exit] seam (defaulting to `exitProcess`) rather than a thrown exception, so the
 * generic command executor never renders a Java exception-class prefix to the terminal.
 */
@Command(
    name = "help",
    description = ["Show task-oriented help topics"],
)
class Help(
    private val exit: (Int) -> Unit = { exitProcess(it) },
) : PicoCommand,
    KoinComponent {
    private val helpTopicService: HelpTopicService by inject()

    @Parameters(
        arity = "0..1",
        paramLabel = "<topic>",
        description = ["The topic to show. Omit to list all topics."],
    )
    var topic: String? = null

    override fun execute() {
        val topics = helpTopicService.findAll()
        val requested = topic

        if (requested == null) {
            printTopicListing(topics)
            return
        }

        val match = helpTopicService.find(requested)
        if (match == null) {
            printUnknownTopic(requested, topics)
            exit(Constants.ExitCodes.ERROR)
            return
        }

        println(match.body)
    }

    private fun printTopicListing(topics: List<HelpTopic>) {
        val listing =
            topics
                .sortedBy { it.name }
                .joinToString("\n") { "  ${it.name}\t${it.description}" }
        val header =
            """
            Usage: easy-db-lab help <topic>

            Show a task-oriented guide for a topic. Available topics:

            """.trimIndent()
        println(header + listing)
    }

    private fun printUnknownTopic(
        requested: String,
        topics: List<HelpTopic>,
    ) {
        val valid = topics.map { it.name }.sorted().joinToString(", ")
        println(
            """
            Unknown help topic: $requested
            Valid topics: $valid
            """.trimIndent(),
        )
    }
}
