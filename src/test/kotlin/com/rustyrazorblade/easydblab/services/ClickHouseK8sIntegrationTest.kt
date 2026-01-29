package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kubernetes.ManifestApplier
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files

/**
 * Integration tests for ClickHouse K8s manifest application using TestContainers with K3s.
 *
 * These tests verify that all ClickHouse manifests can be applied successfully
 * to a real K3s cluster, catching errors before production deployment.
 *
 * Note: All resources are deployed to the 'default' namespace.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClickHouseK8sIntegrationTest {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"
        private const val CLICKHOUSE_MANIFEST_DIR =
            "src/main/resources/com/rustyrazorblade/easydblab/commands/k8s/clickhouse"

        @Container
        @JvmStatic
        val k3s: K3sContainer = K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.6-k3s1"))
    }

    private lateinit var client: KubernetesClient
    private val configService = DefaultClickHouseConfigService()

    /**
     * Resource types for K8s manifests
     */
    enum class ResourceType {
        SERVICE,
        CONFIGMAP,
        STATEFULSET,
    }

    /**
     * Test case definition for manifest testing
     */
    data class ManifestTestCase(
        val filename: String,
        val resourceType: ResourceType,
        val resourceName: String,
        val dataKey: String? = null,
    )

    /**
     * Ordered list of ClickHouse manifests to test (matching numeric prefixes)
     */
    private val manifestTestCases =
        listOf(
            // ConfigMaps (11, 12, 13, 14, 17)
            ManifestTestCase(
                "11-clickhouse-keeper-configmap.yaml",
                ResourceType.CONFIGMAP,
                "clickhouse-keeper-config",
                "keeper_config.xml",
            ),
            ManifestTestCase(
                "12-clickhouse-server-configmap.yaml",
                ResourceType.CONFIGMAP,
                "clickhouse-server-config",
                "config.xml",
            ),
            ManifestTestCase(
                "13-clickhouse-cluster-config.yaml",
                ResourceType.CONFIGMAP,
                "clickhouse-cluster-config",
                "replicas-per-shard",
            ),
            ManifestTestCase(
                "14-grafana-dashboard-clickhouse.yaml",
                ResourceType.CONFIGMAP,
                "grafana-dashboard-clickhouse",
            ),
            ManifestTestCase(
                "17-grafana-dashboard-clickhouse-logs.yaml",
                ResourceType.CONFIGMAP,
                "grafana-dashboard-clickhouse-logs",
            ),
            // Services (20, 21)
            ManifestTestCase(
                "20-clickhouse-keeper-service.yaml",
                ResourceType.SERVICE,
                "clickhouse-keeper",
            ),
            ManifestTestCase(
                "21-clickhouse-server-service.yaml",
                ResourceType.SERVICE,
                "clickhouse",
            ),
            // StatefulSets (30, 31)
            ManifestTestCase(
                "30-clickhouse-keeper-statefulset.yaml",
                ResourceType.STATEFULSET,
                "clickhouse-keeper",
            ),
            ManifestTestCase(
                "31-clickhouse-server-statefulset.yaml",
                ResourceType.STATEFULSET,
                "clickhouse",
            ),
        )

    @BeforeAll
    fun setup() {
        val kubeconfig = k3s.kubeConfigYaml
        val config = Config.fromKubeconfig(kubeconfig)
        client =
            KubernetesClientBuilder()
                .withConfig(config)
                .build()
    }

    @Test
    @Order(1)
    fun `default namespace should exist`() {
        val namespace = client.namespaces().withName(DEFAULT_NAMESPACE).get()
        assertThat(namespace)
            .withFailMessage("Namespace '$DEFAULT_NAMESPACE' does not exist")
            .isNotNull
    }

    @TestFactory
    @Order(2)
    fun `should apply all ClickHouse manifests`(): List<DynamicTest> =
        manifestTestCases.map { testCase ->
            DynamicTest.dynamicTest("apply ${testCase.filename}") {
                applyAndVerifyManifest(testCase)
            }
        }

    @Test
    @Order(3)
    fun `should have created all expected ClickHouse resources`() {
        // Verify ConfigMaps (5 from manifests + kubernetes default)
        val configMaps =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .list()
        assertThat(configMaps.items)
            .withFailMessage("Expected at least 5 ConfigMaps, found ${configMaps.items.size}")
            .hasSizeGreaterThanOrEqualTo(5)

        // Verify Services (2 ClickHouse services + kubernetes service)
        // Note: 21-clickhouse-server-service.yaml contains 2 services (clickhouse and clickhouse-client)
        val services =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .list()
        assertThat(services.items)
            .withFailMessage("Expected at least 4 Services (kubernetes, clickhouse-keeper, clickhouse, clickhouse-client)")
            .hasSizeGreaterThanOrEqualTo(4)

        // Verify StatefulSets (2)
        val statefulSets =
            client
                .apps()
                .statefulSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .list()
        assertThat(statefulSets.items)
            .withFailMessage("Expected 2 StatefulSets (clickhouse-keeper, clickhouse)")
            .hasSize(2)
    }

    @Test
    @Order(4)
    fun `clickhouse-keeper service should have correct ports`() {
        val service =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("clickhouse-keeper")
                .get()

        assertThat(service).isNotNull
        val ports = service.spec.ports.map { it.port }
        assertThat(ports)
            .withFailMessage("ClickHouse Keeper service should expose ports 2181 (client), 9234 (raft), 9363 (metrics)")
            .containsExactlyInAnyOrder(2181, 9234, 9363)
    }

    @Test
    @Order(5)
    fun `clickhouse server service should have correct ports`() {
        val service =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("clickhouse")
                .get()

        assertThat(service).isNotNull
        val ports = service.spec.ports.map { it.port }
        assertThat(ports)
            .withFailMessage("ClickHouse service should expose ports 8123 (http), 9000 (native), 9009 (interserver), 9363 (metrics)")
            .containsExactlyInAnyOrder(8123, 9000, 9009, 9363)
    }

    @Test
    @Order(6)
    fun `clickhouse-client service should be NodePort type`() {
        val service =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("clickhouse-client")
                .get()

        assertThat(service).isNotNull
        assertThat(service.spec.type)
            .withFailMessage("ClickHouse client service should be NodePort type for external access")
            .isEqualTo("NodePort")
    }

    @Test
    @Order(7)
    fun `should generate dynamic ConfigMap with correct shard topology`() {
        // Use the ClickHouseConfigService to generate dynamic config
        val tempDir = Files.createTempDirectory("clickhouse-test")
        try {
            // Copy the server ConfigMap template to temp dir
            val sourceConfigMap = File(CLICKHOUSE_MANIFEST_DIR, "12-clickhouse-server-configmap.yaml")
            val destConfigMap = tempDir.resolve("12-clickhouse-server-configmap.yaml")
            Files.copy(sourceConfigMap.toPath(), destConfigMap)

            // Generate dynamic config with 6 replicas, 3 per shard (2 shards)
            val configMapData =
                configService.createDynamicConfigMap(
                    totalReplicas = 6,
                    replicasPerShard = 3,
                    basePath = tempDir,
                )

            assertThat(configMapData).containsKey("config.xml")
            assertThat(configMapData).containsKey("users.xml")

            // Verify the generated config contains correct shard topology
            val configXml = configMapData["config.xml"]!!

            // Should have 2 shards (6 replicas / 3 per shard)
            assertThat(configXml).contains("<shard>")

            // Verify replica hostnames are generated correctly
            assertThat(configXml).contains("clickhouse-0.clickhouse.default.svc.cluster.local")
            assertThat(configXml).contains("clickhouse-5.clickhouse.default.svc.cluster.local")

            // Apply the dynamic ConfigMap to K8s using the Kubernetes client directly
            // (YAML parser has issues with XML content containing < characters)
            val dynamicConfigMap =
                io.fabric8.kubernetes.api.model
                    .ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("clickhouse-server-config-dynamic")
                    .withNamespace(DEFAULT_NAMESPACE)
                    .addToLabels("app.kubernetes.io/name", "clickhouse-server")
                    .endMetadata()
                    .addToData("config.xml", configXml)
                    .addToData("users.xml", configMapData["users.xml"]!!)
                    .build()

            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .resource(dynamicConfigMap)
                .serverSideApply()

            // Verify it was created
            val createdConfigMap =
                client
                    .configMaps()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName("clickhouse-server-config-dynamic")
                    .get()
            assertThat(createdConfigMap).isNotNull
            assertThat(createdConfigMap.data).containsKey("config.xml")
            assertThat(createdConfigMap.data).containsKey("users.xml")

            // Verify the config.xml in K8s contains the expected shard topology
            assertThat(createdConfigMap.data["config.xml"])
                .contains("clickhouse-0.clickhouse.default.svc.cluster.local")
                .contains("clickhouse-5.clickhouse.default.svc.cluster.local")
        } finally {
            // Cleanup temp directory
            Files
                .walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @Order(8)
    fun `keeper statefulset should have correct replicas and labels`() {
        val statefulSet =
            client
                .apps()
                .statefulSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("clickhouse-keeper")
                .get()

        assertThat(statefulSet).isNotNull
        assertThat(statefulSet.spec.replicas)
            .withFailMessage("ClickHouse Keeper should have 3 replicas")
            .isEqualTo(3)
        assertThat(statefulSet.spec.selector.matchLabels)
            .containsEntry("app.kubernetes.io/name", "clickhouse-keeper")
    }

    @Test
    @Order(9)
    fun `server statefulset should reference correct config volumes`() {
        val statefulSet =
            client
                .apps()
                .statefulSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("clickhouse")
                .get()

        assertThat(statefulSet).isNotNull

        val volumes = statefulSet.spec.template.spec.volumes
        val volumeNames = volumes.map { it.name }

        // Config volumes are defined in volumes section
        assertThat(volumeNames)
            .withFailMessage("ClickHouse server should mount config and users volumes")
            .containsAll(listOf("config", "users"))

        // Verify config volume references the correct ConfigMap
        val configVolume = volumes.find { it.name == "config" }
        assertThat(configVolume?.configMap?.name)
            .isEqualTo("clickhouse-server-config")

        // Data volume comes from volumeClaimTemplates for pod-to-node pinning
        val volumeClaimTemplates = statefulSet.spec.volumeClaimTemplates
        assertThat(volumeClaimTemplates)
            .withFailMessage("ClickHouse server should have volumeClaimTemplate for data")
            .isNotEmpty
        val dataVct = volumeClaimTemplates.find { it.metadata.name == "data" }
        assertThat(dataVct)
            .withFailMessage("ClickHouse server should have 'data' volumeClaimTemplate")
            .isNotNull
        assertThat(dataVct?.spec?.storageClassName)
            .isEqualTo("local-storage")
    }

    /**
     * Apply a manifest and verify the expected resource was created.
     */
    private fun applyAndVerifyManifest(testCase: ManifestTestCase) {
        val manifestFile = File(CLICKHOUSE_MANIFEST_DIR, testCase.filename)
        assertThat(manifestFile.exists())
            .withFailMessage("Manifest file not found: ${manifestFile.absolutePath}")
            .isTrue()

        try {
            ManifestApplier.applyManifest(client, manifestFile)
        } catch (e: Exception) {
            throw AssertionError("Failed to apply manifest '${testCase.filename}': ${e.message}", e)
        }

        verifyResource(testCase)
    }

    /**
     * Verify a resource was created based on its type.
     */
    private fun verifyResource(testCase: ManifestTestCase) {
        when (testCase.resourceType) {
            ResourceType.SERVICE -> {
                val service =
                    client
                        .services()
                        .inNamespace(DEFAULT_NAMESPACE)
                        .withName(testCase.resourceName)
                        .get()
                assertThat(service)
                    .withFailMessage("Service '${testCase.resourceName}' was not created")
                    .isNotNull
            }
            ResourceType.CONFIGMAP -> {
                val configMap =
                    client
                        .configMaps()
                        .inNamespace(DEFAULT_NAMESPACE)
                        .withName(testCase.resourceName)
                        .get()
                assertThat(configMap)
                    .withFailMessage("ConfigMap '${testCase.resourceName}' was not created")
                    .isNotNull
                testCase.dataKey?.let { key ->
                    assertThat(configMap.data)
                        .withFailMessage("ConfigMap '${testCase.resourceName}' missing data key '$key'")
                        .containsKey(key)
                }
            }
            ResourceType.STATEFULSET -> {
                val statefulSet =
                    client
                        .apps()
                        .statefulSets()
                        .inNamespace(DEFAULT_NAMESPACE)
                        .withName(testCase.resourceName)
                        .get()
                assertThat(statefulSet)
                    .withFailMessage("StatefulSet '${testCase.resourceName}' was not created")
                    .isNotNull
            }
        }
    }
}
