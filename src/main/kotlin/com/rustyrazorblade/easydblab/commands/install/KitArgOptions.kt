package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.KitArgSpec
import picocli.CommandLine.IParameterConsumer
import picocli.CommandLine.MissingParameterException
import picocli.CommandLine.Model.ArgSpec
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.ISetter
import picocli.CommandLine.Model.OptionSpec
import java.util.Stack

/**
 * Builds the picocli option for one kit arg, for both `kit install` args
 * ([KitInstallCommandFactory]) and command args ([KitRunnerCommandFactory]), so the two parse,
 * default and record an arg the same way.
 *
 * The option writes its value into a variable map keyed by [KitArgSpec.variable]. An optional
 * arg the user did not give and that has no default is recorded as described by [UnsetArg], never
 * as the string `null`; an explicit empty value is recorded as given. A boolean with no declared
 * default is `false`, and a bare boolean flag is `true`. A repeatable arg holds every given value
 * in order, joined with a newline.
 */
internal object KitArgOptions {
    /** What an option records for an arg the user did not give and that has no default. */
    enum class UnsetArg {
        /**
         * The empty string. A kit process copies the parent environment, so a variable left out
         * would let an exported shell variable of the same name reach the script.
         */
        EMPTY,

        /**
         * Nothing; the caller records the empty string itself once it has applied the defaults it
         * resolves later (`kit install` applies a `${...}` default after parsing).
         */
        LEFT_TO_CALLER,
    }

    /**
     * Returns the option for [arg] with [default] as its default (already resolved by the
     * caller), recording each parsed value into [values], and [unset] for no value.
     */
    fun optionSpec(
        arg: KitArgSpec,
        default: String,
        values: MutableMap<String, String>,
        unset: UnsetArg,
    ): OptionSpec {
        val effectiveDefault = default.ifEmpty { if (arg.type == KitArgSpec.ArgType.BOOLEAN) "false" else "" }
        // Picocli expands ${...} as a property lookup and returns null for an unknown key,
        // so only a fully resolved default is handed to it.
        val resolvedDefault = effectiveDefault.takeIf { it.isNotEmpty() && !it.contains("\${") }
        val builder =
            OptionSpec
                .builder(arg.flag)
                .paramLabel(arg.paramLabel)
                .description(helpDescription(arg, resolvedDefault))
        if (arg.repeatable) {
            val binding = RepeatedValues(arg.flag) { joined -> values.record(arg.variable, joined, unset) }
            builder
                .type(String::class.java)
                .setter(binding)
                .parameterConsumer(binding)
        } else {
            builder
                .type(arg.type.toPicoCliType())
                .valuesFor(arg.type)
                .setter(
                    object : ISetter {
                        override fun <T> set(value: T): T {
                            // Picocli initialises an unmatched option with no default by passing null.
                            values.record(arg.variable, value?.toString(), unset)
                            return value
                        }
                    },
                )
        }
        when {
            resolvedDefault != null -> builder.defaultValue(resolvedDefault)
            effectiveDefault.isEmpty() && arg.required -> builder.required(true)
        }
        return builder.build()
    }

    /**
     * The `--help` description of [arg]: its own description, then its default in the form the
     * other commands use, `(default: <value>)`. A boolean flag's implicit `false` is not shown,
     * and neither is a default that is not resolved yet.
     */
    private fun helpDescription(
        arg: KitArgSpec,
        resolvedDefault: String?,
    ): String =
        if (resolvedDefault == null || arg.type == KitArgSpec.ArgType.BOOLEAN) {
            arg.description
        } else {
            listOf(arg.description, "(default: \${DEFAULT-VALUE})").filter { it.isNotBlank() }.joinToString(" ")
        }

    /**
     * Records [value] for [variable]. With no value it records what [unset] says, replacing any
     * value from an earlier parse, so a reparse starts clean. An explicit empty string is a value:
     * it overrides a non-empty default.
     */
    private fun MutableMap<String, String>.record(
        variable: String,
        value: String?,
        unset: UnsetArg,
    ) {
        when {
            value != null -> put(variable, value)
            unset == UnsetArg.EMPTY -> put(variable, "")
            else -> remove(variable)
        }
    }

    /**
     * Holds a repeatable option's values and reports them, joined, on every change.
     *
     * Picocli passes the initial value (null, or the default) to the setter before each parse,
     * and hands every occurrence of [flag] to the consumer, which appends it. The first occurrence
     * replaces a default instead of adding to it. A consumer, not a collection-typed option, is
     * what lets this hold a typed `List<String>`: a collection option needs an `IGetter`, whose
     * generic `<T> get(): T` cannot be implemented without an unchecked cast.
     */
    private class RepeatedValues(
        private val flag: String,
        private val onChange: (String?) -> Unit,
    ) : ISetter,
        IParameterConsumer {
        private var current: List<String> = emptyList()
        private var given = false

        override fun <T> set(value: T): T {
            current = listOfNotNull(value?.toString())
            given = false
            report()
            return value
        }

        override fun consumeParameters(
            args: Stack<String>,
            argSpec: ArgSpec,
            commandSpec: CommandSpec,
        ) {
            if (args.isEmpty()) {
                throw MissingParameterException(commandSpec.commandLine(), argSpec, "Missing required parameter for option '$flag'")
            }
            current = (if (given) current else emptyList()) + args.pop()
            given = true
            report()
        }

        private fun report() = onChange(current.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
