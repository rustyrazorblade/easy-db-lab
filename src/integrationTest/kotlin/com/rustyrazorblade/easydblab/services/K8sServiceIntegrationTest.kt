package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.PAUSE_IMAGE
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.StatefulSet
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
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

        @Container
        @JvmStatic
        val k3s: K3sContainer =
            K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.6-k3s1"))
                .withPrivilegedMode(true)
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig!!
                        .withCgroupnsMode("host")
                        .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
                }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
                .withEnv("K3S_SNAPSHOTTER", "native")
                .let { (it as K3sContainer).withPreloadedImages() }
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

        val clusterConfig =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("cluster-config")
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .addToData("control_node_ip", "10.0.0.1")
                .addToData("aws_region", "us-west-2")
                .addToData("s3_bucket", "test-bucket")
                .addToData("traces_s3_prefix", "observability/traces")
                .addToData("cluster_name", "test")
                .build()
        client.resource(clusterConfig).forceConflicts().serverSideApply()

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

    // -----------------------------------------------------------------------
    // Phase 1: Set up prerequisites that pods need to start
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    fun `label node and create host directories needed by pods`() {
        // Label the K3s node as a db node so ClickHouse pods can schedule
        val nodeName =
            client
                .nodes()
                .list()
                .items
                .first()
                .metadata
                .name
        client
            .nodes()
            .withName(nodeName)
            .edit { node ->
                node.metadata.labels["type"] = "db"
                node.metadata.labels[Constants.NODE_ORDINAL_LABEL] = "0"
                node
            }

        // Registry needs /opt/registry/certs with TLS cert and key (hostPath type: Directory)
        k3s.execInContainer("mkdir", "-p", "/opt/registry/certs")
        k3s.execInContainer(
            "sh",
            "-c",
            "openssl req -x509 -newkey rsa:2048 -keyout /opt/registry/certs/registry.key " +
                "-out /opt/registry/certs/registry.crt -days 1 -nodes -subj '/CN=registry'",
        )

        // Data directories for Victoria (images run as non-root, need write access)
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/victoriametrics")
        k3s.execInContainer("chmod", "777", "/mnt/db1/victoriametrics")
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/victorialogs")
        k3s.execInContainer("chmod", "777", "/mnt/db1/victorialogs")

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

        assertServiceAccountExists("otel-collector")
        assertClusterRoleExists("otel-collector")
        assertClusterRoleBindingExists("otel-collector")
        assertConfigMapExists("otel-collector-config", "otel-collector-config.yaml")
        assertDaemonSetExists("otel-collector")
        assertServiceExists("otel-collector")
    }

    @Test
    @Order(14)
    fun `should apply OTel Journald resources`() {
        val resources = JournaldOtelManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("fluent-bit-journald-config", "fluent-bit-journald.yaml")
        assertDaemonSetExists("fluent-bit-journald")
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
    fun `should apply Mimir and Loki resources`() {
        applyAndVerify(MimirManifestBuilder(templateService).buildAllResources())
        applyAndVerify(LokiManifestBuilder(templateService).buildAllResources())

        assertConfigMapExists("mimir-config", MimirManifestBuilder.CONFIG_FILE)
        assertServiceExists("mimir")
        assertDeploymentExists("mimir")
        assertConfigMapExists("loki-config", LokiManifestBuilder.CONFIG_FILE)
        assertServiceExists("loki")
        assertDeploymentExists("loki")
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
        assertServiceAccountExists("pyroscope-ebpf")
        assertClusterRoleExists("pyroscope-ebpf")
        assertClusterRoleBindingExists("pyroscope-ebpf")
        assertConfigMapExists("pyroscope-ebpf-config", "config.alloy")
        assertDaemonSetExists("pyroscope-ebpf")
    }

    @Test
    @Order(19)
    fun `should apply YACE resources`() {
        val resources = YaceManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("yace-config", "yace-config.yaml")
        assertDeploymentExists("yace")
    }

    @Test
    @Order(20)
    fun `should apply kube-state-metrics resources`() {
        val resources = KubeStateMetricsManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertServiceAccountExists("kube-state-metrics")
        assertClusterRoleExists("kube-state-metrics")
        assertClusterRoleBindingExists("kube-state-metrics")
        assertServiceExists("kube-state-metrics")
        assertDeploymentExists("kube-state-metrics")
    }

    @Test
    @Order(21)
    fun `should apply Grafana resources`() {
        val builder = GrafanaManifestBuilder(templateService)

        // Apply provisioning ConfigMap
        applyAndVerify(listOf(builder.buildDashboardProvisioningConfigMap()))
        assertConfigMapExists("grafana-dashboards-config", "dashboards.yaml")

        // Apply all resources including Deployment. Dashboards are files on the hostPath, not
        // K8s objects, so nothing per dashboard is expected here.
        applyAndVerify(builder.buildAllResources())
        assertDeploymentExists("grafana")
    }

    // -----------------------------------------------------------------------
    // Phase 2b: PV lifecycle — verify stale claimRef recycling works
    // -----------------------------------------------------------------------

    @Test
    @Order(23)
    fun `PV with stale claimRef should rebind after clearing UID`() {
        val pvName = "pv-lifecycle-test"
        val pvcName = "pvc-lifecycle-test"

        // 1. Create a local-storage PV pre-bound to a PVC name (simulating workload start)
        val pv =
            PersistentVolumeBuilder()
                .withNewMetadata()
                .withName(pvName)
                .endMetadata()
                .withNewSpec()
                .addToCapacity("storage", Quantity("1Gi"))
                .withAccessModes("ReadWriteOnce")
                .withPersistentVolumeReclaimPolicy("Retain")
                .withStorageClassName("local-storage")
                .withNewLocal()
                .withPath("/tmp/pv-lifecycle-test")
                .endLocal()
                .withNewClaimRef()
                .withName(pvcName)
                .withNamespace(DEFAULT_NAMESPACE)
                .endClaimRef()
                .withNewNodeAffinity()
                .withNewRequired()
                .addNewNodeSelectorTerm()
                .addNewMatchExpression()
                .withKey(Constants.NODE_ORDINAL_LABEL)
                .withOperator("In")
                .withValues("0")
                .endMatchExpression()
                .endNodeSelectorTerm()
                .endRequired()
                .endNodeAffinity()
                .endSpec()
                .build()
        client.persistentVolumes().resource(pv).create()
        k3s.execInContainer("mkdir", "-p", "/tmp/pv-lifecycle-test")

        // 2. Create a matching PVC — it should bind to the pre-bound PV
        val pvc =
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(pvcName)
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withStorageClassName("local-storage")
                .withNewResources()
                .addToRequests("storage", Quantity("1Gi"))
                .endResources()
                .endSpec()
                .build()
        client.persistentVolumeClaims().resource(pvc).create()

        // Wait for the PVC to bind
        waitForPvcBound(pvcName, timeoutSeconds = 30)

        // Verify PV now has a UID in its claimRef (K8s filled it in)
        val boundPv = client.persistentVolumes().withName(pvName).get()
        assertThat(boundPv.spec.claimRef.uid).isNotNull()

        // 3. Delete the PVC (simulating "clickhouse stop")
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName(pvcName)
            .delete()

        // Wait for PV to transition to Released
        waitForPvPhase(pvName, "Released", timeoutSeconds = 30)

        // 4. Clear just the UID (the fix from clearStaleClaimRefUid)
        client.persistentVolumes().withName(pvName).edit { editPv ->
            editPv.spec.claimRef.uid = null
            editPv.spec.claimRef.resourceVersion = null
            editPv
        }

        // 5. Create a new PVC with the same name (simulating "clickhouse start")
        client.persistentVolumeClaims().resource(pvc).create()

        // 6. Verify it binds — this is the core assertion
        waitForPvcBound(pvcName, timeoutSeconds = 30)

        val reboundPv = client.persistentVolumes().withName(pvName).get()
        assertThat(reboundPv.status.phase).isEqualTo("Bound")
        assertThat(reboundPv.spec.claimRef.uid).isNotNull()

        // Cleanup
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName(pvcName)
            .delete()
        client.persistentVolumes().withName(pvName).delete()
    }

    @Test
    @Order(24)
    fun `createLocalPersistentVolumes recycles stale PVs via service layer`() {
        val storageOps = createStorageOperations()
        val testHost =
            ClusterHost(
                publicIp = "unused",
                privateIp = "unused",
                alias = "test",
                availabilityZone = "us-west-2a",
            )
        val config =
            PersistentVolumeConfig(
                dbName = "recycletest",
                localPath = "/tmp/recycletest",
                count = 1,
                storageSize = "1Gi",
                namespace = DEFAULT_NAMESPACE,
                volumeClaimTemplateName = "data",
            )
        k3s.execInContainer("mkdir", "-p", "/tmp/recycletest")

        // First call: creates the PV
        storageOps.createLocalPersistentVolumes(testHost, config).getOrThrow()
        val pvName = "data-recycletest-0"
        assertThat(client.persistentVolumes().withName(pvName).get()).isNotNull

        // Create and bind a PVC, then delete it (simulating stop)
        val pvc =
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(pvName)
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withStorageClassName("local-storage")
                .withNewResources()
                .addToRequests("storage", Quantity("1Gi"))
                .endResources()
                .endSpec()
                .build()
        client.persistentVolumeClaims().resource(pvc).create()
        waitForPvcBound(pvName, timeoutSeconds = 30)
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName(pvName)
            .delete()
        waitForPvPhase(pvName, "Released", timeoutSeconds = 30)

        // Second call: should detect stale claimRef and clear the UID
        storageOps.createLocalPersistentVolumes(testHost, config).getOrThrow()

        // Verify the PV's claimRef UID was cleared (pre-bound state)
        val recycledPv = client.persistentVolumes().withName(pvName).get()
        assertThat(recycledPv.spec.claimRef.uid).isNull()
        assertThat(recycledPv.spec.claimRef.name).isEqualTo(pvName)

        // Verify a new PVC can bind
        client.persistentVolumeClaims().resource(pvc).create()
        waitForPvcBound(pvName, timeoutSeconds = 30)

        // Cleanup
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName(pvName)
            .delete()
        client.persistentVolumes().withName(pvName).delete()
    }

    /**
     * db and app nodes both carry `easydblab.com/node-ordinal`, so a db PV pinned only by ordinal
     * was satisfiable on app0: a 3-broker Kafka put a broker there, which then failed to mount
     * `/mnt/db1/kafka`. A platform PV requires its node pool (`type`) as well as its ordinal.
     */
    @Test
    @Order(25)
    fun `a db platform PV binds only on a db node with its ordinal, not an app node with the same ordinal`() {
        val storageOps = createStorageOperations()
        storageOps.ensureLocalStorageWfcClass(testHost).getOrThrow()
        val nodeName =
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.name
        val kit = "affinitytest"
        val pvName = "data-$kit-0"
        k3s.execInContainer("mkdir", "-p", "/tmp/$kit")
        val labelKeys = listOf(Constants.NODE_ORDINAL_LABEL, Constants.NODE_TYPE_LABEL)
        val originalLabels =
            client
                .nodes()
                .withName(nodeName)
                .get()
                .metadata.labels
                .filterKeys { it in labelKeys }
        labelNode(nodeName, mapOf(Constants.NODE_ORDINAL_LABEL to "0", Constants.NODE_TYPE_LABEL to "app"))
        try {
            storageOps
                .createLocalPersistentVolumes(
                    testHost,
                    PersistentVolumeConfig(
                        dbName = kit,
                        localPath = "/tmp/$kit",
                        count = 1,
                        storageSize = "1Gi",
                        storageClass = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                        namespace = DEFAULT_NAMESPACE,
                        nodeType = "db",
                    ),
                ).getOrThrow()
            createClaimAndConsumer(kit, storageClass = Constants.K8s.LOCAL_STORAGE_WFC_CLASS)

            // The only node is an app node with ordinal 0: the scheduler must not place the pod there.
            Thread.sleep(UNSCHEDULABLE_WAIT_MS)
            assertThat(pvcPhase(pvName)).isEqualTo("Pending")

            labelNode(nodeName, mapOf(Constants.NODE_TYPE_LABEL to "db"))
            waitForPvcBound(pvName, timeoutSeconds = 90)
        } finally {
            deleteClaimAndConsumer(kit)
            client.persistentVolumes().withName(pvName).delete()
            // Restore what the earlier setup test labelled the node with for the tests that follow.
            unlabelNode(nodeName, labelKeys)
            labelNode(nodeName, originalLabels)
        }
    }

    /**
     * Every kit's platform PVs share one storage class, so neo4j's PVC bound postgres-duckdb's PV.
     * A platform PV carries its kit's label, and a kit's PVC selecting that label binds only its
     * own kit's PV, even when another kit's PV is Available first.
     */
    @Test
    @Order(26)
    fun `a kit's claim selecting its kit label binds its own PV, not another kit's available PV`() {
        val storageOps = createStorageOperations()
        val kits = listOf("reservedother", "reservedown")
        kits.forEach { kit ->
            k3s.execInContainer("mkdir", "-p", "/tmp/$kit")
            storageOps
                .createLocalPersistentVolumes(
                    testHost,
                    PersistentVolumeConfig(
                        dbName = kit,
                        localPath = "/tmp/$kit",
                        count = 1,
                        storageSize = "1Gi",
                        namespace = DEFAULT_NAMESPACE,
                    ),
                ).getOrThrow()
        }
        try {
            val claim =
                PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName("claim-reservedown")
                    .withNamespace(DEFAULT_NAMESPACE)
                    .endMetadata()
                    .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withStorageClassName(Constants.K8s.LOCAL_STORAGE_CLASS)
                    .withNewSelector()
                    .addToMatchLabels(Constants.PV_KIT_LABEL, "reservedown")
                    .endSelector()
                    .withNewResources()
                    .addToRequests("storage", Quantity("1Gi"))
                    .endResources()
                    .endSpec()
                    .build()
            client.persistentVolumeClaims().resource(claim).create()
            waitForPvcBound("claim-reservedown", timeoutSeconds = 30)

            assertThat(
                client
                    .persistentVolumeClaims()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName("claim-reservedown")
                    .get()
                    .spec.volumeName,
            ).isEqualTo("data-reservedown-0")
            assertThat(
                client
                    .persistentVolumes()
                    .withName("data-reservedother-0")
                    .get()
                    .status.phase,
            ).isEqualTo("Available")
        } finally {
            client
                .persistentVolumeClaims()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName("claim-reservedown")
                .delete()
            kits.forEach { client.persistentVolumes().withName("data-$it-0").delete() }
        }
    }

    // -----------------------------------------------------------------------
    // Phase 3: Resource limits and structural checks
    // -----------------------------------------------------------------------

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

    private fun assertServiceAccountExists(name: String) {
        val sa =
            client
                .serviceAccounts()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(sa).withFailMessage("ServiceAccount '$name' not found").isNotNull
    }

    private fun assertClusterRoleExists(name: String) {
        val cr =
            client
                .rbac()
                .clusterRoles()
                .withName(name)
                .get()
        assertThat(cr).withFailMessage("ClusterRole '$name' not found").isNotNull
    }

    private fun assertClusterRoleBindingExists(name: String) {
        val crb =
            client
                .rbac()
                .clusterRoleBindings()
                .withName(name)
                .get()
        assertThat(crb).withFailMessage("ClusterRoleBinding '$name' not found").isNotNull
    }

    private fun collectAllResources(): List<HasMetadata> =
        JournaldOtelManifestBuilder(templateService).buildAllResources() +
            OtelManifestBuilder(templateService).buildAllResources() +
            EbpfExporterManifestBuilder().buildAllResources() +
            MimirManifestBuilder(templateService).buildAllResources() +
            LokiManifestBuilder(templateService).buildAllResources() +
            TempoManifestBuilder(templateService).buildAllResources() +
            RegistryManifestBuilder().buildAllResources() +
            S3ManagerManifestBuilder(templateService).buildAllResources() +
            BeylaManifestBuilder(templateService).buildAllResources() +
            PyroscopeManifestBuilder(templateService).buildAllResources() +
            YaceManifestBuilder(templateService).buildAllResources() +
            KubeStateMetricsManifestBuilder().buildAllResources() +
            GrafanaManifestBuilder(templateService).buildAllResources()

    private fun waitForPvcBound(
        pvcName: String,
        timeoutSeconds: Int,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * MILLIS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            val pvc =
                client
                    .persistentVolumeClaims()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName(pvcName)
                    .get()
            if (pvc?.status?.phase == "Bound") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val pvc =
            client
                .persistentVolumeClaims()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(pvcName)
                .get()
        throw AssertionError(
            "PVC '$pvcName' did not bind within ${timeoutSeconds}s. Phase: ${pvc?.status?.phase}\n" +
                clusterDiagnostics(client, DEFAULT_NAMESPACE),
        )
    }

    private fun waitForPvPhase(
        pvName: String,
        expectedPhase: String,
        timeoutSeconds: Int,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * MILLIS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            val pv = client.persistentVolumes().withName(pvName).get()
            if (pv?.status?.phase == expectedPhase) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val pv = client.persistentVolumes().withName(pvName).get()
        throw AssertionError(
            "PV '$pvName' did not reach phase '$expectedPhase' within ${timeoutSeconds}s. Phase: ${pv?.status?.phase}",
        )
    }

    private val testHost =
        ClusterHost(publicIp = "unused", privateIp = "unused", alias = "test", availabilityZone = "us-west-2a")

    private fun labelNode(
        nodeName: String,
        labels: Map<String, String>,
    ) {
        client.nodes().withName(nodeName).edit { node ->
            node.metadata.labels.putAll(labels)
            node
        }
    }

    private fun unlabelNode(
        nodeName: String,
        keys: List<String>,
    ) {
        client.nodes().withName(nodeName).edit { node ->
            keys.forEach { node.metadata.labels.remove(it) }
            node
        }
    }

    private fun pvcPhase(pvcName: String): String? =
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName(pvcName)
            .get()
            ?.status
            ?.phase

    /**
     * A claim `data-<kit>-0` selecting [kit]'s PVs, and a pod that consumes it: with a
     * WaitForFirstConsumer class the claim binds only when the scheduler places the pod.
     */
    private fun createClaimAndConsumer(
        kit: String,
        storageClass: String,
    ) {
        val claimName = "data-$kit-0"
        val claim =
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(claimName)
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withStorageClassName(storageClass)
                .withNewSelector()
                .addToMatchLabels(Constants.PV_KIT_LABEL, kit)
                .endSelector()
                .withNewResources()
                .addToRequests("storage", Quantity("1Gi"))
                .endResources()
                .endSpec()
                .build()
        client.persistentVolumeClaims().resource(claim).create()
        val pod =
            PodBuilder()
                .withNewMetadata()
                .withName("consumer-$kit")
                .withNamespace(DEFAULT_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("pause")
                .withImage(PAUSE_IMAGE)
                // Preloaded into K3s; never pulling makes a missing preload fail fast, not flake.
                .withImagePullPolicy("Never")
                .addNewVolumeMount()
                .withName("data")
                .withMountPath("/data")
                .endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("data")
                .withNewPersistentVolumeClaim()
                .withClaimName(claimName)
                .endPersistentVolumeClaim()
                .endVolume()
                .endSpec()
                .build()
        client.pods().resource(pod).create()
    }

    private fun deleteClaimAndConsumer(kit: String) {
        client
            .pods()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName("consumer-$kit")
            .withGracePeriod(0)
            .delete()
        client
            .persistentVolumeClaims()
            .inNamespace(DEFAULT_NAMESPACE)
            .withName("data-$kit-0")
            .delete()
    }

    private fun createStorageOperations(): DefaultK8sStorageOperations {
        val mockClientProvider = mock<K8sClientProvider>()
        whenever(mockClientProvider.createClient(any())).thenAnswer {
            KubernetesClientBuilder()
                .withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml))
                .build()
        }
        return DefaultK8sStorageOperations(
            mockClientProvider,
            EventBus(),
        )
    }

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
            is StatefulSet ->
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

/** How long a pod the scheduler must not place is given before its claim is checked still Pending. */
private const val UNSCHEDULABLE_WAIT_MS = 15_000L
