package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.network.InvalidCidrException
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import com.rustyrazorblade.easydblab.providers.aws.IngressAuthorization
import com.rustyrazorblade.easydblab.providers.aws.IngressRevocation
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupId
import com.rustyrazorblade.easydblab.providers.aws.SecurityGroupNotFoundException
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import com.rustyrazorblade.easydblab.services.ExternalIpService
import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException

/**
 * Adds and removes SSH ingress rules on the workspace cluster's security group, for
 * `ingress allow` and `ingress revoke`.
 *
 * It owns the SSH binding (tcp, port 22 to 22), so callers deal only in CIDRs.  It reads the
 * security group from the cluster state, turns the operator's values (or, with none, the detected
 * public IP) into validated canonical CIDRs before any AWS call, and changes one exact rule per
 * CIDR.  It reports every outcome as data and emits no events; the commands do that.
 */
interface SshIngressService {
    /** Adds an SSH rule for each of [rawCidrs], or for the detected public IP when it is empty. */
    fun allow(rawCidrs: List<String>): IngressResult<AllowOutcome>

    /** Removes the SSH rule for each of [rawCidrs], or for the detected public IP when it is empty. */
    fun revoke(rawCidrs: List<String>): IngressResult<RevokeOutcome>
}

/**
 * The result of one `ingress allow` or `ingress revoke`: a precondition that stopped it before
 * any rule changed, or the per-CIDR outcomes of the change.
 */
sealed interface IngressResult<out T> {
    /** The working directory has no cluster state. */
    data object NotInWorkspace : IngressResult<Nothing>

    /** The cluster state records no security group. */
    data object NoSecurityGroupInState : IngressResult<Nothing>

    /** One or more values are not valid IPv4 CIDRs; nothing was changed. */
    data class InvalidCidrs(
        val invalid: List<InvalidCidr>,
    ) : IngressResult<Nothing>

    /** No value was given and the public IP could not be detected; nothing was changed. */
    data class DetectionFailed(
        val reason: String,
    ) : IngressResult<Nothing>

    /** AWS reports that the security group in the cluster state does not exist. */
    data class SecurityGroupGone(
        val securityGroupId: String,
    ) : IngressResult<Nothing>

    /**
     * Each CIDR was tried on [securityGroupId], in order.  [detected] is true when the one CIDR is
     * the detected public IP rather than a value the operator gave.
     */
    data class Applied<T>(
        val securityGroupId: String,
        val detected: Boolean,
        val outcomes: List<T>,
    ) : IngressResult<T>
}

/** A rejected `--cidr` value and why. */
data class InvalidCidr(
    val value: String,
    val reason: String,
)

/** What `ingress allow` did for one CIDR. */
sealed interface AllowOutcome {
    val cidr: Ipv4Cidr

    /** The SSH rule was added. */
    data class Added(
        override val cidr: Ipv4Cidr,
    ) : AllowOutcome

    /** The security group already had the SSH rule. */
    data class AlreadyPresent(
        override val cidr: Ipv4Cidr,
    ) : AllowOutcome

    /** AWS refused the change, for [reason]. */
    data class Failed(
        override val cidr: Ipv4Cidr,
        val reason: String,
    ) : AllowOutcome
}

/** What `ingress revoke` did for one CIDR. */
sealed interface RevokeOutcome {
    val cidr: Ipv4Cidr

    /** The SSH rule was removed. */
    data class Removed(
        override val cidr: Ipv4Cidr,
    ) : RevokeOutcome

    /** The security group had no SSH rule for the CIDR. */
    data class NotFound(
        override val cidr: Ipv4Cidr,
    ) : RevokeOutcome

    /** AWS refused the change, for [reason]. */
    data class Failed(
        override val cidr: Ipv4Cidr,
        val reason: String,
    ) : RevokeOutcome
}

/**
 * [SshIngressService] over the cluster state, the public IP detector and [VpcService].
 */
