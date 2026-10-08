package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitConfig
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.ISetter
import picocli.CommandLine.Model.OptionSpec

/**
 * Builds a PicoCLI [CommandLine] for a kit install subcommand from [KitConfig].
 *
 * @param templateVars resolved cluster-state variables used to interpolate `${VAR}` defaults;
 *   pass an empty map when cluster state is unavailable (defaults retain their raw `${VAR}` form).
 */
class KitInstallCommandFactory(
    private val templateVars: Map<String, String>,
) {
    fun build(
        config: KitConfig,
        source: InstallTemplateResolver.TemplateSource,
    ): CommandLine {
        val command = KitInstallCommand(config, source)
        val spec = CommandSpec.wrapWithoutInspection(command).name(config.name)
        spec.usageMessage().description(config.description)

        val kitUsesVersionFlag = config.args.any { it.flag == "--version" }
        if (kitUsesVersionFlag) {
            // Kit owns --version as a named arg; only add --help to avoid a duplicate option conflict.
            spec.add(
                OptionSpec
                    .builder("--help", "-h")
                    .usageHelp(true)
                    .type(Boolean::class.java)
                    .description("Show this help message and exit.")
                    .build(),
            )
        } else {
            spec.mixinStandardHelpOptions(true)
        }

        if (config.collisionCheck.guards(Constants.Kit.PHASE_INSTALL)) {
            spec.add(forceOptionSpec(command))
        }

        for (arg in config.args) {
            spec.add(argOptionSpec(arg, command))
        }

        return CommandLine(spec)
    }

    private fun forceOptionSpec(command: KitInstallCommand): OptionSpec =
        OptionSpec
            .builder("--force")
            .type(Boolean::class.java)
            .description("Overwrite scaffold even if the kit directory already exists")
            .setter(
                object : ISetter {
                    override fun <T> set(value: T): T {
                        command.force = value == true
                        return value
                    }
                },
            ).build()

    private fun argOptionSpec(
        arg: KitArgSpec,
        command: KitInstallCommand,
    ): OptionSpec {
        val resolvedDefault = resolveDefault(arg.default, templateVars)
        // A default that still holds ${...} is not handed to picocli, so execute() applies it.
        if (resolvedDefault.isNotEmpty()) command.resolvedDefaults[arg.variable] = resolvedDefault
        return KitArgOptions.optionSpec(arg, resolvedDefault, command.argValues)
    }

    private fun resolveDefault(
        default: String,
        vars: Map<String, String>,
    ): String =
        default.replace(Constants.Kit.SHELL_VAR_PATTERN) { match ->
            vars[match.groupValues[1]] ?: match.value
        }
}

internal fun KitArgSpec.ArgType.toPicoCliType(): Class<*> =
    when (this) {
        KitArgSpec.ArgType.STRING, KitArgSpec.ArgType.KIT_REF, KitArgSpec.ArgType.EXTENSION -> String::class.java
        KitArgSpec.ArgType.BOOLEAN -> Boolean::class.java
        KitArgSpec.ArgType.FLOAT -> Double::class.java
        KitArgSpec.ArgType.INT -> Int::class.java
    }
