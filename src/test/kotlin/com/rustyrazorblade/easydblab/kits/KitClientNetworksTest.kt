package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ClickHouse's passwordless `default` user and Postgres's trust lines accept exactly the
 * environment's client networks: on the nodes easy-db-lab builds the VPC and the K3s cluster CIDR,
 * elsewhere what `init` recorded, never a fixed `10.0.0.0/8`.
 */
class KitClientNetworksTest : BaseKoinTest() {
    private fun fixture(
        kit: String,
        clientNetworks: List<String>? = null,
    ) = BuiltinKitFixture(
        kit,
        TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()),
        clientNetworks = clientNetworks,
    )

    private fun clickHouseNetworks(clientNetworks: List<String>? = null): Any? =
        fixture("clickhouse", clientNetworks)
            .render("clickhouseinstallation.yaml.template")
            .filterIsInstance<GenericKubernetesResource>()
            .single()
            .get<Map<String, Any>>("spec", "configuration", "users")["default/networks/ip"]

    private fun postgresHba(clientNetworks: List<String>? = null): Any? =
        fixture("postgres", clientNetworks)
            .render("postgres-cluster.yaml.template", POSTGRES_INSTALL_VARS)
            .filterIsInstance<GenericKubernetesResource>()
            .single { it.kind == "Cluster" }
            .get<Map<String, Any>>("spec", "postgresql")["pg_hba"]

    @Test
    fun `ClickHouse allows the AWS VPC and the K3s cluster CIDR`() {
        assertThat(clickHouseNetworks()).isEqualTo(listOf("172.16.0.0/16", Constants.K3s.CLUSTER_CIDR))
    }

    @Test
    fun `ClickHouse allows the recorded networks`() {
        assertThat(clickHouseNetworks(PROBED)).isEqualTo(PROBED)
    }

    @Test
    fun `Postgres trusts one line per network`() {
        assertThat(postgresHba(PROBED)).isEqualTo(PROBED.map { "host all all $it trust" })
        assertThat(postgresHba())
            .isEqualTo(listOf("host all all 172.16.0.0/16 trust", "host all all ${Constants.K3s.CLUSTER_CIDR} trust"))
    }

    private companion object {
        val PROBED = listOf("10.244.0.0/24", "192.168.1.5/32", "192.168.0.0/16")

        /** Values `kit install` derives for a postgres instance rather than reads from arg defaults. */
        val POSTGRES_INSTALL_VARS =
            mapOf(
                "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
                "PG_MAJOR_VERSION" to "17",
                "SHARED_PRELOAD_LIBRARIES" to "[]",
                "POST_INIT_SQL" to "[]",
                "POSTGRES_UID" to "26",
                "POSTGRES_GID" to "26",
            )
    }
}
