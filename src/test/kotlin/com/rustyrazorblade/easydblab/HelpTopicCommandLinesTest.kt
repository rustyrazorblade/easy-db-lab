package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.DefaultHelpTopicService
import com.rustyrazorblade.easydblab.services.DefaultKitCommandScanner
import com.rustyrazorblade.easydblab.services.HelpTopicService
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitCommandScanner
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.WorkspaceKitScanner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine
import java.io.File

/**
 * Checks every `easy-db-lab ...` command line in the packaged help topics against the real
 * command tree, so a topic cannot document an option or a positional argument the CLI does not
 * take. `help spark` showed `spark submit <jar-path>` and `spark status [<job-id>]` while the
 * commands take `--jar` and `--step-id`.
 *
 * A line is checked from the deepest registered subcommand it names: each `--option` must exist
 * there, and any other argument needs the command to take a positional parameter. A line whose
 * first word is not a registered command (a `<kit>` placeholder, or a kit installed in a
 * workspace) is not checked, and neither is the rest of a line after a placeholder that stands
 * for a dynamic subcommand (`kit install <name> [args]`).
 */
class HelpTopicCommandLinesTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { WorkspaceKitScanner(get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single<KitCommandScanner> { DefaultKitCommandScanner() }
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                single<HelpTopicService> { DefaultHelpTopicService() }
            },
        )

    @Test
    fun `every command line in a help topic uses only options and arguments the command takes`() {
        val root = CommandLineParser().commandLine
        val lines = DefaultHelpTopicService().findAll().flatMap { topic -> commandLines(topic.body).map { topic.name to it } }

        assertThat(lines).describedAs("command lines found in the help topics").isNotEmpty()
        val problems = lines.mapNotNull { (topic, line) -> problemWith(line, root)?.let { "help $topic: `$line`: $it" } }
        assertThat(problems).isEmpty()
    }

    private fun commandLines(body: String): List<String> {
        val inline = Regex("`(easy-db-lab [^`]+)`").findAll(body).map { it.groupValues[1] }
        val fenced =
            Regex("```[a-z]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
                .findAll(body)
                .flatMap { block -> block.groupValues[1].lines() }
                .map { it.trim().substringBefore("  #") }
                .filter { it.startsWith("easy-db-lab ") }
        return (inline + fenced).toList()
    }

    private fun problemWith(
        line: String,
        root: CommandLine,
    ): String? {
        val tokens = tokenize(line).drop(1)
        var command = root
        var index = 0
        while (index < tokens.size && tokens[index] in command.subcommands) {
            command = command.subcommands.getValue(tokens[index])
            index++
        }
        val spec = command.commandSpec
        val takesPositional = spec.positionalParameters().isNotEmpty()
        val standsForSubcommand = index < tokens.size && tokens[index].isPlaceholder() && !takesPositional
        if (command === root || standsForSubcommand && command.subcommands.isNotEmpty()) return null

        val rest = tokens.drop(index)
        var position = 0
        while (position < rest.size) {
            val token = rest[position]
            when {
                token == "--" -> return if (takesPositional) null else "`${spec.qualifiedName()}` takes no arguments after --"
                token.startsWith("-") -> {
                    val option =
                        spec.findOption(token.substringBefore('='))
                            ?: return "`${spec.qualifiedName()}` has no option ${token.substringBefore('=')}"
                    if (!token.contains('=') && option.arity().max() > 0) position++
                }
                !takesPositional -> return "`${spec.qualifiedName()}` takes no positional argument, but the line passes $token"
            }
            position++
        }
        return null
    }

    private fun String.isPlaceholder() = startsWith("<") || startsWith("[")

    /** Splits on spaces, keeping a double-quoted string as one token. */
    private fun tokenize(line: String): List<String> = Regex("\"[^\"]*\"|\\S+").findAll(line).map { it.value }.toList()
}
