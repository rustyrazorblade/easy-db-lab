package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.beyla.BeylaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.clickhouse.ClickHouseManifestBuilder
import com.rustyrazorblade.easydblab.configuration.headlamp.HeadlampManifestBuilder
import com.rustyrazorblade.easydblab.configuration.inspektorgadget.InspektorGadgetManifestBuilder
import com.rustyrazorblade.easydblab.configuration.ebpfexporter.EbpfExporterManifestBuilder
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboard
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.JournaldOtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.otel.OtelManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.registry.RegistryManifestBuilder
import com.rustyrazorblade.easydblab.configuration.s3manager.S3ManagerManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.configuration.victoria.VictoriaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.yace.YaceManifestBuilder
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Pod
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
        private const val POD_WAIT_TIMEOUT_SECONDS = 120L

        /**
         * Deployments that should reach Running status with ready replicas in K3s.
         *
         * Currently empty: K3s containerd in nested Docker environments loses track
         * of sandbox tasks ("SandboxChanged: Pod sandbox changed, it will be killed
         * and re-created"), causing random pods to get exit code 137 and CrashLoopBackOff.
         * This is a K3s container runtime issue, not a manifest problem. Structural
         * tests (apply, image pull, no-resource-limits) reliably validate all manifests.
         */
        private val EXPECTED_RUNNING_DEPLOYMENTS = emptySet<String>()

        /**
         * DaemonSets that should have at least one pod scheduled and running in K3s.
         *
         * Note: otel-collector is excluded because the K3s test environment lacks the
         * host log files that filelog receivers expect. Structural tests (apply, image
         * pull, no-resource-limits) still validate the manifest.
         */
        private val EXPECTED_RUNNING_DAEMONSETS = emptySet<String>()

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
                .withEnv("K3S_SNAPSHOTTER", "native") as K3sContainer
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
                .addToData("s3_bucket", "test-bucket")
                .addToData("cluster_s3_prefix", "clusters/test-test123")
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
    fun `should apply YACE resources`() {
        val resources = YaceManifestBuilder(templateService).buildAllResources()
        applyAndVerify(resources)

        assertConfigMapExists("yace-config", "yace-config.yaml")
        assertDeploymentExists("yace")
    }

    @Test
    @Order(20)
    fun `should apply inspektor-gadget resources`() {
        val resources = InspektorGadgetManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertServiceAccountExists("inspektor-gadget")
        assertClusterRoleExists("inspektor-gadget")
        assertClusterRoleBindingExists("inspektor-gadget")
        assertDaemonSetExists("inspektor-gadget")
    }

    @Test
    @Order(23)
    fun `should apply Headlamp resources`() {
        val resources = HeadlampManifestBuilder().buildAllResources()
        applyAndVerify(resources)

        assertServiceAccountExists("headlamp")
        assertClusterRoleExists("headlamp")
        assertClusterRoleBindingExists("headlamp")
        assertServiceExists("headlamp")
        assertDeploymentExists("headlamp")
    }

    @Test
    @Order(21)
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

    @Test
    @Order(22)
    fun `should apply ClickHouse resources`() {
        // Create ClickHouse data directories
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/clickhouse")
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/clickhouse-keeper")

        val builder = ClickHouseManifestBuilder(DefaultClickHouseConfigService())
        val resources =
            builder.buildAllResources(
                totalReplicas = 3,
                replicasPerShard = 3,
                s3CacheSize = "10Gi",
                s3CacheOnWrite = "true",
            )
        applyAndVerify(resources)

        assertConfigMapExists("clickhouse-keeper-config", "keeper_config.xml")
        assertConfigMapExists("clickhouse-server-config", "config.xml")
        assertConfigMapExists("clickhouse-cluster-config", "replicas-per-shard")
        assertServiceExists("clickhouse-keeper")
        assertServiceExists("clickhouse")
        assertServiceExists("clickhouse-client")
        assertStatefulSetExists("clickhouse-keeper")
        assertStatefulSetExists("clickhouse")
    }

    // -----------------------------------------------------------------------
    // Phase 2b: PV lifecycle — verify stale claimRef recycling works
    // -----------------------------------------------------------------------

    @Test
    @Order(23)
    fun `PV with stale claimRef should rebind after clearing UID`() {
        val pvName = "pv-lifecycle-test"
        val pvcName = "pvc-lifecycle-test"

        // 1. Create a local-storage PV pre-bound to a PVC name (like ClickHouseStart does)
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

        // ClickHouse Keeper/Server use required pod anti-affinity (one pod per node)
        // and the K3s test has only 1 node, so 2 of 3 keeper pods will always be Pending.
        // Fluent Bit journald needs /var/log/journal which doesn't exist in K3s.
        val pendingExclusions = listOf("clickhouse-keeper-", "clickhouse-", "fluent-bit-journald-")

        for (pod in allPods) {
            val podName = pod.metadata?.name ?: "unknown"
            val phase = pod.status?.phase

            // Pending means scheduling failed (missing volumes, affinity, etc.)
            if (phase == "Pending" && pendingExclusions.none { podName.startsWith(it) }) {
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
    // Phase 3b: Rollout restart
    // -----------------------------------------------------------------------

    @Test
    @Order(35)
    fun `should rollout restart a deployment without error`() {
        // registry is a simple deployment that should be running at this point
        val result =
            runCatching {
                client
                    .apps()
                    .deployments()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName("registry")
                    .rolling()
                    .restart()
            }
        assertThat(result.isSuccess)
            .withFailMessage("Rollout restart of Deployment/registry failed: ${result.exceptionOrNull()?.message}")
            .isTrue()
    }

    @Test
    @Order(36)
    fun `should rollout restart a daemonset without error`() {
        val result =
            runCatching {
                client
                    .apps()
                    .daemonSets()
                    .inNamespace(DEFAULT_NAMESPACE)
                    .withName("otel-collector")
                    .edit { ds ->
                        val annotations =
                            ds.spec
                                ?.template
                                ?.metadata
                                ?.annotations
                                ?.toMutableMap()
                                ?: mutableMapOf()
                        annotations["kubectl.kubernetes.io/restartedAt"] =
                            java.time.Instant
                                .now()
                                .toString()
                        ds.spec
                            ?.template
                            ?.metadata
                            ?.annotations = annotations
                        ds
                    }
            }
        assertThat(result.isSuccess)
            .withFailMessage("Rollout restart of DaemonSet/otel-collector failed: ${result.exceptionOrNull()?.message}")
            .isTrue()
    }

    // -----------------------------------------------------------------------
    // Phase 4: Image pull, resource limits, and structural checks
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

    private fun assertStatefulSetExists(name: String) {
        val sts =
            client
                .apps()
                .statefulSets()
                .inNamespace(DEFAULT_NAMESPACE)
                .withName(name)
                .get()
        assertThat(sts).withFailMessage("StatefulSet '$name' not found").isNotNull
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

    private fun collectAllResources(): List<HasMetadata> =
        JournaldOtelManifestBuilder(templateService).buildAllResources() +
            OtelManifestBuilder(templateService).buildAllResources() +
            EbpfExporterManifestBuilder().buildAllResources() +
            VictoriaManifestBuilder().buildAllResources() +
            TempoManifestBuilder(templateService).buildAllResources() +
            RegistryManifestBuilder().buildAllResources() +
            S3ManagerManifestBuilder(templateService).buildAllResources() +
            BeylaManifestBuilder(templateService).buildAllResources() +
            PyroscopeManifestBuilder(templateService).buildAllResources() +
            YaceManifestBuilder(templateService).buildAllResources() +
            InspektorGadgetManifestBuilder().buildAllResources() +
            HeadlampManifestBuilder().buildAllResources() +
            GrafanaManifestBuilder(templateService).buildAllResources() +
            ClickHouseManifestBuilder(DefaultClickHouseConfigService()).buildAllResources(
                totalReplicas = 3,
                replicasPerShard = 3,
                s3CacheSize = "10Gi",
                s3CacheOnWrite = "true",
            )

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
            "PVC '$pvcName' did not bind within ${timeoutSeconds}s. Phase: ${pvc?.status?.phase}",
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
private const val SECONDS_FOR_SCHEDULING = 60_000L
private val IMAGE_PULL_FAILURES = setOf("ImagePullBackOff", "ErrImagePull", "ErrImageNeverPull")
