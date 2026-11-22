package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@EnableKubernetesMockClient(crud = true)
class DefaultK8sStorageOperationsTest {
    private lateinit var storageOps: DefaultK8sStorageOperations
    private lateinit var mockEventBus: EventBus
    private lateinit var clientProvider: K8sClientProvider

    lateinit var server: KubernetesMockServer

    @BeforeEach
    fun setup() {
        mockEventBus = mock()
        // The workspace kubeconfig is a placeholder: the mock API server is not reached through one.
        val mockServerFactory =
            object : KubernetesClientFactory {
                override fun createClient(api: KubeApi): KubernetesClient = server.createClient()
            }
        clientProvider = WorkspaceK8s.provider("placeholder", mockServerFactory)
        storageOps = DefaultK8sStorageOperations(clientProvider, mockEventBus)
    }

    /** Every event emitted so far, in order. */
    private fun emitted(): List<Event> {
        val captor = argumentCaptor<Event>()
        verify(mockEventBus, atLeast(0)).emit(captor.capture())
        return captor.allValues
    }

    @Test
    fun `ensureKitLocalStorageClass creates a no-provisioner WaitForFirstConsumer Retain class, labelled`() {
        val result = storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")

        assertThat(result.isSuccess).isTrue()

        val sc =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .withName("edl-local-kafka")
                .get()

        assertThat(sc).isNotNull()
        assertThat(sc.volumeBindingMode).isEqualTo("WaitForFirstConsumer")
        assertThat(sc.reclaimPolicy).isEqualTo("Retain")
        assertThat(sc.provisioner).isEqualTo("kubernetes.io/no-provisioner")
        assertThat(sc.metadata.labels)
            .containsEntry(Constants.K8s.MANAGED_BY_LABEL, Constants.K8s.MANAGED_BY_VALUE)
            .containsEntry(Constants.K8s.CLUSTER_LABEL, "cid-1")
    }

    @Test
    fun `ensureKitLocalStorageClass is idempotent when the class already exists`() {
        storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")
        assertThat(emitted()).containsExactly(Event.K8s.KitStorageClassCreated("edl-local-kafka"))

        val result = storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")
        assertThat(emitted()).containsExactly(Event.K8s.KitStorageClassCreated("edl-local-kafka"))

        assertThat(result.isSuccess).isTrue()
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .filter { it.metadata.name == "edl-local-kafka" }
        assertThat(classes).hasSize(1)
    }

    @Test
    fun `ensureKitLocalStorageClass refuses a pre-existing class of that name that deletes, and leaves it`() {
        server
            .createClient()
            .storage()
            .v1()
            .storageClasses()
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("edl-local-kafka")
                    .endMetadata()
                    .withProvisioner("kubernetes.io/no-provisioner")
                    .withVolumeBindingMode("WaitForFirstConsumer")
                    .withReclaimPolicy("Delete")
                    .build(),
            ).create()

        val result = storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")

