package com.rustyrazorblade.easydblab.commands.ingress

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.aws.IngressResult

/**
 * Returns the per-CIDR outcomes of an ingress change, or reports the precondition that stopped
 * it and fails the command.
 *
 * Shared by `ingress allow` and `ingress revoke`, so both report a precondition the same way: one
 * typed event, then a [CommandFailedException] so the command exits non-zero without the failure
 * being printed twice.
 *
 * @throws CommandFailedException for every result other than [IngressResult.Applied]
 */
internal fun <T> IngressResult<T>.appliedOrFail(eventBus: EventBus): IngressResult.Applied<T> =
    when (this) {
        IngressResult.NotInWorkspace ->
            fail(eventBus, Event.Ingress.NotInWorkspace, "this directory is not a cluster workspace")
        IngressResult.NoSecurityGroupInState ->
            fail(eventBus, Event.Ingress.NoSecurityGroupInState, "the cluster state records no security group")
        is IngressResult.InvalidCidrs ->
            fail(
                eventBus,
                Event.Ingress.InvalidCidrs(invalid.map { Event.Ingress.InvalidCidrValue(it.value, it.reason) }),
                "invalid --cidr values: ${invalid.joinToString { it.value }}",
            )
        is IngressResult.DetectionFailed ->
            fail(eventBus, Event.Ingress.PublicIpDetectionFailed(reason), "public IP detection failed: $reason")
        is IngressResult.SecurityGroupGone ->
            fail(eventBus, Event.Ingress.SecurityGroupNotFound(securityGroupId), "security group $securityGroupId does not exist")
        is IngressResult.Applied -> this
    }

private fun fail(
    eventBus: EventBus,
    event: Event.Ingress,
    message: String,
): Nothing {
    eventBus.emit(event)
    throw CommandFailedException(message)
}
