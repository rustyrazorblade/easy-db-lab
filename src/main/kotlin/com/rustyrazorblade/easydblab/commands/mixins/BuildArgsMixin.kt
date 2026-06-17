package com.rustyrazorblade.easydblab.commands.mixins

import com.rustyrazorblade.easydblab.commands.converters.PicoArchConverter
import com.rustyrazorblade.easydblab.configuration.Arch
import picocli.CommandLine.Option

/**
 * PicoCLI mixin for image build arguments.
 *
 * Provides reusable options for building AMI images including
 * release mode, region selection, and CPU architecture.
 */
class BuildArgsMixin {
    @Option(
        names = ["--release"],
        description = ["Release flag"],
    )
    var release: Boolean = false

    @Option(
        names = ["--region", "-r"],
        description = ["AWS region to build the image in"],
    )
    var region: String = ""

    @Option(
        names = ["--arch", "-a", "--cpu"],
        description = ["CPU architecture"],
        converter = [PicoArchConverter::class],
    )
    var arch: Arch = Arch.AMD64

    @Option(
        names = ["--keep-on-error"],
        description = [
            "On build failure, leave the instance and temporary SSH key running for debugging " +
                "(packer -on-error=abort) instead of terminating it",
        ],
    )
    var keepOnError: Boolean = false
}
