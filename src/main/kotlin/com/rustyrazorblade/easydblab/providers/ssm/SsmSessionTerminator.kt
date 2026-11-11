package com.rustyrazorblade.easydblab.providers.ssm

import io.github.oshai.kotlinlogging.KotlinLogging
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.TerminateSessionRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.concurrent.thread

/**
 * Ends a Session Manager session on the AWS side.
 *
 * Killing the local `aws` and `session-manager-plugin` processes does not end a session: AWS keeps
 * it "Connected" until its 20-minute idle timeout. Every forward [SsmSshRoute] stops is therefore
 * also terminated here. It is an interface so tests can record the calls instead of reaching AWS.
 */
fun interface SsmSessionTerminator {
    /** Ends the session [sessionId]. May throw; the caller logs the failure and carries on. */
    fun terminate(sessionId: String)

    /**
     * Prepares for [terminate] in the background, so the AWS client and credentials are ready by
     * the time a forward stops. Called when a forward starts; never throws, and safe to call often.
     */
    fun warmUp() = Unit
}

/**
 * [SsmSessionTerminator] over the AWS SDK. The client must sign as the identity that started the
 * session, which is the profile's SDK identity, the same one the AWS CLI uses for the session.
 *
 * Nothing is built at construction, which happens when Koin resolves the `ssm` route. [warmUp]
 * builds the client and resolves [credentialsProvider] on a daemon thread, which for an SSO profile
 * is a round trip to the SSO portal, so it overlaps the 2s or more a forward takes to get ready
 * instead of running after the command's work, on the way out. [terminate] waits for that warm-up
 * (its caller bounds the wait).
 *
 * A failed preparation is never kept: on a slow network the SSO fetch can time out once and work
 * the next time. The next [warmUp] or [terminate] starts a new preparation, client and credentials
 * both, and concurrent callers share it rather than each starting their own.
 *
 * @param credentialsProvider the profile's credentials, resolved during each preparation
 * @param buildClient builds the SSM client; called once per preparation
 */
class SdkSsmSessionTerminator(
    private val credentialsProvider: AwsCredentialsProvider,
    private val buildClient: () -> SsmClient,
) : SsmSessionTerminator {
    private var preparation: CompletableFuture<SsmClient>? = null

    override fun warmUp() {
        prepared()
    }

    /**
     * Ends [sessionId], preparing again once if the preparation it waited on failed.
     *
     * @throws Exception whatever the second preparation or the call itself failed with
     */
    override fun terminate(sessionId: String) {
        val client =
            try {
                prepared().get()
            } catch (e: ExecutionException) {
                log.warn(e.cause) { "Preparing the SSM client failed; preparing it again to end the session" }
                prepared().get()
            }
        client.terminateSession(TerminateSessionRequest.builder().sessionId(sessionId).build())
    }

    /** The current preparation, or a new one when there is none or the last one failed. */
    @Synchronized
    private fun prepared(): CompletableFuture<SsmClient> =
        preparation?.takeUnless { it.isCompletedExceptionally }
            ?: CompletableFuture<SsmClient>().also { future ->
                preparation = future
                thread(isDaemon = true, name = "ssm-terminator-warm-up") {
                    runCatching { buildClient().also { credentialsProvider.resolveCredentials() } }
                        .onSuccess { future.complete(it) }
                        .onFailure { future.completeExceptionally(it) }
                }
            }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
