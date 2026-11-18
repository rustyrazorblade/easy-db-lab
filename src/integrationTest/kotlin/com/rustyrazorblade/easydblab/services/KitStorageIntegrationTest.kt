package com.rustyrazorblade.easydblab.services

import com.github.dockerjava.api.model.Ulimit
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.K3sDiagnostics.clusterDiagnostics
import com.rustyrazorblade.easydblab.K3sPreloadedImages.PAUSE_IMAGE
import com.rustyrazorblade.easydblab.K3sPreloadedImages.withPreloadedImages
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kits.BuiltinKitFixture
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Kit storage against real StorageClasses and real binding on K3s.
 *
 * K3s ships `local-path`, a dynamic class that reclaims with `Delete`, the Kubernetes default. The
 * Retain check must refuse it, and accept a copy of it that retains; a kit claim rendered on that
 * copy binds at the provisioned default size. On local volumes a kit claim kept across `stop`
 * rebinds its Retain PV on the next `start` — what Kafka's `deleteClaim: false` relies on.
 *
 * It uses its own K3s container: it creates cluster-scoped StorageClasses and PersistentVolumes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitStorageIntegrationTest {
    private val k3s: K3sContainer =
        K3sContainer(DockerImageName.parse(SharedK3s.K3S_IMAGE))
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!
                    .withCgroupnsMode("host")
                    .withUlimits(listOf(Ulimit("nofile", 65536L, 65536L)))
            }.withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
            .withEnv("K3S_SNAPSHOTTER", "native")
            .let { (it as K3sContainer).withPreloadedImages(LOCAL_PATH_PROVISIONER_IMAGE, LOCAL_PATH_HELPER_IMAGE) }

    private lateinit var client: KubernetesClient
    private lateinit var k8sService: K8sService

    @BeforeAll
    fun setup() {
        k3s.start()
        client = KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        val provider = mock<K8sClientProvider>()
        whenever(provider.createClient()).thenAnswer {
            KubernetesClientBuilder().withConfig(Config.fromKubeconfig(k3s.kubeConfigYaml)).build()
        }
        k8sService = DefaultK8sService(provider, EventBus())
        SharedK3s.ensurePodAdmission(client, NAMESPACE)
        val node =
            client
                .nodes()
                .list()
                .items
                .single()
        client.nodes().withName(node.metadata.name).edit { n ->
            n.apply {
                metadata.labels[Constants.NODE_TYPE_LABEL] = "db"
                metadata.labels[Constants.NODE_ORDINAL_LABEL] = "0"
            }
        }
        createRetainedCopyOfLocalPath()
    }

    @AfterAll
    fun tearDown() {
        client.close()
        k3s.stop()
    }

    @Test
    fun `the Retain check refuses local-path, accepts its retained copy, and reports a missing class`() {
        val check = StorageClassRetainCheck(k8sService)

        assertThatThrownBy { check.require(LOCAL_PATH, RetainedData.KitData) }
            .isInstanceOf(StorageClassRefusedException::class.java)
            .hasMessageContaining("StorageClass local-path reclaims with Delete")
        check.require(RETAINED_CLASS, RetainedData.KitData)
        assertThatThrownBy { check.require("no-such-class", RetainedData.KitData) }
            .isInstanceOf(StorageClassRefusedException::class.java)
            .hasMessageContaining("StorageClass no-such-class does not exist")
    }

    @Test
    fun `cassandra-pods on a retained provisioned class creates no PV and its claim binds at the default size`() {
        val storage = KitStorage.Provisioned(RETAINED_CLASS, Quantity(Constants.K8s.DEFAULT_PROVISIONED_KIT_STORAGE_SIZE))
        val pvsBefore =
            client
                .persistentVolumes()
                .list()
                .items.size
        KitStorageProvisioner(k8sService, mock<RemoteOperationsService>(), EventBus())
            .provision(KitVolumes("cassandra-pods", "db", "data", storage.defaultSize.toString()), storage, clusterState())
        assertThat(client.persistentVolumes().list().items).hasSize(pvsBefore)

        val template =
            fixture("cassandra-pods", RETAINED_CLASS)
                .render("statefulset.yaml.template", mapOf("CASSANDRA_NODES" to "1"))
                .filterIsInstance<StatefulSet>()
                .single()
                .spec.volumeClaimTemplates
                .single()
        val claim =
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName("data-cassandra-pods-0")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withSpec(template.spec)
                .build()
        bindWithConsumer(claim, "consumer-provisioned")

        val bound = claimNamed("data-cassandra-pods-0")
        assertThat(bound.spec.storageClassName).isEqualTo(RETAINED_CLASS)
        assertThat(bound.spec.resources.requests["storage"]).isEqualTo(Quantity("100Gi"))
        assertThat(
            client
                .persistentVolumes()
                .withName(bound.spec.volumeName)
                .get()
                .spec.persistentVolumeReclaimPolicy,
        ).isEqualTo("Retain")
    }

    @Test
    fun `on local volumes a claim kept across stop rebinds its Retain PV on the next start`() {
        val storage = KitStorage.LocalVolumes(Quantity("1Gi"))
        k3s.execInContainer("mkdir", "-p", "/mnt/db1/kafka/data-0")
        val remoteOps = mock<RemoteOperationsService>()
        KitStorageProvisioner(k8sService, remoteOps, EventBus())
            .provision(KitVolumes("kafka", "db", "data-0", "1Gi"), storage, clusterState())
        val kafkaClass = storage.storageClassFor("kafka")
        val claim =
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(STRIMZI_CLAIM)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withAccessModes(Constants.K8s.READ_WRITE_ONCE)
                .withStorageClassName(kafkaClass)
                .withNewResources()
                .addToRequests("storage", Quantity("1Gi"))
                .endResources()
                .endSpec()
                .build()

        bindWithConsumer(claim, "broker-first")
        val firstVolume = claimNamed(STRIMZI_CLAIM).spec.volumeName
        assertThat(firstVolume).isEqualTo("data-0-kafka-0")

        // stop: the node pool and its pods go; with deleteClaim: false the claim stays.
        deleteConsumer("broker-first")
        // start: a new broker pod mounts the same claim.
        startConsumer("broker-second", STRIMZI_CLAIM)
        waitForPodRunning("broker-second")

        assertThat(claimNamed(STRIMZI_CLAIM).spec.volumeName).isEqualTo(firstVolume)
        assertThat(
            client
                .persistentVolumes()
                .withName(firstVolume)
                .get()
                .status.phase,
        ).isEqualTo("Bound")
    }

    /**
     * postgres and clickhouse `stop` delete the CNPG Cluster or the CHI, and the operator deletes
     * the claims with it. On a provisioned Retain class the volume goes Released; the next start's
     * `kit-storage` step (`rebind-released`) must hand it to the new claim of the same name.
     */
    @Test
    fun `on a provisioned class a claim deleted at stop rebinds its Released volume on the next start`() {
        val storage = KitStorage.Provisioned(RETAINED_CLASS, Quantity("1Gi"))
        val provisioner = KitStorageProvisioner(k8sService, mock<RemoteOperationsService>(), EventBus())
        val volumes = KitVolumes("postgres", "db", "data", "1Gi", rebindReleased = true)
        val claim = {
            PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(OPERATOR_CLAIM)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withAccessModes(Constants.K8s.READ_WRITE_ONCE)
                .withStorageClassName(RETAINED_CLASS)
                .withNewResources()
                .addToRequests("storage", Quantity("1Gi"))
                .endResources()
                .endSpec()
                .build()
        }
        provisioner.provision(volumes, storage, clusterState())
        bindWithConsumer(claim(), "operator-first")
        val firstVolume = claimNamed(OPERATOR_CLAIM).spec.volumeName

        // stop: the operator deletes its pod and its claim; the Retain volume goes Released.
        deleteConsumer("operator-first")
        client
            .persistentVolumeClaims()
            .inNamespace(NAMESPACE)
            .withName(OPERATOR_CLAIM)
            .delete()
        client
            .persistentVolumes()
            .withName(firstVolume)
            .waitUntilCondition({ it?.status?.phase == "Released" }, TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // start: kit-storage runs before the operator recreates the claim.
        provisioner.provision(volumes, storage, clusterState())
        bindWithConsumer(claim(), "operator-second")

        assertThat(claimNamed(OPERATOR_CLAIM).spec.volumeName).isEqualTo(firstVolume)
    }

    private fun createRetainedCopyOfLocalPath() {
        val localPath =
            client
                .storage()
                .v1()
                .storageClasses()
                .withName(LOCAL_PATH)
                .get()
        assertThat(localPath.reclaimPolicy).isEqualTo("Delete")
        client
            .storage()
            .v1()
            .storageClasses()
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName(RETAINED_CLASS)
                    .endMetadata()
                    .withProvisioner(localPath.provisioner)
                    .withVolumeBindingMode(localPath.volumeBindingMode)
                    .withReclaimPolicy("Retain")
                    .build(),
            ).create()
    }

    private fun fixture(
        kit: String,
        kitStorageClass: String?,
    ) = BuiltinKitFixture(
        kit,
        TemplateService(ClusterStateManager(File(Files.createTempDirectory("kit-storage-it").toFile(), "state.json"))),
        kitStorageClass,
    )

    private fun clusterState() =
        ClusterState(
            name = "kit-storage-it",
            clusterId = "kit-storage-it",
            hosts = mapOf(ServerType.Cassandra to listOf(ClusterHost("", "10.0.0.1", "db0", "us-west-2a"))),
        )

    private fun claimNamed(name: String): PersistentVolumeClaim =
        client
            .persistentVolumeClaims()
            .inNamespace(NAMESPACE)
            .withName(name)
            .get()

    private fun bindWithConsumer(
        claim: PersistentVolumeClaim,
        pod: String,
    ) {
        client.persistentVolumeClaims().resource(claim).create()
        startConsumer(pod, claim.metadata.name)
        try {
            client
                .persistentVolumeClaims()
                .inNamespace(NAMESPACE)
                .withName(claim.metadata.name)
                .waitUntilCondition({ it?.status?.phase == "Bound" }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: KubernetesClientTimeoutException) {
            throw AssertionError(
                listOf(
                    e.message,
                    storageState(),
                    clusterDiagnostics(client, NAMESPACE),
                    clusterDiagnostics(client, "kube-system"),
                ).joinToString("\n"),
                e,
            )
        }
        waitForPodRunning(pod)
    }

    /**
     * Every PV and claim with its phase and claim reference, for a bind that timed out: whether the
     * reopened volume was still Released, pre-bound with no UID, or passed over for a new volume.
     */
    private fun storageState(): String {
        val volumes =
            client.persistentVolumes().list().items.map { pv ->
                val ref = pv.spec.claimRef
                "pv ${pv.metadata.name} class=${pv.spec.storageClassName} phase=${pv.status?.phase} " +
                    "claimRef=${ref?.namespace}/${ref?.name} uid=${ref?.uid}"
            }
        val claims =
            client.persistentVolumeClaims().inNamespace(NAMESPACE).list().items.map { pvc ->
                "pvc ${pvc.metadata.name} uid=${pvc.metadata.uid} phase=${pvc.status?.phase} " +
                    "volume=${pvc.spec.volumeName} annotations=${pvc.metadata.annotations}"
            }
        return (volumes + claims).joinToString("\n")
    }

    private fun startConsumer(
        pod: String,
        claimName: String,
    ) {
        client
            .pods()
            .resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName(pod)
                    .withNamespace(NAMESPACE)
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withName("pause")
                    .withImage(PAUSE_IMAGE)
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
                    .build(),
            ).create()
    }

    private fun waitForPodRunning(pod: String) {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(pod)
            .waitUntilCondition({ it?.status?.phase == "Running" }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun deleteConsumer(pod: String) {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(pod)
            .withGracePeriod(0)
            .delete()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(pod)
            .waitUntilCondition({ it == null }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val NAMESPACE = "default"
        const val LOCAL_PATH = "local-path"

        /** A copy of K3s's `local-path` that keeps its volumes, standing in for an operator's retained class. */
        const val RETAINED_CLASS = "local-path-retain"

        /** The claim Strimzi makes for volume 0 of the `dual-role` node pool's broker 0. */
        const val STRIMZI_CLAIM = "data-0-kafka-dual-role-0"

        /** A claim an operator deletes with its resource on `stop`, as CNPG names instance 1's. */
        const val OPERATOR_CLAIM = "postgres-1"
        const val TIMEOUT_SECONDS = 180L

        /** The images local-path provisioning runs, so a claim never waits on a registry. */
        const val LOCAL_PATH_PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.34"
        const val LOCAL_PATH_HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0"
    }
}
