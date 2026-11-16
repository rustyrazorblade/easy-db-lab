package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.TerminateSessionRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [SdkSsmSessionTerminator] builds its SSM client and fetches credentials off the main thread, while
 * the first forward gets ready, so the command's exit does not pay for them. SSM is an external
 * service, so its client is mocked; what is under test is when the client is built and used.
 */
class SdkSsmSessionTerminatorTest {
    private val ssmClient = mock<SsmClient>()
    private val builds = AtomicInteger(0)
    private val credentialFetches = AtomicInteger(0)
    private val credentials =
        AwsCredentialsProvider { AwsBasicCredentials.create("AKIATEST", "secret").also { credentialFetches.incrementAndGet() } }

    private fun terminator(build: () -> SsmClient = { ssmClient }) =
        SdkSsmSessionTerminator(credentials) {
            builds.incrementAndGet()
            build()
        }

    @Test
    fun `nothing is built or fetched until a forward starts`() {
        terminator()

        assertThat(builds.get()).isZero()
        assertThat(credentialFetches.get()).isZero()
    }

    @Test
    fun `the warm-up builds the client and fetches credentials once`() {
        val terminator = terminator()

        terminator.warmUp()
        terminator.warmUp()
        terminator.terminate("lab-session-1")

        assertThat(builds.get()).isEqualTo(1)
        assertThat(credentialFetches.get()).isEqualTo(1)
        verify(ssmClient).terminateSession(TerminateSessionRequest.builder().sessionId("lab-session-1").build())
    }

    @Test
    fun `terminate waits for a warm-up still in progress, then uses its client`() {
        val release = CountDownLatch(1)
        val terminator =
            terminator {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                ssmClient
            }
        terminator.warmUp()

        val call = thread { terminator.terminate("lab-session-1") }
        call.join(WAIT_MS)
        assertThat(call.isAlive).isTrue()
        verify(ssmClient, never()).terminateSession(any<TerminateSessionRequest>())

        release.countDown()
        call.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        verify(ssmClient).terminateSession(any<TerminateSessionRequest>())
        assertThat(builds.get()).isEqualTo(1)
    }

    /**
     * On a slow network the SSO credential fetch can time out during the warm-up. That failure must
     * not be kept: terminate prepares again, credentials included, and succeeds.
     */
    @Test
    fun `a failed warm-up is prepared again, credentials included, and terminate succeeds`() {
        val fetches = AtomicInteger(0)
        val flakyCredentials =
            AwsCredentialsProvider {
                check(fetches.incrementAndGet() > 1) { "Read timed out (SDK Attempt Count: 4)" }
                AwsBasicCredentials.create("AKIATEST", "secret")
            }
        val terminator =
            SdkSsmSessionTerminator(flakyCredentials) {
                builds.incrementAndGet()
                ssmClient
            }
        terminator.warmUp()

        terminator.terminate("lab-session-1")

        val request = argumentCaptor<TerminateSessionRequest>()
        verify(ssmClient, times(1)).terminateSession(request.capture())
        assertThat(request.firstValue.sessionId()).isEqualTo("lab-session-1")
        assertThat(fetches.get()).isEqualTo(2)
        assertThat(builds.get()).isEqualTo(2)
    }

    /** Several forwards stop together; after a failed warm-up they must share one new preparation. */
    @Test
    fun `concurrent terminates after a failed warm-up prepare once`() {
        val fetches = AtomicInteger(0)
        val release = CountDownLatch(1)
        val credentialsFailingOnce =
            AwsCredentialsProvider {
                val n = fetches.incrementAndGet()
                check(n > 1) { "Read timed out" }
                // Hold the second preparation open so every terminate is waiting on it at once.
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                AwsBasicCredentials.create("AKIATEST", "secret")
            }
        val terminator =
            SdkSsmSessionTerminator(credentialsFailingOnce) {
                builds.incrementAndGet()
                ssmClient
            }
        terminator.warmUp()
        waitUntil { fetches.get() >= 1 }

        val calls = (1..CONCURRENT_STOPS).map { n -> thread { terminator.terminate("lab-session-$n") } }
        Thread.sleep(WAIT_MS)
        release.countDown()
        calls.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }

        verify(ssmClient, times(CONCURRENT_STOPS)).terminateSession(any<TerminateSessionRequest>())
        assertThat(fetches.get()).isEqualTo(2)
        assertThat(builds.get()).isEqualTo(2)
    }

    /**
     * The total wait for TerminateSession is 20s, final per the owner, and the SDK's attempts
     * (three in its standard retry mode) must each fit inside it rather than one using it all.
     */
    @Test
    fun `the TerminateSession budget is 20s and each SDK attempt fits inside it`() {
        assertThat(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS).isEqualTo(20L)
        assertThat(Constants.Ssm.TERMINATE_SESSION_ATTEMPT_TIMEOUT_SECONDS * SDK_STANDARD_MAX_ATTEMPTS)
            .isLessThan(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val WAIT_MS = 300L
        const val POLL_MS = 10L
        const val CONCURRENT_STOPS = 4
        const val SDK_STANDARD_MAX_ATTEMPTS = 3
    }
}
