package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.proxy.LoopbackPortSelector
import com.rustyrazorblade.easydblab.proxy.ProxiedHttpClientFactory
import com.rustyrazorblade.easydblab.services.DefaultObservabilityHttp
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [PortForwardServiceAccess] over [Fabric8ServiceForwarder] against a real K3s cluster: the forward
 * carries traffic to the Service, listens on the IPv4 loopback only, fails naming a missing Service,
 * and stops listening once the seam is closed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortForwardServiceAccessIntegrationTest {
    companion object {
        private const val NAMESPACE = "port-forward-access"
        private const val SERVICE = "web"
        private const val HTTP_PORT = 8080
        private const val NODE_PORT = 30880
        private const val BODY = "forwarded"
        private const val READY_TIMEOUT_SECONDS = 120L
        private const val STALE_READ_SECONDS = 10L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val CLOSE_ATTEMPTS = 50
        private val CLOSE_POLL = Duration.ofMillis(100)

        // The real-tunnel tests' range, so a run never collides with a live tunnel on the machine.
        private val TEST_PORTS = 10_000..19_999
    }

    private lateinit var client: KubernetesClient

    private val target = ServiceTarget(NAMESPACE, SERVICE, HTTP_PORT, ServerType.Control, HTTP_PORT)

    private fun newAccess() =
        PortForwardServiceAccess(
            Fabric8ServiceForwarder(SharedK3s::client),
            LoopbackPortSelector(candidates = { TEST_PORTS.random() })::select,
        )

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        createWebPod(SERVICE)
        createService(SERVICE, nodePort = NODE_PORT)
    }

    /** A busybox httpd pod labelled `app=`[name] that serves [BODY], once it is ready. */
    private fun createWebPod(name: String) {
        client
            .resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(NAMESPACE)
                    .withLabels<String, String>(mapOf("app" to name))
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withName(name)
                    .withImage(SharedK3s.BUSYBOX_IMAGE)
                    // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
                    .withImagePullPolicy("Never")
                    .withCommand("sh", "-c", "mkdir -p /www && echo $BODY > /www/index.html && httpd -f -p $HTTP_PORT -h /www")
                    .withNewReadinessProbe()
                    .withNewTcpSocket()
                    .withPort(IntOrString(HTTP_PORT))
                    .endTcpSocket()
                    .withPeriodSeconds(1)
                    .endReadinessProbe()
                    .endContainer()
                    .endSpec()
                    .build(),
            ).create()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(name)
            .waitUntilReady(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** A Service [name] selecting `app=`[name] on [HTTP_PORT]; a NodePort Service when [nodePort] is given. */
    private fun createService(
        name: String,
        nodePort: Int? = null,
    ) {
        client
            .resource(
                ServiceBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(NAMESPACE)
                    .endMetadata()
                    .withNewSpec()
                    .withType(if (nodePort == null) "ClusterIP" else "NodePort")
                    .withSelector<String, String>(mapOf("app" to name))
                    .addNewPort()
                    .withPort(HTTP_PORT)
                    .withTargetPort(IntOrString(HTTP_PORT))
                    .withNodePort(nodePort)
                    .endPort()
                    .endSpec()
                    .build(),
            ).create()
    }

    @AfterAll
    fun tearDown() {
        client.namespaces().withName(NAMESPACE).delete()
        client.close()
    }

    private fun get(port: Int): String =
        URI("http://127.0.0.1:$port/")
            .toURL()
            .openStream()
            .use { it.readAllBytes().decodeToString() }
            .trim()

    private fun accepts(
        address: InetAddress,
        port: Int,
    ): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS) }
        }.isSuccess

    @Test
    fun `the forward carries traffic to the Service and listens on the IPv4 loopback only`() {
        newAccess().use { access ->
            val endpoint = access.open(target) as ServiceEndpoint.Plain

            assertThat(endpoint.host).isEqualTo("127.0.0.1")
            assertThat(endpoint.port).isIn(TEST_PORTS)
            assertThat(get(endpoint.port)).isEqualTo(BODY)
            assertThat(accepts(externalProbeAddress(), endpoint.port)).isFalse()
        }
    }

    /**
     * A non-loopback IPv4 address of this machine that refuses a port nothing listens on, so a refusal
     * there shows what is bound. A VPN tunnel interface (a corporate VPN's `utun`) can accept every
     * connection whatever is listening, which would make any port look reachable.
     */
    private fun externalProbeAddress(): InetAddress {
        val unused = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val candidates =
            NetworkInterface
                .networkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
        return checkNotNull(candidates.firstOrNull { !accepts(it, unused) }) {
            "No non-loopback IPv4 address refuses an unused port ($candidates); cannot check the loopback-only bind"
        }
    }

    @Test
    fun `a kit endpoint's NodePort is forwarded to the Service port behind it`() {
        newAccess().use { access ->
            val endpoint = access.open(target.copy(servicePort = NODE_PORT, nodePort = NODE_PORT)) as ServiceEndpoint.Plain

            assertThat(get(endpoint.port)).isEqualTo(BODY)
        }
    }

    @Test
    fun `a missing Service fails naming its namespace, name and port`() {
        newAccess().use { access ->
            assertThatThrownBy { access.open(target.copy(service = "absent")) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("$NAMESPACE/absent")
                .hasMessageContaining("port $HTTP_PORT")
        }
    }

    /**
     * The race the bind retry exists for: another process binds the chosen port before the forward
     * does. Fabric8's real bind failure (an `IllegalStateException` over a `BindException`) must be
     * recognised, so the forward moves to the next port and still carries traffic.
     */
    @Test
    fun `a port another process holds is given up for a fresh one, and the forward works`() {
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { holder ->
            val fresh = TEST_PORTS.random()
            val picks = ArrayDeque(listOf(holder.localPort, fresh))
            PortForwardServiceAccess(Fabric8ServiceForwarder(SharedK3s::client)) { picks.removeFirst() }.use { access ->
                val endpoint = access.open(target) as ServiceEndpoint.Plain

                assertThat(endpoint.port).isEqualTo(fresh)
                assertThat(get(endpoint.port)).isEqualTo(BODY)
            }
        }
    }

    /**
     * A long-running reader (the server, a REPL) holds a forward to a pod that is then replaced. At
     * most one request through the stale forward fails: the forward records the error, so the request
     * after it reopens the forward onto the new pod and succeeds.
     */
    @Test
    fun `after its pod is replaced, one request through the stale forward fails and the next one works`() {
        val replaced = "replaced"
        val replacedTarget = target.copy(service = replaced)
        createWebPod(replaced)
        createService(replaced)
        newAccess().use { access ->
            val http = DefaultObservabilityHttp(access, { "acme" }, ProxiedHttpClientFactory())
            assertThat(http.get(replacedTarget, "/").body.trim()).isEqualTo(BODY)

            val pod = client.pods().inNamespace(NAMESPACE).withName(replaced)
            pod.withGracePeriod(0).delete()
            pod.waitUntilCondition({ it == null }, READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            createWebPod(replaced)

            // The forward may already have recorded the closed stream, and reopen before this request;
            // if it has not, this one request fails and records it. Either way it never hangs.
            runCatching { http.get(replacedTarget, "/", Duration.ofSeconds(STALE_READ_SECONDS)) }
            assertThat(http.get(replacedTarget, "/").body.trim()).isEqualTo(BODY)
        }
    }

    @Test
    fun `closing the seam closes its forwards`() {
        val access = newAccess()
        val endpoint = access.open(target) as ServiceEndpoint.Plain
        assertThat(get(endpoint.port)).isEqualTo(BODY)

        access.close()

        assertThat(acceptsAfterClose(endpoint.port)).isFalse()
    }

    /**
     * Whether `127.0.0.1:`[port] still accepts once it has had up to five seconds to stop. On Linux, a
     * connect made the moment Fabric8 closes its listening channel, while its accept thread is still
     * blocked in `accept()`, can land on the closing listener; the port refuses within milliseconds.
     */
    private fun acceptsAfterClose(port: Int): Boolean {
        val loopback = InetAddress.getByName("127.0.0.1")
        val config =
            RetryConfig
                .custom<Boolean>()
                .maxAttempts(CLOSE_ATTEMPTS)
                .waitDuration(CLOSE_POLL)
                .retryOnResult { it }
                .build()
        return Retry.of("forward closed", config).executeSupplier { accepts(loopback, port) }
    }
}
