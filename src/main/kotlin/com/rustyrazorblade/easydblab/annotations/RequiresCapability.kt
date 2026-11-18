package com.rustyrazorblade.easydblab.annotations

import com.rustyrazorblade.easydblab.configuration.NodeCapability

/**
 * Marks a command that works only in an environment providing every one of [capabilities]; an
 * AWS-only command declares [NodeCapability.AWS_ACCOUNT]. It shares its vocabulary with the kit
 * `requires:` terms, so no command asks which environment it runs in.
 *
 * `DefaultCommandExecutor.checkRequirements()` checks it first, before the profile check and before
 * the command runs, against the target environment (the recorded one, or EC2 + K3s outside a
 * workspace). When a capability is absent it emits `Environment.CommandUnavailable` and the command
 * exits non-zero with no side effect.
 *
 * @property capabilities the capabilities the command needs.
 * @property alternative what to use instead in an environment that lacks them, or empty for none.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresCapability(
    vararg val capabilities: NodeCapability,
    val alternative: String = "",
)