class DefaultSshIngressService(
    private val clusterStateManager: ClusterStateManager,
    private val externalIpService: ExternalIpService,
    private val vpcService: VpcService,
) : SshIngressService {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val SSH_PROTOCOL = "tcp"
    }

    override fun allow(rawCidrs: List<String>): IngressResult<AllowOutcome> =
        change(
            rawCidrs,
            apply = { securityGroupId, cidr ->
                when (authorize(securityGroupId, cidr)) {
                    IngressAuthorization.ADDED -> AllowOutcome.Added(cidr)
                    IngressAuthorization.ALREADY_PRESENT -> AllowOutcome.AlreadyPresent(cidr)
                }
            },
            failed = AllowOutcome::Failed,
        )

    override fun revoke(rawCidrs: List<String>): IngressResult<RevokeOutcome> =
        change(
            rawCidrs,
            apply = { securityGroupId, cidr ->
                when (revokeRule(securityGroupId, cidr)) {
                    IngressRevocation.REMOVED -> RevokeOutcome.Removed(cidr)
                    IngressRevocation.NOT_FOUND -> RevokeOutcome.NotFound(cidr)
                }
            },
            failed = RevokeOutcome::Failed,
        )

    /**
     * Resolves the security group and the targets, then applies one change per CIDR.  An AWS
     * error on one CIDR becomes its [failed] outcome and the loop goes on; a missing security
     * group stops the whole command.
     */
    private fun <T> change(
        rawCidrs: List<String>,
        apply: (SecurityGroupId, Ipv4Cidr) -> T,
        failed: (Ipv4Cidr, String) -> T,
    ): IngressResult<T> {
        val securityGroupId =
            when (val group = securityGroup()) {
                is GroupLookup.Found -> group.securityGroupId
                is GroupLookup.Missing -> return group.result
            }
        val targets =
            when (val resolved = targets(rawCidrs)) {
                is Targets.Resolved -> resolved
                is Targets.Rejected -> return resolved.result
            }
        val outcomes =
            targets.cidrs.map { cidr ->
                try {
                    apply(securityGroupId, cidr)
                } catch (e: SecurityGroupNotFoundException) {
                    log.warn(e) { "Security group $securityGroupId is gone" }
                    return IngressResult.SecurityGroupGone(securityGroupId)
                } catch (e: SdkException) {
                    log.warn(e) { "SSH ingress change for $cidr on $securityGroupId failed" }
                    failed(cidr, awsReason(e))
                }
            }
        return IngressResult.Applied(securityGroupId, targets.detected, outcomes)
    }

    private fun securityGroup(): GroupLookup {
        if (!clusterStateManager.exists()) return GroupLookup.Missing(IngressResult.NotInWorkspace)
        val securityGroupId =
            clusterStateManager.load().infrastructure?.securityGroupId
                ?: return GroupLookup.Missing(IngressResult.NoSecurityGroupInState)
        return GroupLookup.Found(securityGroupId)
    }

    private fun targets(rawCidrs: List<String>): Targets =
        when {
            rawCidrs.isEmpty() -> detectedTarget()
            else -> givenTargets(rawCidrs)
        }

    private fun givenTargets(rawCidrs: List<String>): Targets {
        val parsed = rawCidrs.map { raw -> raw to Ipv4Cidr.parse(raw) }
        val invalid =
            parsed.mapNotNull { (raw, result) ->
                result.exceptionOrNull()?.let { InvalidCidr(raw, reasonOf(it)) }
            }
        if (invalid.isNotEmpty()) return Targets.Rejected(IngressResult.InvalidCidrs(invalid))
        return Targets.Resolved(parsed.map { (_, result) -> result.getOrThrow() }.distinct(), detected = false)
    }

    private fun detectedTarget(): Targets {
        val address =
            runCatching { externalIpService.getExternalIpAddress().trim() }
                .getOrElse { e ->
                    log.warn(e) { "Public IP detection failed" }
                    return Targets.Rejected(IngressResult.DetectionFailed(e.message ?: e.javaClass.simpleName))
                }
        val cidr =
            address
                .takeUnless { '/' in it }
                ?.let { Ipv4Cidr.parse(it).getOrNull() }
                ?: return Targets.Rejected(
                    IngressResult.DetectionFailed("the public IP service answered '$address', which is not an IPv4 address"),
                )
        return Targets.Resolved(listOf(cidr), detected = true)
    }

    private fun authorize(
        securityGroupId: SecurityGroupId,
        cidr: Ipv4Cidr,
    ): IngressAuthorization =
        vpcService.authorizeSecurityGroupIngress(
            securityGroupId,
            Constants.Network.SSH_PORT,
            Constants.Network.SSH_PORT,
            cidr.value,
            SSH_PROTOCOL,
        )

    private fun revokeRule(
        securityGroupId: SecurityGroupId,
        cidr: Ipv4Cidr,
    ): IngressRevocation =
        vpcService.revokeSecurityGroupIngress(
            securityGroupId,
            Constants.Network.SSH_PORT,
            Constants.Network.SSH_PORT,
            cidr.value,
            SSH_PROTOCOL,
        )

    private fun reasonOf(error: Throwable): String =
        when (error) {
            is InvalidCidrException -> error.reason
            else -> error.message ?: error.javaClass.simpleName
        }

    private fun awsReason(error: SdkException): String =
        when (error) {
            is AwsServiceException -> error.awsErrorDetails()?.errorMessage() ?: error.message
            else -> error.message
        } ?: error.javaClass.simpleName

    /** The security group from the cluster state, or the precondition that stops the command. */
    private sealed interface GroupLookup {
        data class Found(
            val securityGroupId: SecurityGroupId,
        ) : GroupLookup

        data class Missing(
            val result: IngressResult<Nothing>,
        ) : GroupLookup
    }

    /** The CIDRs to change, or the precondition that stops the command before any AWS call. */
    private sealed interface Targets {
        data class Resolved(
            val cidrs: List<Ipv4Cidr>,
            val detected: Boolean,
        ) : Targets

        data class Rejected(
            val result: IngressResult<Nothing>,
        ) : Targets
    }
}
