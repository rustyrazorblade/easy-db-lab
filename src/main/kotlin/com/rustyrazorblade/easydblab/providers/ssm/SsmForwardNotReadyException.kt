package com.rustyrazorblade.easydblab.providers.ssm

/**
 * An SSM port-forwarding session exited, or timed out, before it reported that it was listening.
 *
 * The usual cause on a fresh node is `TargetNotConnected`: the node's SSM agent has not registered
 * yet. That is transient, so the exception is unchecked on purpose. resilience4j's
 * `decorateSupplier` and `decorateRunnable` catch only [RuntimeException], so a checked exception
 * would bypass both `up`'s SSH-readiness retry and the SSH operations retry and abort on the first
 * attempt. The SSH connection retry policy lists this type explicitly.
 *
 * @property instanceId the instance the session targeted
 * @property transcript the most recent Session Manager plugin output, which names the real cause
 */
class SsmForwardNotReadyException(
    val instanceId: String,
    val transcript: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("$reason Session Manager plugin output:\n$transcript", cause)
