package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.HttpClientFactory
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
 * it, so no socket is opened.
 */
class ObservabilityHttpTest {
    private val control = ClusterHost("54.0.0.1", "10.0.1.5", "control0", "us-west-2a")
    private val requests = mutableListOf<Request>()
    private val readTimeouts = mutableListOf<Int>()
    private val socks = mock<SocksProxyService>()
    private val stateManager = mock<ClusterStateManager>()

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

    private val http = DefaultObservabilityHttp(factory, socks, stateManager)

    private fun state(tailscale: Boolean = false) =
        ClusterState(
            name = "lab",
            initConfig = InitConfig(tenant = "acme"),
            hosts = mapOf(ServerType.Control to listOf(control)),
        ).also { it.tailscaleActive = tailscale }

    @Test
    fun `a GET reaches the service on the control node with the cluster's tenant`() {
        whenever(stateManager.load()).thenReturn(state())

        val response = http.get(3100, "/loki/api/v1/labels")

        assertThat(response).isEqualTo(ObservabilityResponse(204, "done"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("http://10.0.1.5:3100/loki/api/v1/labels")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
    }

    @Test
    fun `a POST carries its body, content type and the tenant`() {
        whenever(stateManager.load()).thenReturn(state())

        http.post(9009, "/ingester/shutdown", body = "{}", contentType = "application/json")

        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.header("X-Scope-OrgID")).isEqualTo("acme")
        assertThat(request.body?.contentType().toString()).startsWith("application/json")
        assertThat(Buffer().also { request.body?.writeTo(it) }.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `the SOCKS tunnel is opened unless Tailscale reaches the private address`() {
        whenever(stateManager.load()).thenReturn(state(tailscale = false))
        http.get(3100, "/ready")
        verify(socks).ensureRunning(control)

        val direct = mock<SocksProxyService>()
        whenever(stateManager.load()).thenReturn(state(tailscale = true))
        DefaultObservabilityHttp(factory, direct, stateManager).get(3100, "/ready")
        verify(direct, never()).ensureRunning(any())
    }

    @Test
    fun `a long call gets its own read timeout`() {
        whenever(stateManager.load()).thenReturn(state())

        http.post(3100, "/ingester/shutdown", timeout = Duration.ofSeconds(600))

        assertThat(readTimeouts.single()).isEqualTo(600_000)
    }

    private val target = ControlNodeTarget("10.0.1.5", "acme")

    private fun answering(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { handler(it) }).build()

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
                    blocked.controlNodeCall(target, 9009, "/q", Duration.ofSeconds(30)) { it.get() }
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

        assertThatThrownBy { failing.controlNodeCall(target, 9009, "/q", Duration.ofSeconds(30)) { it.get() } }
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

        val response = answered.controlNodeCall(target, 9009, "/q", Duration.ofSeconds(30)) { it.get() }

        assertThat(response).isEqualTo(ObservabilityResponse(200, "result"))
        assertThat(closed.get()).describedAs("response body closed").isTrue()
    }

    @Test
    fun `a cluster with no control node fails fast`() {
        whenever(stateManager.load()).thenReturn(ClusterState(name = "lab"))

        assertThatThrownBy { http.get(3100, "/ready") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("control node")
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
