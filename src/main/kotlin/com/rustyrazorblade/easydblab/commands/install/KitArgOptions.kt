package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.KitArgSpec
import picocli.CommandLine.Model.IGetter
import picocli.CommandLine.Model.ISetter
import picocli.CommandLine.Model.OptionSpec

/**
 * Builds the picocli option for one kit arg, for both `kit install` args
 * ([KitInstallCommandFactory]) and command args ([KitRunnerCommandFactory]), so the two parse,
 * default and record an arg the same way.
 *
 * The option writes its value into a variable map keyed by [KitArgSpec.variable]. An optional
 * arg the user did not give and that has no default is left out of the map, never recorded as
 * the string `null`. A boolean with no declared default is `false`. A repeatable arg holds
 * every given value in order, joined with a newline.
 */
internal object KitArgOptions {
    /**
     * Returns the option for [arg] with [default] as its default (already resolved by the
     * caller), recording each parsed value into [values].
     */
    fun optionSpec(
        arg: KitArgSpec,
        default: String,
        values: MutableMap<String, String>,
    ): OptionSpec {
        val builder =
            OptionSpec
                .builder(arg.flag)
                .paramLabel(arg.paramLabel)
                .description(arg.description)
        if (arg.repeatable) {
            val binding = RepeatedValues { joined -> values.record(arg.variable, joined) }
            builder
                .type(List::class.java)
                .auxiliaryTypes(String::class.java)
                .arity("1")
                .getter(binding)
                .setter(binding)
        } else {
            builder
                .type(arg.type.toPicoCliType())
                .setter(
                    object : ISetter {
                        override fun <T> set(value: T): T {
                            // Picocli initialises an unmatched option with no default by passing null.
                            values.record(arg.variable, value?.toString())
                            return value
                        }
                    },
                )
        }
        val effectiveDefault = default.ifEmpty { if (arg.type == KitArgSpec.ArgType.BOOLEAN) "false" else "" }
        when {
            // Picocli expands ${...} as a property lookup and returns null for an unknown key,
            // so only a fully resolved default is handed to it.
            effectiveDefault.isNotEmpty() && !effectiveDefault.contains("\${") -> builder.defaultValue(effectiveDefault)
            effectiveDefault.isEmpty() && arg.required -> builder.required(true)
        }
        return builder.build()
    }

    /** Records [value] for [variable], or removes it when there is no value, so a reparse starts clean. */
    private fun MutableMap<String, String>.record(
        variable: String,
        value: String?,
    ) {
        if (value.isNullOrEmpty()) remove(variable) else put(variable, value)
    }

    /**
     * Holds a repeatable option's values and reports them, joined, on every change. Picocli reads
     * the current collection through the getter before it adds a value, so without a getter every
     * repetition would replace the last.
     */
    private class RepeatedValues(
        private val onChange: (String?) -> Unit,
    ) : IGetter,
        ISetter {
        private var current: Any? = null

        // Picocli's binding interfaces are generic in the value; it reads back what it last set.
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(): T = current as T

        override fun <T> set(value: T): T {
            current = value
            onChange((value as? Collection<*>)?.joinToString("\n"))
            return value
        }
    }
}
