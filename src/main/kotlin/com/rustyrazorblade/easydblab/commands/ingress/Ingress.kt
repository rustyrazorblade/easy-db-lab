package com.rustyrazorblade.easydblab.commands.ingress

import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec

/**
 * Parent command for the SSH ingress rules on the workspace cluster's security group.
 *
 * A cluster created without `init --open` allows SSH only from the IPs its security group lists.
 * When the operator's public IP changes, or another person needs access, these commands change
 * the rules with EC2 API calls alone, so they work when SSH and the SOCKS tunnel do not.
 *
 * Available sub-commands:
 * - allow: Add the SSH rule for each CIDR (default: this machine's public IP)
 * - revoke: Remove the SSH rule for each CIDR (default: this machine's public IP)
 *
 * Usage:
 * ```
 * easy-db-lab ingress allow
 * easy-db-lab ingress allow --cidr 198.51.100.0/24
 * easy-db-lab ingress revoke --cidr 203.0.113.7
 * ```
 */
@Command(
    name = "ingress",
    description = ["SSH ingress rules on the cluster's security group"],
    mixinStandardHelpOptions = true,
    subcommands = [
        IngressAllow::class,
        IngressRevoke::class,
    ],
)
class Ingress : Runnable {
    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(System.out)
    }
}
