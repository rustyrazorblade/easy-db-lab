package com.rustyrazorblade.easydblab.configuration.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.DefaultClickHouseConfigService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for ClickHouseManifestBuilder.
 *
 * Verifies resource structure, labels, and content without requiring K3s.
 * Integration testing (apply to real cluster) is done in K8sServiceIntegrationTest.
 */
class ClickHouseManifestBuilderTest {
    private val builder = ClickHouseManifestBuilder(DefaultClickHouseConfigService())

    private fun buildResources() =
        builder.buildAllResources(
            totalReplicas = 6,
            replicasPerShard = 3,
            s3CacheSize = "10Gi",
            s3CacheOnWrite = "true",
        )

    @Test
    fun `buildAllResources returns 8 resources`() {
        val resources = buildResources()
        assertThat(resources).hasSize(8)
    }

    @Test
    fun `keeper ConfigMap contains keeper_config xml`() {
        val resources = buildResources()
        val keeperConfig =
            resources
                .filterIsInstance<ConfigMap>()
                .first { it.metadata.name == "clickhouse-keeper-config" }

        assertThat(keeperConfig.data).containsKey("keeper_config.xml")
        assertThat(keeperConfig.data["keeper_config.xml"]).contains("<keeper_server>")
    }

    @Test
    fun `server ConfigMap contains config xml with dynamic shards`() {
        val resources = buildResources()
        val serverConfig =
            resources
                .filterIsInstance<ConfigMap>()
                .first { it.metadata.name == "clickhouse-server-config" }

        assertThat(serverConfig.data).containsKeys("config.xml", "users.xml")
        // 6 replicas / 3 per shard = 2 shards
        val configXml = serverConfig.data["config.xml"]!!
        assertThat(configXml).contains("<shard>")
        assertThat(configXml).contains("clickhouse-0")
        assertThat(configXml).contains("clickhouse-5")
    }

    @Test
    fun `cluster config ConfigMap contains runtime values`() {
        val resources = buildResources()
        val clusterConfig =
            resources
                .filterIsInstance<ConfigMap>()
                .first { it.metadata.name == "clickhouse-cluster-config" }

        assertThat(clusterConfig.data).containsEntry("replicas-per-shard", "3")
        assertThat(clusterConfig.data).containsEntry("s3-cache-size", "10Gi")
        assertThat(clusterConfig.data).containsEntry("s3-cache-on-write", "true")
    }

    @Test
    fun `keeper service is headless`() {
        val resources = buildResources()
        val keeperSvc =
            resources
                .filterIsInstance<Service>()
                .first { it.metadata.name == "clickhouse-keeper" }

        assertThat(keeperSvc.spec.clusterIP).isEqualTo("None")
    }

    @Test
    fun `server client service uses NodePort`() {
        val resources = buildResources()
        val clientSvc =
            resources
                .filterIsInstance<Service>()
                .first { it.metadata.name == "clickhouse-client" }

        assertThat(clientSvc.spec.type).isEqualTo("NodePort")
        val portNames = clientSvc.spec.ports.map { it.name }
        assertThat(portNames).contains("http", "native")
    }

    @Test
    fun `keeper StatefulSet has 3 replicas`() {
        val resources = buildResources()
        val keeperSts =
            resources
                .filterIsInstance<StatefulSet>()
                .first { it.metadata.name == "clickhouse-keeper" }

        assertThat(keeperSts.spec.replicas).isEqualTo(3)
    }

    @Test
    fun `server StatefulSet starts at 0 replicas`() {
        val resources = buildResources()
        val serverSts =
            resources
                .filterIsInstance<StatefulSet>()
                .first { it.metadata.name == "clickhouse" }

        assertThat(serverSts.spec.replicas).isEqualTo(0)
    }

    @Test
    fun `all resources use correct namespace`() {
        val resources = buildResources()
        resources.forEach { resource ->
            assertThat(resource.metadata.namespace)
                .withFailMessage("${resource.kind}/${resource.metadata.name} has wrong namespace")
                .isEqualTo(Constants.ClickHouse.NAMESPACE)
        }
    }

    @Test
    fun `no containers have resource limits or requests`() {
        val resources = buildResources()
        val statefulSets = resources.filterIsInstance<StatefulSet>()

        for (sts in statefulSets) {
            val containers =
                sts.spec.template.spec.containers +
                    sts.spec.template.spec.initContainers
                        .orEmpty()
            for (container in containers) {
                val res = container.resources
                if (res != null) {
                    assertThat(res.limits.orEmpty())
                        .withFailMessage("StatefulSet/${sts.metadata.name} container '${container.name}' has resource limits")
                        .isEmpty()
                    assertThat(res.requests.orEmpty())
                        .withFailMessage("StatefulSet/${sts.metadata.name} container '${container.name}' has resource requests")
                        .isEmpty()
                }
            }
        }
    }
}
