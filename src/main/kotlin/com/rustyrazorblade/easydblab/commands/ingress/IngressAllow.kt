package com.rustyrazorblade.easydblab.commands.ingress

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.mixins.SshCidrMixin
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.aws.AllowOutcome
import com.rustyrazorblade.easydblab.services.aws.SshIngressService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

/**
 * Allows SSH to the cluster from each `--cidr` value, or from this machine's detected public IP.
 *
 * It makes EC2 API calls only and never starts the SOCKS tunnel: the usual reason to run it is
 * that a changed public IP has already cut off SSH, and with it the tunnel.  It reports one event
 * per CIDR and exits non-zero when any CIDR fails or a precondition is not met.
 */
@RequireProfileSetup
@Command(
    name = "allow",
    description = ["Allow SSH to the cluster from an IPv4 address or CIDR (default: this machine's public IP)"],
)
class IngressAllow : PicoBaseCommand() {
    private val sshIngressService: SshIngressService by inject()

    @Mixin
    var cidrs = SshCidrMixin()

    override fun execute() {
        val applied = sshIngressService.allow(cidrs.cidrs).appliedOrFail(eventBus)
        applied.outcomes.forEach { eventBus.emit(it.toEvent(applied.securityGroupId, applied.detected)) }
        val failed = applied.outcomes.filterIsInstance<AllowOutcome.Failed>().map { it.cidr.value }
        if (failed.isNotEmpty()) {
            throw CommandFailedException("could not allow SSH from ${failed.joinToString()}")
        }
    }

    private fun AllowOutcome.toEvent(
        securityGroupId: String,
        detected: Boolean,
    ): Event.Ingress =
        when (this) {
            is AllowOutcome.Added -> Event.Ingress.RuleAdded(securityGroupId, cidr.value, detected)
            is AllowOutcome.AlreadyPresent -> Event.Ingress.RuleAlreadyPresent(securityGroupId, cidr.value, detected)
            is AllowOutcome.Failed -> Event.Ingress.RuleChangeFailed(securityGroupId, cidr.value, Event.Ingress.Action.ALLOW, reason)
        }
}
