package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Postgres processes signal each other, and the node's default AppArmor profile (containerd's,
 * stacked with the host's) refuses every signal a container sends, so an instance never leaves
 * recovery. The pods must run without the node's profile. This pins the manifest value; the real
 * proof is a `postgres start` on a cluster.
 */
class PostgresAppArmorTest : BaseKoinTest() {
    /** What `kit install postgres` resolves at install time for the default image. */
    private val postgresInstallArgs =
        mapOf(
            "PG_MAJOR_VERSION" to "17",
            "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
            "POSTGRES_UID" to "26",
            "POSTGRES_GID" to "26",
            "SHARED_PRELOAD_LIBRARIES" to "''",
            "POST_INIT_SQL" to "[]",
        )

    private fun kit() = BuiltinKitFixture("postgres", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))

    /** Walks [path] through nested maps of a parsed manifest; null when a step is missing. */
    private fun Map<*, *>.dig(vararg path: String): Any? = path.fold(this as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }

    @Test
    fun `the postgres instance pods run without the node's AppArmor profile and keep the operator's defaults`() {
        val rendered = kit().renderText("postgres-cluster.yaml.template", postgresInstallArgs)
        val cluster = Serialization.unmarshal(rendered.substringAfter("kind: Cluster"), Map::class.java)

        assertThat(cluster.dig("spec", "podSecurityContext", "appArmorProfile", "type")).isEqualTo("Unconfined")
        assertThat(cluster.dig("spec", "podSecurityContext", "runAsNonRoot")).isEqualTo(true)
        assertThat(cluster.dig("spec", "podSecurityContext", "runAsUser")).isEqualTo(26)
        assertThat(cluster.dig("spec", "podSecurityContext", "fsGroup")).isEqualTo(26)
    }
}
