package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ConfigHashAnnotator
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
 * Tests for [GrafanaDocumentsSidecars]: the signing proxy stays on loopback and signs for the
 * bucket's region, the web server is the only one on the host port, both images are pinned, and the
 * rendered web server configuration refuses anything but a GET under `/reports/`.
 */
class GrafanaDocumentsSidecarsTest : BaseKoinTest() {
    private val documents = DocumentsBucket("acct-bucket", "eu-west-1")
    private lateinit var sidecars: GrafanaDocumentsSidecars
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
        sidecars = GrafanaDocumentsSidecars(templateService)
    }

    private fun container(name: String) = sidecars.containers(documents).single { it.name == name }

    @Test
    fun `the proxy listens on loopback only and signs for the bucket's region`() {
        val proxy = container("documents-sigv4-proxy")

        assertThat(proxy.args).containsSequence("--port", "127.0.0.1:${Constants.Grafana.Documents.PROXY_PORT}")
        assertThat(proxy.args).containsSequence("--region", "eu-west-1")
        assertThat(proxy.args).containsSequence("--host", "s3.eu-west-1.amazonaws.com")
        assertThat(proxy.args).containsSequence("--name", "s3")
        assertThat(proxy.ports.map { it.hostPort }).containsOnlyNulls()
    }

    @Test
    fun `only the web server is published on the host, on its own port and not the renderer's`() {
        val web = container("documents-web")

        assertThat(web.ports.single().hostPort).isEqualTo(Constants.Grafana.Documents.WEB_PORT)
        assertThat(setOf(Constants.Grafana.Documents.WEB_PORT, Constants.Grafana.Documents.PROXY_PORT)).doesNotContain(8081)
        assertThat(web.volumeMounts.single().mountPath).isEqualTo("/etc/nginx/nginx.conf")
    }

    @Test
    fun `both images name a released version`() {
        val images = sidecars.containers(documents).map { it.image }

        assertThat(images).containsExactly(Constants.Grafana.Documents.SIGV4_PROXY_IMAGE, Constants.Grafana.Documents.WEB_SERVER_IMAGE)
        assertThat(images).allSatisfy { image ->
            val tag = image.substringAfterLast(':')
            assertThat(tag).isNotEqualTo("latest").matches("v?\\d+\\.\\d+.*")
        }
    }

    @Test
    fun `the web server forwards only GET under reports to the proxy with the bucket fixed and no query string`() {
        val config = sidecars.webServerConfig(documents)

        assertThat(config).contains(
            "listen ${Constants.Grafana.Documents.WEB_PORT};",
            "location /reports/ {",
            "if (\$request_method != GET) {",
            "return 405;",
            "if (\$uri !~ \"^/reports/[A-Za-z0-9._/-]+\$\") {",
            "return 400;",
            "set \$args \"\";",
            "proxy_pass http://127.0.0.1:${Constants.Grafana.Documents.PROXY_PORT}/acct-bucket/reports/;",
        )
        assertThat(config).containsPattern("location / \\{\\s*return 404;")
        // A variable in proxy_pass would send the decoded path unescaped: an encoded line break would split the request.
        assertThat(config.lines().filter { "proxy_pass" in it }).noneMatch { "\$" in it }
        assertThat(config).doesNotContain("\$request_uri", "__")
    }

    @Test
    fun `a change to the web server configuration changes the Grafana pod's config hash`() {
        val builder = GrafanaManifestBuilder(templateService)

        fun hash(bucket: String): String? =
            ConfigHashAnnotator
                .annotate(builder.buildAllResources(DocumentsBucket(bucket, "eu-west-1")), mapOf("grafana-datasources" to emptyMap()))
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.metadata.annotations[Constants.K8s.CONFIG_HASH_ANNOTATION]

        assertThat(hash("acct-bucket")).isNotNull().isEqualTo(hash("acct-bucket")).isNotEqualTo(hash("other-bucket"))
    }
}
