package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.exceptions.AwsTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/**
 * [pollUntilOrTimeout]: the one place every AWS wait turns "never converged" into an
 * [AwsTimeoutException], and a transient look failure (a throttle, a 5xx, a network failure) into a
 * retry. A failure that waiting cannot fix (access denied, expired credentials, a defect) ends the
 * wait at once instead of holding `up` or `down` for the whole timeout.
 */
class PollUntilOrTimeoutTest {
    private val looks = AtomicInteger(0)

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun wait(
        timeout: Duration = Duration.ofSeconds(5),
        interval: Duration = Duration.ofMillis(1),
        poll: () -> String,
    ): String =
        pollUntilOrTimeout(
            what = "thing to be ready",
            timeout = timeout,
            interval = interval,
            done = { it == "ready" },
        ) {
            looks.incrementAndGet()
            poll()
        }

    private fun awsFailure(
        status: Int,
        code: String? = null,
    ): AwsServiceException =
        AwsServiceException
            .builder()
            .statusCode(status)
            .message("failure $status")
            .apply { code?.let { awsErrorDetails(AwsErrorDetails.builder().errorCode(it).build()) } }
            .build()

    @Test
    fun `returns the first converged result with one look per iteration`() {
        val result = wait { if (looks.get() < 3) "pending" else "ready" }

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(3)
    }

    @Test
    fun `a zero interval polls until the result converges`() {
        val result = wait(interval = Duration.ZERO) { if (looks.get() < 50) "pending" else "ready" }

        assertThat(result).isEqualTo("ready")
        assertThat(looks.get()).isEqualTo(50)
    }

    @ParameterizedTest(name = "{0} is retried")
    @MethodSource("transientFailures")
    fun `a transient failure is retried and does not fail the wait`(
        name: String,
        failure: Throwable,
    ) {
        val result = wait { if (looks.get() < 3) throw failure else "ready" }

        assertThat(result).describedAs(name).isEqualTo("ready")
        assertThat(looks.get()).describedAs(name).isEqualTo(3)
    }

    @ParameterizedTest(name = "{0} ends the wait at once")
    @MethodSource("permanentFailures")
    fun `a failure that waiting cannot fix ends the wait at once with its own exception`(
        name: String,
        failure: Throwable,
    ) {
        assertThatThrownBy { wait { throw failure } }.describedAs(name).isSameAs(failure)
        assertThat(looks.get()).describedAs(name).isEqualTo(1)
    }

    @Test
    fun `throws AwsTimeoutException naming the wait and the timeout when it never converges`() {
        assertThatThrownBy { wait(timeout = Duration.ofMillis(50)) { "pending" } }
            .isInstanceOf(AwsTimeoutException::class.java)
            .hasMessage("Timeout waiting for thing to be ready after 50ms")
    }

    @Test
    fun `a look that keeps failing transiently surfaces its own exception, not a timeout`() {
        val throttle = awsFailure(429)

        assertThatThrownBy { wait(timeout = Duration.ofMillis(50)) { throw throttle } }.isSameAs(throttle)
    }

    @Test
    fun `an interrupted look ends the wait at once and restores the interrupt flag`() {
        assertThatThrownBy { wait { throw InterruptedException("stop") } }.isInstanceOf(InterruptedException::class.java)

        assertThat(looks.get()).isEqualTo(1)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    companion object {
        @JvmStatic
        fun transientFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "HTTP 429",
                    AwsServiceException
                        .builder()
                        .statusCode(429)
                        .message("Rate exceeded")
                        .build(),
                ),
                Arguments.of(
                    "ThrottlingException",
                    AwsServiceException
                        .builder()
                        .statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build())
                        .message("Rate exceeded")
                        .build(),
                ),
                Arguments.of(
                    "HTTP 503",
                    AwsServiceException
                        .builder()
                        .statusCode(503)
                        .message("unavailable")
                        .build(),
                ),
                Arguments.of(
                    "SdkClientException caused by an IOException",
                    SdkClientException.create("Unable to execute HTTP request", IOException("connection reset")),
                ),
                Arguments.of("IOException", IOException("connection reset")),
            )

        @JvmStatic
        fun permanentFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "HTTP 403",
                    AwsServiceException
                        .builder()
                        .statusCode(403)
                        .message("AccessDenied")
                        .build(),
                ),
                Arguments.of(
                    "HTTP 400",
                    AwsServiceException
                        .builder()
                        .statusCode(400)
                        .message("ValidationException")
                        .build(),
                ),
                Arguments.of("IllegalStateException", IllegalStateException("a defect")),
            )
    }
}
