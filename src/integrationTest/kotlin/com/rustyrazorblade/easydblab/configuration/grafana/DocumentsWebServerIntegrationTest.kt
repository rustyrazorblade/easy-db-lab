package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

/**
 * The documents web server, with its real configuration, in a real pod: it forwards a `GET` under
 * `/reports/` to the signing proxy with the bucket fixed and the query string dropped, and refuses
 * every write and every path outside `/reports/`, `..` included.
 *
 * The signing proxy is replaced by a stub nginx on the proxy's loopback port that echoes the
 * request it receives. Requests are sent from inside the pod with raw HTTP, so a path such as
 * `/reports/../mimir/` reaches the web server unnormalized.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentsWebServerIntegrationTest {
    private companion object {
        const val NAMESPACE = "documents-web"
        const val POD = "documents-web"
        const val STUB_CONFIGMAP = "stub-s3"
        const val BUCKET = "acct-bucket"
        const val READY_TIMEOUT_SECONDS = 180L
        const val EXEC_TIMEOUT_SECONDS = 30L
    }

    private lateinit var client: KubernetesClient

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
        val sidecars = GrafanaDocumentsSidecars(TemplateService(stateManager, mock<User>()))
        val documents = DocumentsBucket(BUCKET, "eu-west-1")

        val webConfig =
            ConfigMapBuilder(sidecars.configMap(documents))
                .editMetadata()
                .withNamespace(NAMESPACE)
                .endMetadata()
                .build()
        val stubConfig =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName(STUB_CONFIGMAP)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .addToData("nginx.conf", checkNotNull(javaClass.getResource("stub-s3-nginx.conf")).readText())
                .build()
        // The web server exactly as the Grafana pod runs it, less the host port, which this pod does not need.
        val web =
            ContainerBuilder(sidecars.containers(documents).single { it.name == "documents-web" })
                .editFirstPort()
                .withHostPort(null)
                .endPort()
                .withNewReadinessProbe()
                .withNewTcpSocket()
                .withPort(IntOrString(Constants.Grafana.Documents.WEB_PORT))
                .endTcpSocket()
                .withPeriodSeconds(1)
                .endReadinessProbe()
                .build()
        val stub =
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
        val pod =
            PodBuilder()
                .withNewMetadata()
                .withName(POD)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withContainers(web, stub)
                .withVolumes(sidecars.volume())
                .addNewVolume()
                .withName("stub-config")
                .withNewConfigMap()
                .withName(STUB_CONFIGMAP)
                .endConfigMap()
                .endVolume()
                .endSpec()
                .build()

        client.resource(webConfig).create()
        client.resource(stubConfig).create()
        client.resource(pod).create()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(POD)
            .waitUntilReady(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    /** Sends one raw HTTP/1.0 request line to the web server from inside the pod and returns the whole response. */
    private fun request(requestLine: String): String {
        // stdin stays open for a while: nginx drops a proxied request whose client has already closed its side.
        val command = "(printf '%s\\r\\n\\r\\n' '$requestLine'; sleep 2) | nc -w 5 127.0.0.1 ${Constants.Grafana.Documents.WEB_PORT}"
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
    fun `a GET under reports is forwarded to the proxy with the bucket prefixed and the query string dropped`() {
        val response = request("GET /reports/acme/lab-1/index.html?list-type=2&prefix=mimir HTTP/1.0")

        assertThat(status(response)).contains(" 200 ")
        assertThat(response).contains("upstream saw GET /$BUCKET/reports/acme/lab-1/index.html\n")
    }

    @Test
    fun `writes are refused before they reach the proxy`() {
        for (method in listOf("PUT", "POST", "DELETE")) {
            val response = request("$method /reports/acme/lab-1/index.html HTTP/1.0")

            assertThat(status(response)).describedAs(method).contains(" 405 ")
            assertThat(response).describedAs(method).doesNotContain("upstream saw")
        }
    }

    @Test
    fun `an encoded line break or question mark in the path is refused and never reaches the proxy`() {
        // Decoded, %0d%0a would split the proxied request into a second one, and %3F would add a query.
        for (path in listOf(
            "/reports/a%0d%0aX",
            "/reports/a%0d%0aDELETE%20/acct-bucket/x",
            "/reports/a%3Facl",
            "/reports/a%3Flist-type=2",
        )) {
            val response = request("GET $path HTTP/1.0")

            assertThat(status(response)).describedAs(path).contains(" 400 ")
            assertThat(response).describedAs(path).doesNotContain("upstream saw")
        }
    }

    @Test
    fun `a path outside reports is refused, a normalized dot-dot path included`() {
        for (path in listOf("/mimir/", "/reports/../mimir/", "/reports/%2e%2e/loki/", "/")) {
            val response = request("GET $path HTTP/1.0")

            assertThat(status(response)).describedAs(path).contains(" 404 ")
            assertThat(response).describedAs(path).doesNotContain("upstream saw")
        }
    }
}
