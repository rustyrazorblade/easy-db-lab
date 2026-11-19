package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.providers.ssm.SsmForwardNotReadyException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.sshd.common.SshException

private val log = KotlinLogging.logger {}

/**
 * Whether [throwable] is a node whose SSH is not up yet and may answer if asked again: MINA's
 * [SshException] (refused, timed out, key exchange or auth while booting), or under the `ssm`
 * transport an SSM agent that has not registered yet ([SsmForwardNotReadyException]). Every
 * other failure will not clear by waiting. [com.rustyrazorblade.easydblab.providers.aws.RetryUtil.createSshConnectionRetryConfig]
 * retries on it, and each node's SSH step in `up` uses it to tell a booting node from a broken one.
 */
fun isSshNotReady(throwable: Throwable): Boolean =
    when (throwable) {
        is SsmForwardNotReadyException -> {
            log.debug { "SSM port forward not ready - will retry: ${throwable.message}" }
            true
        }
        is SshException -> {
            log.debug { "SSH not ready - will retry: ${throwable.message}" }
            true
        }
        else -> false
    }
