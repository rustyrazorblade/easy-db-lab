package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [GrafanaFront]: the nginx in front of Grafana holds Grafana's client port and is the
 * pod's only published port, the signing proxy stays on loopback and signs for the bucket's region,
 * both images are pinned, and the rendered configuration sends only reads under `/reports/` to the
 * proxy and everything else to Grafana's loopback port. `GrafanaFrontIntegrationTest` runs it.
 */
class GrafanaFrontTest : BaseKoinTest() {
    private val documents = DocumentsBucket("acct-bucket", "eu-west-1")
    private lateinit var front: GrafanaFront
    private lateinit var templateService: TemplateService

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct-bucket"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        templateService = getKoin().get()
        front = GrafanaFront(templateService)
    }

    private fun container(name: String) = front.containers(documents).single { it.name == name }

    @Test
    fun `the proxy listens on loopback only and signs for the bucket's region`() {
        val proxy = container(GrafanaFront.PROXY_CONTAINER)

        assertThat(proxy.args).containsSequence("--port", "127.0.0.1:${Constants.Grafana.Documents.PROXY_PORT}")
        assertThat(proxy.args).containsSequence("--region", "eu-west-1")
        assertThat(proxy.args).containsSequence("--host", "s3.eu-west-1.amazonaws.com")
        assertThat(proxy.args).containsSequence("--name", "s3")
        assertThat(proxy.ports.map { it.hostPort }).containsOnlyNulls()
    }

    @Test
    fun `the front holds Grafana's client port, the one port Grafana's Service and the control node reach`() {
        val web = container(GrafanaFront.FRONT_CONTAINER)

        assertThat(web.ports.map { it.containerPort }).containsExactly(Constants.K8s.GRAFANA_PORT)
        assertThat(web.ports.single().hostPort).isEqualTo(Constants.K8s.GRAFANA_PORT)
        assertThat(setOf(Constants.Grafana.INTERNAL_PORT, Constants.Grafana.Documents.PROXY_PORT))
            .doesNotContain(Constants.K8s.GRAFANA_PORT, 8081)
        assertThat(web.volumeMounts.single().mountPath).isEqualTo("/etc/nginx/nginx.conf")
    }

    @Test
    fun `the front is Ready only once it reaches Grafana`() {
        val probe = container(GrafanaFront.FRONT_CONTAINER).readinessProbe.httpGet

        assertThat(probe.path).isEqualTo("/api/health")
        assertThat(probe.port.intVal).isEqualTo(Constants.K8s.GRAFANA_PORT)
    }

    @Test
    fun `both images name a released version`() {
        val images = front.containers(documents).map { it.image }

        assertThat(images).containsExactly(Constants.Grafana.Documents.SIGV4_PROXY_IMAGE, Constants.Grafana.Documents.WEB_SERVER_IMAGE)
        assertThat(images).allSatisfy { image ->
            val tag = image.substringAfterLast(':')
            assertThat(tag).isNotEqualTo("latest").matches("v?\\d+\\.\\d+.*")
        }
    }

    @Test
    fun `reports go to the proxy with the bucket fixed and no query string, everything else to Grafana's loopback port`() {
        val config = front.config(documents)

        assertThat(config).contains(
            "listen ${Constants.K8s.GRAFANA_PORT};",
            "location /reports/ {",
            "proxy_pass http://127.0.0.1:${Constants.Grafana.Documents.PROXY_PORT}/acct-bucket/reports/;",
            "set \$args \"\";",
            "proxy_pass http://127.0.0.1:${Constants.Grafana.INTERNAL_PORT};",
        )
        // A variable in proxy_pass would send the decoded path unescaped: an encoded line break would split the request.
        assertThat(config.lines().filter { "proxy_pass" in it }).noneMatch { "\$" in it }
        assertThat(config).doesNotContain("__")
    }

    @Test
    fun `a change to the front's configuration changes the Grafana pod's config hash`() {
        val builder = GrafanaManifestBuilder(templateService)

        fun hash(bucket: String): String? =
            ConfigHashAnnotator
                .annotate(
                    builder.buildAllResources(DocumentsBucket(bucket, "eu-west-1"), ObservabilityHosting.AWS),
                    mapOf("grafana-datasources" to emptyMap()),
                ).filterIsInstance<Deployment>()
                .single()
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]

        assertThat(hash("acct-bucket")).isNotNull().isEqualTo(hash("acct-bucket")).isNotEqualTo(hash("other-bucket"))
    }
}
