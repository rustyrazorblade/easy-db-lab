package com.rustyrazorblade.easydblab.commands.converters

import com.rustyrazorblade.easydblab.configuration.CniMode
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.TypeConversionException

/**
 * PicoCLI converter for `--cni` values.
 *
 * Converts case-insensitive string input to a [CniMode] so users can pass the natural
 * lowercase forms `cilium` / `flannel` rather than the PascalCase enum constant names.
 *
 * @throws TypeConversionException if the input is not a recognized CNI
 */
class PicoCniModeConverter : ITypeConverter<CniMode> {
    override fun convert(value: String): CniMode =
        when (value.lowercase().trim()) {
            "cilium" -> CniMode.Cilium
            "flannel" -> CniMode.Flannel
            else -> throw TypeConversionException(
                "Invalid CNI: $value. Must be cilium or flannel",
            )
        }
}
