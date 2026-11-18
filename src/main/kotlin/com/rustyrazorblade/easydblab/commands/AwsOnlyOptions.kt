package com.rustyrazorblade.easydblab.commands

import picocli.CommandLine.ParseResult

/**
 * The `init` options that configure AWS instances, networking or services, which a K3s in Docker
 * workspace has none of. `init --local` refuses any of them given explicitly, rather than record a
 * value nothing would read.
 */
object AwsOnlyOptions {
    /** The AWS-only options named one by one. */
    private val names = setOf("--instance", "--db.instance-type", "--azs", "--vpc", "--cidr", "--cni", "--ami", "--open")

    /** The option families that are AWS-only as a whole. */
    private val prefixes = listOf("--ebs.", "--spark.", "--opensearch.")

    /** Whether the option whose longest name is [name] is AWS-only. */
    fun isAwsOnly(name: String): Boolean = name in names || prefixes.any { name.startsWith(it) }

    /** The AWS-only options given in [parseResult], by their longest name, in the order typed; none for a command built in code. */
    fun givenIn(parseResult: ParseResult?): List<String> =
        parseResult
            ?.matchedOptions()
            .orEmpty()
            .map { it.longestName() }
            .filter(::isAwsOnly)
            .distinct()
}
