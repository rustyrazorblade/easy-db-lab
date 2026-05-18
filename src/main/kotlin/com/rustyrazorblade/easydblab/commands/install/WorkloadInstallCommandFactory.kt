package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.WorkloadArgSpec
import com.rustyrazorblade.easydblab.services.WorkloadInstallConfig
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.ISetter
import picocli.CommandLine.Model.OptionSpec

/**
 * Builds a PicoCLI [CommandLine] for a workload install subcommand from [WorkloadInstallConfig].
 *
 * @param templateVars resolved cluster-state variables used to interpolate `${VAR}` defaults;
 *   pass an empty map when cluster state is unavailable (defaults retain their raw `${VAR}` form).
 */
class WorkloadInstallCommandFactory(
    private val templateVars: Map<String, String>,
) {
    fun build(
        config: WorkloadInstallConfig,
        source: InstallTemplateResolver.TemplateSource,
    ): CommandLine {
        val command = WorkloadInstallCommand(config, source)
        val spec = CommandSpec.wrapWithoutInspection(command).name(config.name)
        spec.usageMessage().description(config.description)
        spec.mixinStandardHelpOptions(true)

        if (config.collisionCheck) {
            spec.add(forceOptionSpec(command))
        }

        for (arg in config.args) {
            spec.add(argOptionSpec(arg, command))
        }

        return CommandLine(spec)
    }

    private fun forceOptionSpec(command: WorkloadInstallCommand): OptionSpec =
        OptionSpec
            .builder("--force")
            .type(Boolean::class.java)
            .description("Overwrite scaffold even if the workload directory already exists")
            .setter(
                object : ISetter {
                    override fun <T> set(value: T): T {
                        command.force = true
                        return value
                    }
                },
            ).build()

    private fun argOptionSpec(
        arg: WorkloadArgSpec,
        command: WorkloadInstallCommand,
    ): OptionSpec {
        val resolvedDefault = resolveDefault(arg.default, templateVars)
        val picoType =
            when (arg.type) {
                WorkloadArgSpec.ArgType.STRING -> String::class.java
                WorkloadArgSpec.ArgType.BOOLEAN -> Boolean::class.java
                WorkloadArgSpec.ArgType.FLOAT -> Double::class.java
                WorkloadArgSpec.ArgType.INT -> Int::class.java
            }

        val builder =
            OptionSpec
                .builder(arg.flag)
                .type(picoType)
                .description(arg.description)
                .setter(
                    object : ISetter {
                        override fun <T> set(value: T): T {
                            command.argValues[arg.variable] = "$value${arg.suffix}"
                            return value
                        }
                    },
                )

        if (resolvedDefault.isNotEmpty()) {
            val effectiveDefault = resolvedDefault + arg.suffix
            command.resolvedDefaults[arg.variable] = effectiveDefault
            // Only pass to PicoCLI when fully resolved — PicoCLI expands ${...} as property
            // lookups and returns null for unknown keys, corrupting the help text.
            if (!effectiveDefault.contains("\${")) {
                builder.defaultValue(effectiveDefault)
            }
        } else if (arg.required) {
            builder.required(true)
        }

        return builder.build()
    }

    private fun resolveDefault(
        default: String,
        vars: Map<String, String>,
    ): String =
        default.replace(Regex("""\$\{(\w+)}""")) { match ->
            vars[match.groupValues[1]] ?: match.value
        }
}
