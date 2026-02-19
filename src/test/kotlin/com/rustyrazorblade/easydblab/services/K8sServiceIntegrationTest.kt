package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboard
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.vector.VectorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

/**
 * Integration tests for K8s manifest application using TestContainers with K3s.
 *
 * These tests verify that all Fabric8-built resources can be applied successfully
 * to a real K3s cluster, catching errors before production deployment.
 *
 * Note: All resources are deployed to the 'default' namespace.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class K8sServiceIntegrationTest {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"

        @Container
        @JvmStatic
        val k3s: K3sContainer = K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.6-k3s1"))
    }

    private lateinit var client: KubernetesClient
    private lateinit var templateService: TemplateService

    @BeforeAll
    fun setup() {
        val kubeconfig = k3s.kubeConfigYaml
        val config = Config.fromKubeconfig(kubeconfig)
        client =
            KubernetesClientBuilder()
                .withConfig(config)
                .build()

        // Create a real TemplateService with a mock ClusterStateManager.
        // Config files use runtime env expansion (${env:HOSTNAME}), not __KEY__ templates,
        // so the context variables don't matter for correctness.
        val mockClusterStateManager = mock<ClusterStateManager>()
        whenever(mockClusterStateManager.load()).thenReturn(
            ClusterState(name = "test", versions = mutableMapOf()),
        )
        val testUser =
            User(
                region = "us-west-2",
                email = "test@example.com",
                keyName = "",
                awsProfile = "",
                awsAccessKey = "",
                awsSecret = "",
            )
        templateService = TemplateService(mockClusterStateManager, testUser)
    }

    @Test
    @Order(1)
    fun `default namespace should exist`() {
        val namespace = client.namespaces().withName(DEFAULT_NAMESPACE).get()
        assertThat(namespace)
            .withFailMessage("Namespace '$DEFAULT_NAMESPACE' does not exist")
            .isNotNull
    }

    @Test
    @Order(2)
    fun `should apply OTel Collector resources`() {
        val resources = OtelManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val configMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("otel-collector-config")
                .get()
        assertThat(configMap).isNotNull
        assertThat(configMap.data).containsKey("otel-collector-config.yaml")

        val daemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("otel-collector")
                .get()
        assertThat(daemonSet).isNotNull
    }

    @Test
    @Order(3)
    fun `should apply ebpf_exporter resources`() {
        val resources = EbpfExporterManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val configMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("ebpf-exporter-config")
                .get()
        assertThat(configMap).isNotNull
        assertThat(configMap.data).containsKey("config.yaml")

        val daemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("ebpf-exporter")
                .get()
        assertThat(daemonSet).isNotNull
    }

    @Test
    @Order(4)
    fun `should apply VictoriaMetrics and VictoriaLogs resources`() {
        val resources = VictoriaManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        val vmService =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("victoriametrics")
                .get()
        assertThat(vmService).isNotNull

        val vmDeployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("victoriametrics")
                .get()
        assertThat(vmDeployment).isNotNull

        val vlService =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("victorialogs")
                .get()
        assertThat(vlService).isNotNull

        val vlDeployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("victorialogs")
                .get()
        assertThat(vlDeployment).isNotNull
    }

    @Test
    @Order(5)
    fun `should apply Tempo resources`() {
        val resources = TempoManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val configMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("tempo-config")
                .get()
        assertThat(configMap).isNotNull
        assertThat(configMap.data).containsKey("tempo.yaml")

        val service =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("tempo")
                .get()
        assertThat(service).isNotNull

        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("tempo")
                .get()
        assertThat(deployment).isNotNull
    }

    @Test
    @Order(6)
    fun `should apply Vector resources`() {
        val resources = VectorManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val nodeConfigMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("vector-node-config")
                .get()
        assertThat(nodeConfigMap).isNotNull
        assertThat(nodeConfigMap.data).containsKey("vector.yaml")

        val nodeDaemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("vector")
                .get()
        assertThat(nodeDaemonSet).isNotNull

        val s3ConfigMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("vector-s3-config")
                .get()
        assertThat(s3ConfigMap).isNotNull
        assertThat(s3ConfigMap.data).containsKey("vector.yaml")

        val s3Deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("vector-s3")
                .get()
        assertThat(s3Deployment).isNotNull
    }

    @Test
    @Order(7)
    fun `should apply Registry resources`() {
        val resources = RegistryManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("registry")
                .get()
        assertThat(deployment).isNotNull
    }

    @Test
    @Order(8)
    fun `should apply S3 Manager resources`() {
        val resources = S3ManagerManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("s3manager")
                .get()
        assertThat(deployment).isNotNull
    }

    @Test
    @Order(9)
    fun `should apply Beyla resources`() {
        val resources = BeylaManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val configMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("beyla-config")
                .get()
        assertThat(configMap).isNotNull
        assertThat(configMap.data).containsKey("beyla-config.yaml")

        val daemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("beyla")
                .get()
        assertThat(daemonSet).isNotNull
    }

    @Test
    @Order(10)
    fun `should apply Grafana resources built with GrafanaManifestBuilder`() {
        val builder = GrafanaManifestBuilder(templateService)

        val provisioningCm = builder.buildDashboardProvisioningConfigMap()
        applyAndVerify(listOf(provisioningCm))

        val appliedCm =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("grafana-dashboards-config")
                .get()
        assertThat(appliedCm).isNotNull
        assertThat(appliedCm.data).containsKey("dashboards.yaml")

        val dashboardCm = builder.buildDashboardConfigMap(GrafanaDashboard.SYSTEM)
        applyAndVerify(listOf(dashboardCm))

        val appliedDashboard =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(GrafanaDashboard.SYSTEM.configMapName)
                .get()
        assertThat(appliedDashboard).isNotNull
        assertThat(appliedDashboard.data).containsKey(GrafanaDashboard.SYSTEM.jsonFileName)
    }

    @Test
    @Order(11)
    fun `should apply Pyroscope resources`() {
        val resources = PyroscopeManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        val configMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("pyroscope-config")
                .get()
        assertThat(configMap).isNotNull
        assertThat(configMap.data).containsKey("config.yaml")

        val service =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("pyroscope")
                .get()
        assertThat(service).isNotNull

        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("pyroscope")
                .get()
        assertThat(deployment).isNotNull

        val ebpfConfigMap =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("pyroscope-ebpf-config")
                .get()
        assertThat(ebpfConfigMap).isNotNull
        assertThat(ebpfConfigMap.data).containsKey("config.alloy")

        val ebpfDaemonSet =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("pyroscope-ebpf")
                .get()
        assertThat(ebpfDaemonSet).isNotNull
    }

    @Test
    @Order(12)
    fun `should apply Grafana Deployment built with GrafanaManifestBuilder`() {
        val builder = GrafanaManifestBuilder(templateService)
        val resources = builder.buildAllResources()
        applyAndVerify(resources)

        val deployment =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("grafana")
                .get()
        assertThat(deployment).isNotNull
    }

    @Test
    @Order(20)
    fun `all container images should be pullable in K3s`() {
        val images = collectAllContainerImages()
        assertThat(images)
            .withFailMessage("No container images found across all builders")
            .isNotEmpty

        val failures = mutableListOf<String>()
        for (image in images) {
            val result = k3s.execInContainer("crictl", "pull", image)
            if (result.exitCode != 0) {
                failures.add("$image: ${result.stderr.trim()}")
            }
        }

        assertThat(failures)
            .withFailMessage("Failed to pull container images:\n${failures.joinToString("\n")}")
            .isEmpty()
    }

    @Test
    @Order(21)
    fun `no builder should set resource limits or requests`() {
        val allResources = collectAllResources()
        val violations = mutableListOf<String>()

        for (resource in allResources) {
            val containers = extractContainers(resource)
            for (container in containers) {
                val res = container.resources
                if (res != null && (res.limits?.isNotEmpty() == true || res.requests?.isNotEmpty() == true)) {
                    violations.add(
                        "${resource.kind}/${resource.metadata?.name} container '${container.name}' has resource limits/requests",
                    )
                }
            }
        }

        assertThat(violations)
            .withFailMessage(
                "Resource limits/requests must never be set on K8s manifests:\n${violations.joinToString("\n")}",
            ).isEmpty()
    }

    /**
     * Applies Fabric8 resources to the K3s cluster using server-side apply.
     */
    private fun applyAndVerify(resources: List<HasMetadata>) {
        for (resource in resources) {
            try {
                client.resource(resource).forceConflicts().serverSideApply()
            } catch (e: Exception) {
                throw AssertionError(
                    "Failed to apply ${resource.kind}/${resource.metadata?.name}: ${e.message}",
                    e,
                )
            }
        }
    }

    /**
     * Collects all unique container images from every manifest builder.
     */
    private fun collectAllContainerImages(): Set<String> {
        val allResources = collectAllResources()
        return allResources
            .flatMap { resource ->
                extractContainers(resource).map { it.image }
            }.toSet()
    }

    /**
     * Collects all resources from every manifest builder.
     */
    private fun collectAllResources(): List<HasMetadata> =
        OtelManifestBuilder(templateService).buildAllResources() +
            EbpfExporterManifestBuilder(templateService).buildAllResources() +
            VictoriaManifestBuilder().buildAllResources() +
            TempoManifestBuilder(templateService).buildAllResources() +
            VectorManifestBuilder(templateService).buildAllResources() +
            RegistryManifestBuilder().buildAllResources() +
            S3ManagerManifestBuilder().buildAllResources() +
            BeylaManifestBuilder(templateService).buildAllResources() +
            PyroscopeManifestBuilder(templateService).buildAllResources() +
            GrafanaManifestBuilder(templateService).buildAllResources()

    /**
     * Extracts containers from a K8s resource (Deployment, DaemonSet, etc.).
     */
    private fun extractContainers(resource: HasMetadata): List<io.fabric8.kubernetes.api.model.Container> =
        when (resource) {
            is Deployment ->
                resource.spec
                    ?.template
                    ?.spec
                    ?.containers
                    .orEmpty()
            is DaemonSet ->
                resource.spec
                    ?.template
                    ?.spec
                    ?.containers
                    .orEmpty()
            else -> emptyList()
        }
}
