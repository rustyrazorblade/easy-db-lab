package com.rustyrazorblade.easydblab.commands.ingress

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.SshCidrMixin
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.aws.RevokeOutcome
import com.rustyrazorblade.easydblab.services.aws.SshIngressService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Removes the SSH rule for each `--cidr` value, or for this machine's detected public IP.
 *
 * Only the exact tcp/22 rule for each CIDR goes; every other rule stays.  It asks for no
 * confirmation, even for the operator's own IP.  A CIDR with no rule is reported and fails the
 * command, but the rules that do match are still removed.  It never starts the SOCKS tunnel.
 */
@RequireProfileSetup
@Command(
    name = "revoke",
    description = ["Remove the SSH rule for an IPv4 address or CIDR (default: this machine's public IP)"],
)
class IngressRevoke : PicoBaseCommand() {
    private val sshIngressService: SshIngressService by inject()

    @Mixin
    var cidrs = SshCidrMixin()

    override fun execute() {
        val applied = sshIngressService.revoke(cidrs.cidrs).appliedOrFail(eventBus)
        applied.outcomes.forEach { eventBus.emit(it.toEvent(applied.securityGroupId, applied.detected)) }
        val failed = applied.outcomes.filterNot { it is RevokeOutcome.Removed }.map { it.cidr.value }
        if (failed.isNotEmpty()) {
            throw CommandFailedException("could not revoke SSH from ${failed.joinToString()}")
        }
    }

    private fun RevokeOutcome.toEvent(
        securityGroupId: String,
        detected: Boolean,
    ): Event.Ingress =
        when (this) {
            is RevokeOutcome.Removed -> Event.Ingress.RuleRemoved(securityGroupId, cidr.value, detected)
            is RevokeOutcome.NotFound -> Event.Ingress.RuleNotFound(securityGroupId, cidr.value)
            is RevokeOutcome.Failed -> Event.Ingress.RuleChangeFailed(securityGroupId, cidr.value, Event.Ingress.Action.REVOKE, reason)
        }
}
