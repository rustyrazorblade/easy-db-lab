package com.rustyrazorblade.easydblab.commands.converters

import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.TypeConversionException

/**
 * PicoCLI converter that refuses every value, for a hidden `--name` option on `init`.
 *
 * The cluster name is a positional argument (`easy-db-lab init <name>`). Without this option,
 * picocli rejects `--name` with a bare "Unknown option", which names no way forward. Declaring the
 * option lets the parser fail at parse time and name the form that works.
 *
 * @throws TypeConversionException always, naming the positional form
 */
class PicoPositionalClusterNameConverter : ITypeConverter<String> {
    override fun convert(value: String): String =
        throw TypeConversionException(
            "the cluster name is positional, not an option. Use: easy-db-lab init <name> [options] " +
                "(for example: easy-db-lab init $value)",
        )
}
