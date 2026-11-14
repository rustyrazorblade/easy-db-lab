package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The nginx front of the Grafana pod, with its real configuration, in a real pod, reached the way a
 * browser reaches Grafana: through a ClusterIP Service on Grafana's one client port, the port the
 * grafana Service (#1090) targets and a `127.0.0.1` port-forward carries. Through that one port a
 * `GET` under `/reports/` reaches the signing proxy with the bucket fixed and the query string
 * dropped, every write under `/reports/` is refused, and every other path, a normalized `..` path
 * included, reaches Grafana with its Host header, websocket upgrade and body intact.
 *
 * Both upstreams are one stub nginx, on the signing proxy's loopback port and on Grafana's internal
 * port, that echoes what it receives; the signing proxy's S3 call is the side effect it stands in
 * for. Requests are raw HTTP, so a path such as `/reports/../mimir/` reaches the front unnormalized.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrafanaFrontIntegrationTest {
    private companion object {
        const val NAMESPACE = "grafana-front"
        const val POD = "grafana-front"
        const val SERVICE = "grafana"
        const val STUB_CONFIGMAP = "stub-upstreams"
        const val BUCKET = "acct-bucket"
        const val READY_TIMEOUT_SECONDS = 180L
        const val EXEC_TIMEOUT_SECONDS = 30L
        const val SERVICE_WAIT_SECONDS = 60L
        const val GRAFANA_SAW = "grafana saw "
        const val PROXY_SAW = "upstream saw "
    }

    private lateinit var client: KubernetesClient

    /** The front's address as another pod reaches it: the Service, on Grafana's client port. */
    private val address = "$SERVICE.$NAMESPACE.svc ${Constants.K8s.GRAFANA_PORT}"

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()

        val stateManager = mock<ClusterStateManager>()
        whenever(
            stateManager.load(),
        ).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = BUCKET, initConfig = InitConfig(region = "eu-west-1")),
        )
        val front = GrafanaFront(TemplateService(stateManager, mock<User>()))
        val documents = DocumentsBucket(BUCKET, "eu-west-1")

        val frontConfig =
            ConfigMapBuilder(front.configMap(documents))
                .editMetadata()
                .withNamespace(NAMESPACE)
                .endMetadata()
                .build()

        client.resource(frontConfig).create()
        client.resource(stubConfigMap()).create()
        client.resource(service()).create()
        client.resource(pod(frontContainer(front, documents), stubContainer(), front)).create()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .waitUntilReady(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitService()
    }

    /**
     * Waits until the Service name resolves and connects: a Ready pod is in the Service's endpoints
     * only a moment later, and the first request otherwise races that.
     */
    private fun awaitService() {
        val command = "for i in \$(seq $SERVICE_WAIT_SECONDS); do nc -z -w 2 $address && exit 0; sleep 1; done; exit 1"
        val exit =
            client
                .pods()
                .inNamespace(NAMESPACE)
                .withName(POD)
                .inContainer("stub")
                .exec("sh", "-c", command)
                .use { it.exitCode().get(SERVICE_WAIT_SECONDS + EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        check(exit == 0) { "the $SERVICE Service did not answer on ${Constants.K8s.GRAFANA_PORT} within ${SERVICE_WAIT_SECONDS}s" }
    }

    /** The stub upstreams' nginx configuration, on the ports the front forwards to. */
    private fun stubConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(STUB_CONFIGMAP)
            .withNamespace(NAMESPACE)
            .endMetadata()
            .addToData(
                "nginx.conf",
                checkNotNull(javaClass.getResource("stub-upstreams-nginx.conf"))
                    .readText()
                    .replace(
                        "__PROXY_PORT__",
                        Constants.Grafana.Documents.PROXY_PORT
                            .toString(),
                    ).replace("__GRAFANA_INTERNAL_PORT__", Constants.Grafana.INTERNAL_PORT.toString()),
            ).build()

    /** A ClusterIP Service on Grafana's client port, standing in for the grafana Service #1090 adds. */
    private fun service() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(SERVICE)
            .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .addToSelector("app", POD)
            .addNewPort()
            .withPort(Constants.K8s.GRAFANA_PORT)
            .withTargetPort(IntOrString(Constants.K8s.GRAFANA_PORT))
            .endPort()
            .endSpec()
            .build()

    /** The front exactly as the Grafana pod runs it, less the host port, which this pod does not need. */
    private fun frontContainer(
        front: GrafanaFront,
        documents: DocumentsBucket,
    ) = ContainerBuilder(front.containers(documents).single { it.name == GrafanaFront.FRONT_CONTAINER })
        .editFirstPort()
        .withHostPort(null)
        .endPort()
        .build()

    /** The stub upstreams, which record every request they receive. */
    private fun stubContainer() =
        ContainerBuilder()
            .withName("stub")
            .withImage(Constants.Grafana.Documents.WEB_SERVER_IMAGE)
            .addNewVolumeMount()
            .withName("stub-config")
            .withMountPath("/etc/nginx/nginx.conf")
            .withSubPath("nginx.conf")
            .endVolumeMount()
            .withNewReadinessProbe()
            .withNewTcpSocket()
            .withPort(IntOrString(Constants.Grafana.Documents.PROXY_PORT))
            .endTcpSocket()
            .withPeriodSeconds(1)
            .endReadinessProbe()
            .build()

    private fun pod(
        front: Container,
        stub: Container,
        grafanaFront: GrafanaFront,
    ) = PodBuilder()
        .withNewMetadata()
        .withName(POD)
        .withNamespace(NAMESPACE)
        .addToLabels("app", POD)
        .endMetadata()
        .withNewSpec()
        .withContainers(front, stub)
        .withVolumes(grafanaFront.volume())
        .addNewVolume()
        .withName("stub-config")
        .withNewConfigMap()
        .withName(STUB_CONFIGMAP)
        .endConfigMap()
        .endVolume()
        .endSpec()
        .build()

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /**
     * Sends one raw HTTP/1.1 request to the front through its Service and returns the whole response.
     * [headers] are extra header lines; [body] is sent after them.
     */
    private fun request(
        requestLine: String,
        headers: List<String> = emptyList(),
        body: String = "",
    ): String {
        val connection = if (headers.any { it.startsWith("Upgrade:") }) "Connection: Upgrade" else "Connection: close"
        val lines = listOf(requestLine, "Host: grafana.test:${Constants.K8s.GRAFANA_PORT}", connection) + headers
        val raw = lines.joinToString("") { "$it\r\n" } + "\r\n" + body
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
        // stdin stays open for a while: nginx drops a proxied request whose client has already closed its side.
        val command = "(echo $encoded | base64 -d; sleep 2) | nc -w 5 $address"
        return client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .inContainer("stub")
            .redirectingOutput()
            .exec("sh", "-c", command)
            .use { watch ->
                // Read to the end of the stream: the exit code can arrive before the last of the output.
                val response = watch.output.readAllBytes().toString(Charsets.UTF_8)
                watch.exitCode().get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                response
            }
    }

    private fun status(response: String): String = response.lineSequence().first().trim()

    @Test
    fun `a document is served through Grafana's port from the proxy, with the bucket prefixed and the query string dropped`() {
        val response = request("GET /reports/acme/lab-1/index.html?list-type=2&prefix=mimir HTTP/1.1")

        assertThat(status(response)).contains(" 200 ")
        assertThat(response).contains("${PROXY_SAW}GET /$BUCKET/reports/acme/lab-1/index.html\n").doesNotContain(GRAFANA_SAW)
    }

    @Test
    fun `writes under reports are refused before they reach the proxy or Grafana`() {
        for (method in listOf("PUT", "POST", "DELETE", "PATCH")) {
            val response = request("$method /reports/acme/lab-1/index.html HTTP/1.1", listOf("Content-Length: 0"))

            assertThat(status(response)).describedAs(method).contains(" 405 ")
            assertThat(response).describedAs(method).doesNotContain(PROXY_SAW, GRAFANA_SAW)
        }
    }

    @Test
    fun `an encoded line break or question mark under reports is refused and never reaches the proxy`() {
        // Decoded, %0d%0a would split the proxied request into a second one, and %3F would add a query.
        for (path in listOf(
            "/reports/a%0d%0aX",
            "/reports/a%0d%0aDELETE%20/acct-bucket/x",
            "/reports/a%3Facl",
            "/reports/a%3Flist-type=2",
        )) {
            val response = request("GET $path HTTP/1.1")

            assertThat(status(response)).describedAs(path).contains(" 400 ")
            assertThat(response).describedAs(path).doesNotContain(PROXY_SAW)
        }
    }

    @Test
    fun `a path outside reports goes to Grafana, never to the proxy, a normalized dot-dot path included`() {
        for ((path, normalized) in listOf(
            "/mimir/" to "/mimir/",
            "/reports/../mimir/" to "/mimir/",
            "/reports/%2e%2e/loki/" to "/loki/",
        )) {
            val response = request("GET $path HTTP/1.1")

            assertThat(response).describedAs(path).doesNotContain(PROXY_SAW).contains("${GRAFANA_SAW}GET ")
            assertThat(response.substringAfter(GRAFANA_SAW).substringBefore(" host=")).describedAs(path).endsWith(normalized)
        }
    }

    @Test
    fun `a Grafana API call reaches Grafana with its method, body and Host header`() {
        val body = """{"dashboard":{"title":"x"},"overwrite":true}"""

        val response =
            request(
                "POST /api/dashboards/db HTTP/1.1",
                listOf("Content-Type: application/json", "Content-Length: ${body.length}"),
                body,
            )

        assertThat(status(response)).contains(" 200 ")
        assertThat(response)
            .contains("${GRAFANA_SAW}POST /api/dashboards/db host=grafana.test:${Constants.K8s.GRAFANA_PORT} ")
            .contains("length=${body.length}")
            .doesNotContain(PROXY_SAW)
    }

    @Test
    fun `a Grafana Live websocket upgrade is passed through to Grafana`() {
        val response =
            request(
                "GET /api/live/ws HTTP/1.1",
                listOf("Upgrade: websocket", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="),
            )

        assertThat(response).contains("${GRAFANA_SAW}GET /api/live/ws ").contains("upgrade=websocket connection=upgrade")
    }
}
