package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.PAUSE_IMAGE
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJar
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.compactor.Scratch
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
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
import com.rustyrazorblade.easydblab.kits.BuiltinKitFixture
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File

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
            K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
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
            ClusterState(name = "test"),
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
                .addToData("traces_s3_prefix", "tempo")
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
        labelNode(nodeName, mapOf("type" to "db", Constants.NODE_ORDINAL_LABEL to "0"))

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
        applyAndVerify(builder.buildAllResources(DocumentsBucket("acct", "us-west-2")))
        assertDeploymentExists("grafana")
    }

    @Test
    @Order(22)
    fun `should apply the account compactor resources`() {
        val jar = "0".repeat(64)
        // The pod mounts its scratch host paths with type Directory and the agent jar with type File.
        Scratch.entries.forEach { k3s.execInContainer("mkdir", "-p", it.hostPath) }
        k3s.execInContainer("sh", "-c", "mkdir -p ${CompactorAgentJar.AGENT_DIR} && touch ${CompactorAgentJar(jar).hostPath}")

        applyAndVerify(CompactorManifestBuilder(templateService).buildAllResources(CompactorAgentJar(jar), "us-west-2"))

        assertConfigMapExists(CompactorManifestBuilder.CONFIGMAP_NAME, CompactorManifestBuilder.GATE_SCRIPT)
        assertDeploymentExists(CompactorManifestBuilder.NAME)
    }

    /**
     * Every image the account compactor's pod runs can be pulled by K3s's containerd, as the control
     * node pulls it: the JRE the lease agent runs on, the static busybox the gate runs with, and the
     * Grafana images whose binaries the gate starts. A wrong name or a removed tag fails here, not
     * as an ImagePullBackOff during `up`.
     */
    @Test
    @Order(22)
    fun `every account compactor image can be pulled`() {
        val pod =
            CompactorManifestBuilder(templateService)
                .buildDeployment(CompactorAgentJar("0".repeat(64)), "us-west-2")
                .spec.template.spec
        val images = (pod.initContainers + pod.containers).map { it.image }.distinct()

        assertThat(images).contains(Constants.Compactor.AGENT_IMAGE, Constants.Compactor.GATE_IMAGE)
        assertThat(images).allSatisfy { image ->
            val result = k3s.execInContainer("crictl", "pull", image)
            assertThat(result.exitCode).describedAs("crictl pull $image: ${result.stderr}").isZero()
        }
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
        storageOps.createLocalPersistentVolumes(config).getOrThrow()
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
        storageOps.createLocalPersistentVolumes(config).getOrThrow()

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
        storageOps.ensureLocalStorageWfcClass().getOrThrow()
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

    /**
     * `up` labels each node with a merge patch, not a read-then-write `edit()`, so a kubelet
     * status update between the read and the write cannot fail it with 409 Conflict.
     */
    @Test
    @Order(27)
    fun `labelling a node adds its labels and keeps the ones it had`() {
        val nodeName =
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.name
        val before =
            client
                .nodes()
                .withName(nodeName)
                .get()
                .metadata.labels
        val clientProvider = WorkspaceK8s.provider(k3s.kubeConfigYaml)
        val key = "easydblab.test/label-merge"
        try {
            DefaultK8sManifestOperations(clientProvider, EventBus()).labelNode(nodeName, mapOf(key to "yes")).getOrThrow()

            val after =
                client
                    .nodes()
                    .withName(nodeName)
                    .get()
                    .metadata.labels
            assertThat(after).containsAllEntriesOf(before).containsEntry(key, "yes")
        } finally {
            unlabelNode(nodeName, listOf(key))
        }
    }

    /**
     * A kit `label` step labels every node through one client. A node that is not in the cluster
     * fails on its own, named, and the nodes after it are still labelled.
     */
    @Test
    @Order(28)
    fun `labelling several nodes labels each one and reports a missing node by name`() {
        val nodeName =
            client
                .nodes()
                .list()
                .items
                .single()
                .metadata.name
        val key = "easydblab.test/label-many"
        try {
            val outcomes =
                DefaultK8sManifestOperations(WorkspaceK8s.provider(k3s.kubeConfigYaml), EventBus())
                    .labelNodes(listOf("no-such-node", nodeName), mapOf(key to "yes"))

            assertThat(outcomes.keys).containsExactly("no-such-node", nodeName)
            assertThat(outcomes.getValue("no-such-node").exceptionOrNull()).hasMessageContaining("Node no-such-node not found")
            assertThat(outcomes.getValue(nodeName).isSuccess).isTrue()
            assertThat(
                client
                    .nodes()
                    .withName(nodeName)
                    .get()
                    .metadata.labels,
            ).containsEntry(key, "yes")
        } finally {
            unlabelNode(nodeName, listOf(key))
        }
    }

    // -----------------------------------------------------------------------
    // Phase 3: Resource limits and structural checks
    // -----------------------------------------------------------------------

    /**
     * A database must use all of its node's hardware, so no database workload may set a CPU or memory
     * request or limit. Observability and other control-node pods may, since they share the control
     * node; Tempo does.
     *
     * A database workload is every object rendered from a built-in kit whose `kit.yaml` declares
     * `type: db`, so a new database kit is checked without a change here. The operator custom
     * resources those kits apply (CNPG `Cluster`, `ClickHouseInstallation`, `TidbCluster`,
     * `KafkaNodePool`) put a pod's resources at different paths, so each object's whole tree is
     * walked by [computeResourcePaths]. `storage` is allowed: it sizes a claim, not a pod's share of
     * its node.
     */
    @Test
    @Order(41)
    fun `no database workload should set CPU or memory requests or limits`(
        @TempDir profileHome: File,
    ) {
        val context = Context(profileHome)
        val resolver = InstallTemplateResolver(context, KitSourcesProvider(context))
        val dbKits =
            resolver
                .listAvailableTemplates()
                .mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
                .filter { it.type == KitType.DB }
        assertThat(dbKits.map { it.name })
            .contains("clickhouse", "ferrosa", "ignite3", "kafka", "memcached", "neo4j", "postgres", "tidb")

        val violations =
            dbKits.flatMap { kit ->
                val fixture = BuiltinKitFixture(kit.name, templateService)
                resolver
                    .listTemplateFiles(resolver.resolve(kit.name))
                    .map { it.name }
                    .filter { it.endsWith(".yaml.template") && "/" !in it }
                    .flatMap { fixture.render(it, DB_KIT_INSTALL_TIME_VARS) }
                    .flatMap { obj -> computeResourcePaths(obj).map { "${kit.name}: ${obj.kind}/${obj.metadata?.name} $it" } }
            }

        assertThat(violations)
            .withFailMessage(
                "Database workloads must never set CPU or memory requests or limits:\n${violations.joinToString("\n")}",
            ).isEmpty()
    }

    @Test
    @Order(42)
    fun `the database resource check finds a container memory limit`() {
        val tempo = TempoManifestBuilder(templateService).buildAllResources().filterIsInstance<Deployment>()

        assertThat(tempo.flatMap { computeResourcePaths(it) })
            .contains("spec.template.spec.containers[0].resources.limits.memory")
    }

    @Test
    @Order(43)
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
            CompactorManifestBuilder(templateService).buildAllResources(CompactorAgentJar("0".repeat(64)), "us-west-2") +
            GrafanaManifestBuilder(templateService).buildAllResources(DocumentsBucket("acct", "us-west-2"))

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
    ) = patchNodeLabels(nodeName, labels)

    private fun unlabelNode(
        nodeName: String,
        keys: List<String>,
    ) = patchNodeLabels(nodeName, keys.associateWith { null })

    /**
     * Sets each label, or removes it when its value is null, with a JSON merge patch. The patch
     * carries no resourceVersion: `edit()` reads the node and patches that version, and a kubelet
     * status update in between made the API server refuse it with 409 Conflict.
     */
    private fun patchNodeLabels(
        nodeName: String,
        labels: Map<String, String?>,
    ) {
        val patch =
            buildJsonObject {
                putJsonObject("metadata") {
                    putJsonObject("labels") { labels.forEach { (key, value) -> put(key, value) } }
                }
            }
        client.nodes().withName(nodeName).patch(PatchContext.of(PatchType.JSON_MERGE), patch.toString())
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
        val mockClientProvider = WorkspaceK8s.provider(k3s.kubeConfigYaml)
        return DefaultK8sStorageOperations(
            mockClientProvider,
            EventBus(),
        )
    }

    /**
     * Every compute quota [resource] sets, as a dotted path such as
     * `spec.template.spec.containers[0].resources.limits.memory`. A quota is any entry other than
     * `storage` in a `requests` or `limits` map anywhere in the object's tree. The tree is walked
     * rather than a known path read because operator custom resources place them differently: the
     * TiDB operator puts `requests` and `limits` directly on each component's spec.
     */
    private fun computeResourcePaths(resource: HasMetadata): List<String> =
        quotaPaths(KubernetesSerialization().convertValue(resource, Map::class.java), "")

    private fun quotaPaths(
        node: Any?,
        path: String,
    ): List<String> =
        when (node) {
            is Map<*, *> ->
                node.entries.flatMap { (key, value) ->
                    val child = if (path.isEmpty()) "$key" else "$path.$key"
                    when {
                        key in QUOTA_KEYS && value is Map<*, *> ->
                            value.keys.filterNot { it == "storage" }.map { "$child.$it" }
                        else -> quotaPaths(value, child)
                    }
                }
            is List<*> -> node.flatMapIndexed { index, value -> quotaPaths(value, "$path[$index]") }
            else -> emptyList()
        }
}

private const val MILLIS_PER_SECOND = 1000L
private const val POLL_INTERVAL_MS = 2000L

/** How long a pod the scheduler must not place is given before its claim is checked still Pending. */
private const val UNSCHEDULABLE_WAIT_MS = 15_000L

/** The keys under which K8s and the database operators hold a workload's compute quota. */
private val QUOTA_KEYS = setOf("requests", "limits")

/**
 * The variables `kit install` computes rather than reads from arg defaults (the postgres extension's
 * image, uid and ports, and tidb's replica count, which defaults to the db node count); their values
 * do not affect resources.
 */
private val DB_KIT_INSTALL_TIME_VARS =
    mapOf(
        "IMAGE" to "ghcr.io/cloudnative-pg/postgresql:17",
        "PG_MAJOR_VERSION" to "17",
        "POSTGRES_UID" to "26",
        "POSTGRES_GID" to "26",
        "SHARED_PRELOAD_LIBRARIES" to "[]",
        "POST_INIT_SQL" to "[]",
        "POSTGRES_PORT" to "30432",
        "METRICS_PORT" to "30987",
        "REPLICAS" to "3",
    )
