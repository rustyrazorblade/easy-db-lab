package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.services.access.ClusterServices
import com.rustyrazorblade.easydblab.services.access.ServiceAccess
import com.rustyrazorblade.easydblab.services.access.ServiceEndpoint
import com.rustyrazorblade.easydblab.services.access.ServiceTarget
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The HTTP client stands in at the network boundary: an interceptor records each request and answers
 * it, so no socket is opened. The seam stands in for the route: it hands out a fixed endpoint per
 * Service and records each open.
 */
class ObservabilityHttpTest {
    private val requests = mutableListOf<Request>()
    private val readTimeouts = mutableListOf<Int>()
    private val opened = mutableListOf<ServiceTarget>()

    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requests.add(chain.request())
                    readTimeouts.add(chain.readTimeoutMillis())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .body("done".toResponseBody())
                        .build()
                },
            ).build()

    private val factory =
        object : HttpClientFactory {
            override fun createClient(): OkHttpClient = client

            override fun close() = Unit
        }

    private val access =
        object : ServiceAccess {
            override fun open(target: ServiceTarget): ServiceEndpoint {
                opened.add(target)
                return ServiceEndpoint.ViaSocks("10.0.1.5", target.nodePort, 23456)
            }
        }

    private val http = DefaultObservabilityHttp(access, { "acme" }, factory)

    @Test
    fun `a GET reaches the Service at the endpoint the seam opened, with the cluster's tenant`() {
        val response = http.get(ClusterServices.LOKI, "/loki/api/v1/labels")

        assertThat(response).isEqualTo(ObservabilityResponse(204, "done"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:31100/loki/api/v1/labels")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(opened).containsExactly(ClusterServices.LOKI)
    }

    @Test
    fun `a POST carries its body, content type and the tenant`() {
        http.post(ClusterServices.MIMIR, "/ingester/shutdown", body = "{}", contentType = "application/json")

        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:31009/ingester/shutdown")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(request.body?.contentType().toString()).startsWith("application/json")
        assertThat(Buffer().also { request.body?.writeTo(it) }.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `every request opens its Service again, so a replaced route is followed`() {
        http.get(ClusterServices.LOKI, "/ready")
        http.get(ClusterServices.LOKI, "/ready")

        assertThat(opened).containsExactly(ClusterServices.LOKI, ClusterServices.LOKI)
    }

    @Test
    fun `a long call gets its own read timeout`() {
        http.post(ClusterServices.LOKI, "/ingester/shutdown", timeout = Duration.ofSeconds(600))

        assertThat(readTimeouts.single()).isEqualTo(600_000)
    }

    /** An [ObservabilityHttp] over [access] whose network answers every call with [handler]. */
    private fun answering(handler: (Interceptor.Chain) -> Response): ObservabilityHttp {
        val answeringClient = OkHttpClient.Builder().addInterceptor(Interceptor { handler(it) }).build()
        val answeringFactory =
            object : HttpClientFactory {
                override fun createClient(): OkHttpClient = answeringClient

                override fun close() = Unit
            }
        return DefaultObservabilityHttp(access, { "acme" }, answeringFactory)
    }

    @Test
    fun `an interrupt of the waiting thread cancels the call and keeps the interrupt flag`() {
        val inFlight = CountDownLatch(1)
        val interceptorDone = CountDownLatch(1)
        val sawCancel = AtomicBoolean(false)
        val blocked =
            answering { chain ->
                inFlight.countDown()
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
                sawCancel.set(chain.call().isCanceled())
                interceptorDone.countDown()
                throw IOException("canceled")
            }
        val failure = AtomicReference<Throwable>()
        val flagAfter = AtomicBoolean(false)
        val worker =
            thread {
                try {
                    blocked.get(ClusterServices.MIMIR, "/q")
                } catch (e: IOException) {
                    failure.set(e)
                }
                flagAfter.set(Thread.currentThread().isInterrupted)
            }

        assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue()
        worker.interrupt()
        worker.join(Duration.ofSeconds(5).toMillis())

        assertThat(worker.isAlive).describedAs("the waiting thread did not return after the interrupt").isFalse()
        assertThat(failure.get()).isInstanceOf(InterruptedIOException::class.java).hasCauseInstanceOf(InterruptedException::class.java)
        assertThat(flagAfter.get()).describedAs("interrupt flag").isTrue()
        // The waiting thread returns as soon as the call is cancelled; the interceptor notices a moment later.
        assertThat(interceptorDone.await(5, TimeUnit.SECONDS)).describedAs("the interceptor saw the call end").isTrue()
        assertThat(sawCancel.get()).describedAs("the in-flight call was cancelled").isTrue()
    }

    @Test
    fun `a failed call throws the IOException the network raised, not a wrapper`() {
        val failing = answering { throw SocketTimeoutException("read timed out") }

        assertThatThrownBy { failing.get(ClusterServices.MIMIR, "/q") }
            .isExactlyInstanceOf(SocketTimeoutException::class.java)
            .hasMessage("read timed out")
    }

    @Test
    fun `the answer's body is read and closed inside the call`() {
        val closed = AtomicBoolean(false)
        val body = ClosingSource(Buffer().writeUtf8("result"), closed).buffer().asResponseBody(null, -1)
        val answered =
            answering { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }

        val response = answered.get(ClusterServices.MIMIR, "/q")

        assertThat(response).isEqualTo(ObservabilityResponse(200, "result"))
        assertThat(closed.get()).describedAs("response body closed").isTrue()
    }

    @Test
    fun `a Service the seam cannot open fails before any request is sent`() {
        val unreachable =
            DefaultObservabilityHttp(
                { error("No control node in cluster state runs default/loki") },
                { "acme" },
                factory,
            )

        assertThatThrownBy { unreachable.get(ClusterServices.LOKI, "/ready") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node")
        assertThat(requests).isEmpty()
    }
}

/** A source over [delegate] that sets [closed] when it is closed, to show a response body was closed. */
private class ClosingSource(
    delegate: Source,
    private val closed: AtomicBoolean,
) : ForwardingSource(delegate) {
    override fun close() {
        closed.set(true)
        super.close()
    }
}
