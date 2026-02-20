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
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
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
 * to a real K3s cluster and that pods actually get scheduled and start running.
 *
 * Note: All resources are deployed to the 'default' namespace.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class K8sServiceIntegrationTest {
    companion object {
        private const val DEFAULT_NAMESPACE = "default"
        private const val POD_WAIT_TIMEOUT_SECONDS = 120L

        /**
         * Deployments that should reach Running status with ready replicas in K3s.
         * These have no external dependencies beyond what we set up in the test.
         */
        private val EXPECTED_RUNNING_DEPLOYMENTS =
            setOf(
                "victoriametrics",
                "victorialogs",
                "grafana",
                "pyroscope",
            )

        /**
         * DaemonSets that should have at least one pod scheduled and running in K3s.
         */
        private val EXPECTED_RUNNING_DAEMONSETS =
            setOf(
                "otel-collector",
                "vector",
            )

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

    // -----------------------------------------------------------------------
    // Phase 1: Set up prerequisites that pods need to start
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    fun `create cluster-config ConfigMap needed by multiple pods`() {
        val clusterConfig =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("cluster-config")
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .addToData("control_node_ip", "10.0.0.1")
                .addToData("aws_region", "us-west-2")
                .addToData("sqs_queue_url", "")
                .addToData("s3_bucket", "test-bucket")
                .addToData("cluster_name", "test")
                .build()
        client.resource(clusterConfig).forceConflicts().serverSideApply()

        val applied =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("cluster-config")
                .get()
        assertThat(applied).isNotNull
        assertThat(applied.data).containsKeys("control_node_ip", "aws_region", "cluster_name")
    }

    @Test
    @Order(2)
    fun `create grafana-datasources ConfigMap needed by Grafana`() {
        val datasources =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("grafana-datasources")
                .withNamespace(DEFAULT_NAMESPACE)
                .addToLabels("app.kubernetes.io/name", "grafana")
                .endMetadata()
                .addToData(
                    "datasources.yaml",
                    """
                    apiVersion: 1
                    datasources:
                      - name: VictoriaMetrics
                        type: prometheus
                        url: http://localhost:8428
                        access: proxy
                        isDefault: true
                    """.trimIndent(),
                ).build()
        client.resource(datasources).forceConflicts().serverSideApply()
    }

    @Test
    @Order(3)
    fun `create host directories needed by pods`() {
        // Registry needs /opt/registry/certs with TLS cert and key (hostPath type: Directory)
        k3s.execInContainer("mkdir", "-p", "/opt/registry/certs")
        k3s.execInContainer(
            "sh",
            "-c",
            "openssl req -x509 -newkey rsa:2048 -keyout /opt/registry/certs/registry.key " +
                "-out /opt/registry/certs/registry.crt -days 1 -nodes -subj '/CN=registry'",
        )

        // Pyroscope data dir needs correct ownership (UID 10001)
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/pyroscope")
        k3s.execInContainer("chown", "-R", "10001:10001", "/mnt/db1/pyroscope")

        // Vector state directories
        k3s.execInContainer("mkdir", "-p", "/var/lib/vector")
        k3s.execInContainer("mkdir", "-p", "/var/lib/vector-s3")

        // Database log directory for OTel
        k3s.execInContainer("mkdir", "-p", "/mnt/db1")
    }

    // -----------------------------------------------------------------------
    // Phase 2: Apply all resources (same as before, but prerequisites are met)
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    fun `should apply OTel Collector resources`() {
        val resources = OtelManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("otel-collector-config", "otel-collector-config.yaml")
        assertDaemonSetExists("otel-collector")
    }

    @Test
    @Order(11)
    fun `should apply ebpf_exporter resources`() {
        val resources = EbpfExporterManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertDaemonSetExists("ebpf-exporter")
    }

    @Test
    @Order(12)
    fun `should apply VictoriaMetrics and VictoriaLogs resources`() {
        val resources = VictoriaManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertServiceExists("victoriametrics")
        assertDeploymentExists("victoriametrics")
        assertServiceExists("victorialogs")
        assertDeploymentExists("victorialogs")
    }

    @Test
    @Order(13)
    fun `should apply Tempo resources`() {
        val resources = TempoManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("tempo-config", "tempo.yaml")
        assertServiceExists("tempo")
        assertDeploymentExists("tempo")
    }

    @Test
    @Order(14)
    fun `should apply Vector resources`() {
        val resources = VectorManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("vector-node-config", "vector.yaml")
        assertDaemonSetExists("vector")
        assertConfigMapExists("vector-s3-config", "vector.yaml")
        assertDeploymentExists("vector-s3")
    }

    @Test
    @Order(15)
    fun `should apply Registry resources`() {
        val resources = RegistryManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertDeploymentExists("registry")
    }

    @Test
    @Order(16)
    fun `should apply S3 Manager resources`() {
        val resources = S3ManagerManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertDeploymentExists("s3manager")
    }

    @Test
    @Order(17)
    fun `should apply Beyla resources`() {
        val resources = BeylaManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("beyla-config", "beyla-config.yaml")
        assertDaemonSetExists("beyla")
    }

    @Test
    @Order(18)
    fun `should apply Pyroscope resources`() {
        val resources = PyroscopeManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("pyroscope-config", "config.yaml")
        assertServiceExists("pyroscope")
        assertDeploymentExists("pyroscope")
        assertConfigMapExists("pyroscope-ebpf-config", "config.alloy")
        assertDaemonSetExists("pyroscope-ebpf")
    }

    @Test
    @Order(19)
    fun `should apply Grafana resources`() {
        val builder = GrafanaManifestBuilder(templateService)

        // Apply provisioning ConfigMap
        applyAndVerify(listOf(builder.buildDashboardProvisioningConfigMap()))
        assertConfigMapExists("grafana-dashboards-config", "dashboards.yaml")

        // Apply a dashboard ConfigMap
        applyAndVerify(listOf(builder.buildDashboardConfigMap(GrafanaDashboard.SYSTEM)))
        val appliedDashboard =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(GrafanaDashboard.SYSTEM.configMapName)
                .get()
        assertThat(appliedDashboard).isNotNull
        assertThat(appliedDashboard.data).containsKey(GrafanaDashboard.SYSTEM.jsonFileName)

        // Apply all resources including Deployment
        applyAndVerify(builder.buildAllResources())
        assertDeploymentExists("grafana")
    }

    // -----------------------------------------------------------------------
    // Phase 3: Verify pods actually start running
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    fun `deployments that need no external services should reach Running`() {
        for (name in EXPECTED_RUNNING_DEPLOYMENTS) {
            waitForDeploymentReady(name)
        }
    }

    @Test
    @Order(31)
    fun `daemonsets that need no external services should have running pods`() {
        for (name in EXPECTED_RUNNING_DAEMONSETS) {
            waitForDaemonSetReady(name)
        }
    }

    @Test
    @Order(32)
    fun `no pods should be stuck in Pending or ImagePullBackOff`() {
        // Give pods time to be scheduled and attempt image pull
        Thread.sleep(SECONDS_FOR_SCHEDULING)

        val allPods =
            client
                .pods()
                .inNamespace(DEFAULT_NAMESPACE)
                .list()
                .items
        val problems = mutableListOf<String>()

        for (pod in allPods) {
            val podName = pod.metadata?.name ?: "unknown"
            val phase = pod.status?.phase

            // Pending means scheduling failed (missing volumes, affinity, etc.)
            if (phase == "Pending") {
                val conditions = pod.status?.conditions?.joinToString { "${it.type}=${it.status}: ${it.message}" }
                problems.add("$podName is Pending: $conditions")
            }

            // Check container statuses for ImagePullBackOff
            val containerStatuses = pod.status?.containerStatuses.orEmpty()
            for (cs in containerStatuses) {
                val waiting = cs.state?.waiting
                if (waiting?.reason in IMAGE_PULL_FAILURES) {
                    problems.add("$podName container '${cs.name}' has ${waiting?.reason}: ${waiting?.message}")
                }
            }
        }

        assertThat(problems)
            .withFailMessage("Pods have scheduling or image pull failures:\n${problems.joinToString("\n")}")
            .isEmpty()
    }

    // -----------------------------------------------------------------------
    // Phase 4: Image pull, resource limits, and structural checks
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
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
    @Order(41)
    fun `no builder should set resource limits or requests`() {
        val allResources = collectAllResources()
        val violations = mutableListOf<String>()

        for (resource in allResources) {
            val containers = extractContainers(resource)
            for (container in containers) {
                val res = container.resources
                if (res != null && (res.limits?.isNotEmpty() == true || res.requests?.isNotEmpty() == true)) {
                    violations.add(
                        "${resource.kind}/${resource.metadata?.name} container '${container.name}' " +
                            "has resource limits/requests",
                    )
                }
            }
        }

        assertThat(violations)
            .withFailMessage(
                "Resource limits/requests must never be set on K8s manifests:\n${violations.joinToString("\n")}",
            ).isEmpty()
    }

    @Test
    @Order(42)
    fun `all deployments should use Recreate strategy for hostNetwork compatibility`() {
        val allResources = collectAllResources()
        val violations = mutableListOf<String>()

        for (resource in allResources) {
            if (resource is Deployment) {
                val strategy = resource.spec?.strategy?.type
                if (strategy != "Recreate") {
                    violations.add(
                        "Deployment/${resource.metadata?.name} has strategy '$strategy' " +
                            "(must be 'Recreate' for hostNetwork port binding)",
                    )
                }
            }
        }

        assertThat(violations)
            .withFailMessage(
                "All Deployments must use Recreate strategy to avoid port conflicts:\n" +
                    violations.joinToString("\n"),
            ).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

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

    private fun assertConfigMapExists(
        name: String,
        expectedKey: String,
    ) {
        val cm =
            client
                .configMaps()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(cm).withFailMessage("ConfigMap '$name' not found").isNotNull
        assertThat(cm.data).containsKey(expectedKey)
    }

    private fun assertServiceExists(name: String) {
        val svc =
            client
                .services()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(svc).withFailMessage("Service '$name' not found").isNotNull
    }

    private fun assertDeploymentExists(name: String) {
        val dep =
            client
                .apps()
                .deployments()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(dep).withFailMessage("Deployment '$name' not found").isNotNull
    }

    private fun assertDaemonSetExists(name: String) {
        val ds =
            client
                .apps()
                .daemonSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(ds).withFailMessage("DaemonSet '$name' not found").isNotNull
    }

    /**
     * Waits for a Deployment to have at least 1 ready replica.
     * Fails with detailed pod status if the timeout is exceeded.
     */
    private fun waitForDeploymentReady(name: String) {
        val deadline = System.currentTimeMillis() + POD_WAIT_TIMEOUT_SECONDS * MILLIS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            val dep =
                client
                    .apps()
                    .deployments()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName(name)
                    .get()
            val readyReplicas = dep?.status?.readyReplicas ?: 0
            if (readyReplicas > 0) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val podStatus = describePods("app.kubernetes.io/name=$name")
        throw AssertionError("Deployment '$name' did not become ready within ${POD_WAIT_TIMEOUT_SECONDS}s\n$podStatus")
    }

    /**
     * Waits for a DaemonSet to have at least 1 pod in ready state.
     * Fails with detailed pod status if the timeout is exceeded.
     */
    private fun waitForDaemonSetReady(name: String) {
        val deadline = System.currentTimeMillis() + POD_WAIT_TIMEOUT_SECONDS * MILLIS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            val ds =
                client
                    .apps()
                    .daemonSets()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName(name)
                    .get()
            val ready = ds?.status?.numberReady ?: 0
            if (ready > 0) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val podStatus = describePods("app.kubernetes.io/name=$name")
        throw AssertionError("DaemonSet '$name' did not become ready within ${POD_WAIT_TIMEOUT_SECONDS}s\n$podStatus")
    }

    /**
     * Gets detailed status of pods matching a label selector for diagnostic output.
     */
    private fun describePods(labelSelector: String): String {
        val pods =
            client
                .pods()
                .inNamespace(DEFAULT_NAMESPACE)
                .withLabelSelector(labelSelector)
                .list()
                .items
        if (pods.isEmpty()) return "No pods found with label: $labelSelector"
        return pods.joinToString("\n") { pod -> formatPodStatus(pod) }
    }

    private fun formatPodStatus(pod: Pod): String {
        val name = pod.metadata?.name ?: "unknown"
        val phase = pod.status?.phase ?: "Unknown"
        val conditions = pod.status?.conditions?.joinToString { "${it.type}=${it.status}" } ?: "none"
        val containers =
            pod.status?.containerStatuses?.joinToString { cs ->
                val state =
                    when {
                        cs.state?.running != null -> "Running"
                        cs.state?.waiting != null -> "Waiting(${cs.state.waiting.reason}: ${cs.state.waiting.message})"
                        cs.state?.terminated != null -> "Terminated(${cs.state.terminated.reason})"
                        else -> "Unknown"
                    }
                "${cs.name}=$state"
            } ?: "no container status"
        return "  Pod $name: phase=$phase conditions=[$conditions] containers=[$containers]"
    }

    private fun collectAllContainerImages(): Set<String> =
        collectAllResources()
            .flatMap { resource -> extractContainers(resource).map { it.image } }
            .toSet()

    private fun collectAllResources(): List<HasMetadata> =
        OtelManifestBuilder(templateService).buildAllResources() +
            EbpfExporterManifestBuilder().buildAllResources() +
            VictoriaManifestBuilder().buildAllResources() +
            TempoManifestBuilder(templateService).buildAllResources() +
            VectorManifestBuilder(templateService).buildAllResources() +
            RegistryManifestBuilder().buildAllResources() +
            S3ManagerManifestBuilder(templateService).buildAllResources() +
            BeylaManifestBuilder(templateService).buildAllResources() +
            PyroscopeManifestBuilder(templateService).buildAllResources() +
            GrafanaManifestBuilder(templateService).buildAllResources()

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

private const val MILLIS_PER_SECOND = 1000L
private const val POLL_INTERVAL_MS = 2000L
private const val SECONDS_FOR_SCHEDULING = 10_000L
private val IMAGE_PULL_FAILURES = setOf("ImagePullBackOff", "ErrImagePull", "ErrImageNeverPull")
