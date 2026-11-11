package com.rustyrazorblade.easydblab.commands.mixins

import picocli.CommandLine.Option

/**
 * PicoCLI mixin for the repeatable `--cidr` option of `ingress allow` and `ingress revoke`.
 *
 * The values stay raw strings here: the service validates them all at once, so one bad value is
 * reported together with every other bad value instead of stopping at the first.  With none, the
 * command uses this machine's detected public IP.
 */
class SshCidrMixin {
    @Option(
        names = ["--cidr"],
        paramLabel = "<ip-or-cidr>",
        description = [
            "IPv4 address or CIDR to change the SSH rule for; a bare address means /32. " +
                "Repeat for several. Default: this machine's public IP.",
        ],
    )
    var cidrs: List<String> = emptyList()
}