        assertThat(result.exceptionOrNull()).hasMessageContaining("reclaimPolicy Delete (expected Retain)")
        assertThat(emitted()).isEmpty()
    }

    @Test
    fun `deleteKitLocalStorageClass removes only that kit's class and tolerates a missing one`() {
        storageOps.ensureKitLocalStorageClass("edl-local-kafka", "cid-1")
        storageOps.ensureKitLocalStorageClass("edl-local-tidb", "cid-1")

        val created = emitted()

        assertThat(storageOps.deleteKitLocalStorageClass("edl-local-kafka").isSuccess).isTrue()
        assertThat(storageOps.deleteKitLocalStorageClass("edl-local-kafka").isSuccess).isTrue()

        // Only the delete that removed the class reports it; the class already gone reports nothing.
        assertThat(emitted().drop(created.size)).containsExactly(Event.K8s.KitStorageClassDeleted("edl-local-kafka"))

        val names =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
                .list()
                .items
                .map { it.metadata.name }
        assertThat(names).contains("edl-local-tidb").doesNotContain("edl-local-kafka")
    }

    @Test
    fun `defaultStorageClass names the annotated class, or null when none is default`() {
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("plain")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()
        assertThat(storageOps.defaultStorageClass().getOrThrow()).isNull()

        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("gp3")
                    .addToAnnotations("storageclass.kubernetes.io/is-default-class", "true")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()
        assertThat(storageOps.defaultStorageClass().getOrThrow()).isEqualTo("gp3")
    }

    @Test
    fun `a StorageClass's reclaim policy is read, a missing one reads as Delete, and an absent class as null`() {
        val classes =
            server
                .createClient()
                .storage()
                .v1()
                .storageClasses()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName(
                        "kept",
                    ).endMetadata()
                    .withProvisioner("p")
                    .withReclaimPolicy("Retain")
                    .build(),
            ).create()
        classes
            .resource(
                StorageClassBuilder()
                    .withNewMetadata()
                    .withName("defaulted")
                    .endMetadata()
                    .withProvisioner("p")
                    .build(),
            ).create()

        assertThat(storageOps.storageClassReclaimPolicy("kept").getOrThrow()).isEqualTo("Retain")
        assertThat(storageOps.storageClassReclaimPolicy("defaulted").getOrThrow()).isEqualTo("Delete")
        assertThat(storageOps.storageClassReclaimPolicy("absent").getOrThrow()).isNull()
    }

    @Test
    fun `reopenReleasedVolumes clears the claim UID only on Released PVs of the class in the namespace`() {
        val client = server.createClient()
        listOf(
            releasablePv("released-here", "retain-x", "default", "Released"),
            releasablePv("released-other-class", "other", "default", "Released"),
            releasablePv("released-other-namespace", "retain-x", "kube-system", "Released"),
            releasablePv("bound-here", "retain-x", "default", "Bound"),
        ).forEach { client.persistentVolumes().resource(it).create() }

        val reopened = storageOps.reopenReleasedVolumes("retain-x", "default").getOrThrow()

        assertThat(reopened).containsExactly("released-here")
        val claimRef = { name: String ->
            client
                .persistentVolumes()
                .withName(name)
                .get()
                .spec.claimRef
        }
        assertThat(claimRef("released-here").uid).isNull()
        assertThat(claimRef("released-here").name).isEqualTo("claim-released-here")
        assertThat(claimRef("released-here").namespace).isEqualTo("default")
        assertThat(listOf("released-other-class", "released-other-namespace", "bound-here").map { claimRef(it).uid })
            .containsOnly("uid-1")
    }

    private fun releasablePv(
        name: String,
        storageClass: String,
        claimNamespace: String,
        phase: String,
    ) = PersistentVolumeBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .withNewSpec()
        .addToCapacity("storage", Quantity("1Gi"))
        .withStorageClassName(storageClass)
        .withNewClaimRef()
        .withName("claim-$name")
        .withNamespace(claimNamespace)
        .withUid("uid-1")
        .endClaimRef()
        .withNewHostPath()
        .withPath("/tmp")
        .endHostPath()
        .endSpec()
        .withNewStatus()
        .withPhase(phase)
        .endStatus()
        .build()

    @Test
    fun `listKitLocalPersistentVolumes returns every kit's PVs and no other PV`() {
        storageOps.createLocalPersistentVolumes(kitVolumes("kafka", count = 2)).getOrThrow()
        storageOps.createLocalPersistentVolumes(kitVolumes("presto", count = 1)).getOrThrow()
        server
            .createClient()
            .persistentVolumes()
            .resource(
                PersistentVolumeBuilder()
                    .withNewMetadata()
                    .withName("not-a-kit")
                    .endMetadata()
                    .withNewSpec()
                    .addToCapacity("storage", Quantity("1Gi"))
                    .withNewHostPath()
                    .withPath("/tmp")
                    .endHostPath()
                    .endSpec()
                    .build(),
            ).create()

        val names = storageOps.listKitLocalPersistentVolumes().getOrThrow().map { it.metadata.name }

        assertThat(names).containsExactlyInAnyOrder("data-kafka-0", "data-kafka-1", "data-presto-0")
    }

    @Test
    fun `a created kit PV carries the cluster's ownership labels`() {
        storageOps.createLocalPersistentVolumes(kitVolumes("kafka", count = 1)).getOrThrow()

        val pv =
            server
                .createClient()
                .persistentVolumes()
                .withName("data-kafka-0")
                .get()

        assertThat(pv.metadata.labels).containsAllEntriesOf(OwnershipLabels.of("storage-ops-cluster"))
    }

    private fun kitVolumes(
        kit: String,
        count: Int,
    ) = PersistentVolumeConfig(
        dbName = kit,
        localPath = "/mnt/db1/$kit/data",
        count = count,
        storageSize = "1Gi",
        storageClass = "edl-local-$kit",
        namespace = "default",
        clusterId = "storage-ops-cluster",
        volumeClaimTemplateName = "data",
        nodeType = "db",
    )
}
