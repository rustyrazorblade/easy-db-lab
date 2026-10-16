package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.DashboardInstallContext
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.localstack.ComposeFile
import com.rustyrazorblade.easydblab.localstack.DashboardTreeSource
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.LocalStackManifest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * What an export carries besides the data: the offline compose stack and its configuration, the
 * dashboards after the install-time pass for the export, the manifest, and a README that lists the
 * images the opening machine needs and says profiles are left out (issue 1016).
 */
class ExportStackFilesTest {
    private val contexts = mutableListOf<DashboardInstallContext>()
    private val dashboards =
        object : DashboardTreeSource {
            override val origin: String = "packaged dashboards"

            override fun render(context: DashboardInstallContext): Map<String, String> {
                contexts += context
                return mapOf("home/home.json" to """{"uid":"home"}""", "cassandra/overview.json" to """{"uid":"o"}""")
            }
        }

    private val files = ExportStackFiles(dashboards, toolVersion = "16").files(tenant = "acme")

    private fun text(path: String) = String(checkNotNull(files[path]) { "no $path among ${files.keys}" })

    @Test
    fun `the export carries the offline compose stack with its configuration`() {
        val compose = ComposeFile.fromYaml(text(Constants.LocalStack.COMPOSE_FILE))

        assertThat(compose.services.keys).contains("mimir", "loki", "tempo", "grafana").doesNotContain("store-gate", "store-signer")
        assertThat(files.keys).contains("config/mimir.yaml", "config/loki.yaml", "config/tempo.yaml", "config/documents-nginx.conf")
    }

    @Test
    fun `the dashboards are installed for the export - its tenant, no current cluster or experiment, the documents on loopback`() {
        assertThat(files.keys).contains("dashboards/current/home/home.json", "dashboards/current/cassandra/overview.json")
        assertThat(contexts.single())
            .isEqualTo(
                DashboardInstallContext(
                    cluster = null,
                    experiment = null,
                    tenants = TenantSet.of("acme", listOf("acme")),
                    documentsUrl = "http://127.0.0.1:${Constants.LocalStack.DEFAULT_DOCUMENTS_PORT}",
                ),
            )
    }

    @Test
    fun `the README lists exactly the images the stack runs, each a registered pin, and says how to open it`() {
        val images = ComposeFile.fromYaml(text(Constants.LocalStack.COMPOSE_FILE)).services.values.map { it.image }.toSet()
        val readme = text(Constants.Export.README_FILE)
        val pulled = readme.lines().filter { it.trim().startsWith("docker pull ") }.map { it.trim().removePrefix("docker pull ") }

        assertThat(pulled).containsExactlyInAnyOrderElementsOf(images)
        assertThat(images).allSatisfy { assertThat(LocalImages.all).contains(it) }
        assertThat(readme).contains("docker compose up", "http://127.0.0.1:${Constants.LocalStack.DEFAULT_GRAFANA_PORT}", "acme")
    }

    @Test
    fun `the README says profiles are not included and names issue 1016`() {
        assertThat(text(Constants.Export.README_FILE)).contains("Profiles are not included", "#1016")
    }

    @Test
    fun `the manifest records the offline stack, so local dashboards works on the opened export`() {
        val manifest = LocalStackManifest.fromJson(text(Constants.LocalStack.MANIFEST_FILE))
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertThat(manifest.mode).isEqualTo("offline")
        assertThat(manifest.homeTenant).isEqualTo("acme")
        assertThat(manifest.toolVersion).isEqualTo("16")
        assertThat(manifest.files.keys).contains(Constants.LocalStack.COMPOSE_FILE, Constants.Export.README_FILE)
        assertThat(manifest.files.keys).noneMatch { it.startsWith("dashboards/") }
        manifest.files.forEach { (path, sha) -> assertThat(sha256(files.getValue(path))).describedAs(path).isEqualTo(sha) }
    }
}
