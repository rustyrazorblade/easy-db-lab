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
        spec.usageMessage().description(KitArgOptions.picocliText(config.description))

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

        val commandLine = CommandLine(spec)
        // picocli calls each option's setter with its default when the user did not pass it, so
        // argValues alone cannot tell a passed value from a startup default. The command asks the
        // parse result instead, and re-resolves every default the user did not override.
        // Built but never parsed (a caller filled argValues itself): everything set was passed.
        command.userSuppliedVariables = {
            when (val parseResult = commandLine.parseResult) {
                null -> command.argValues.keys.toSet()
                else ->
                    config.args
                        .filter { parseResult.hasMatchedOption(it.flag) }
                        .map { it.variable }
                        .toSet()
            }
        }
        return commandLine
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
        // KitInstallCommand records "" for an arg that is still unset after it applies the defaults.
        return KitArgOptions.optionSpec(arg, resolvedDefault, command.argValues, KitArgOptions.UnsetArg.LEFT_TO_CALLER)
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

/**
 * Sets how many values an option of [type] takes. A boolean takes an optional value, so
 * `--flag false` turns off a flag whose default is `true`. A bare `--flag` falls back to
 * `true`: without the fallback, picocli sets a bare boolean to the opposite of its default.
 * Every other type takes exactly one value.
 */
internal fun OptionSpec.Builder.valuesFor(type: KitArgSpec.ArgType): OptionSpec.Builder =
    when (type) {
        KitArgSpec.ArgType.BOOLEAN -> arity("0..1").fallbackValue("true")
        KitArgSpec.ArgType.STRING,
        KitArgSpec.ArgType.KIT_REF,
        KitArgSpec.ArgType.EXTENSION,
        KitArgSpec.ArgType.FLOAT,
        KitArgSpec.ArgType.INT,
        -> arity("1")
    }
