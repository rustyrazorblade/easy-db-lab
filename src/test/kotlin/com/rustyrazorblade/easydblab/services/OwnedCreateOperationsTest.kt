package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The create paths that do not go through `applyResource` (stress Jobs, ConfigMaps) carry the two
 * ownership labels too, so a sweep by the cluster label finds them (design D6).
 */
class OwnedCreateOperationsTest {
    private var scope = InstallScope(K8sNamespace("lab-a"), "id-1")

    private val jobs = mock<K8sJobOperations>()
    private val storage = mock<K8sStorageOperations>()
    private val ownedJobs = OwnedJobOperations(jobs) { scope }
    private val ownedStorage = OwnedStorageOperations(storage) { scope }

    private fun job(): Job =
        JobBuilder()
            .withNewMetadata()
            .withName("stress-1")
            .addToLabels(Constants.Stress.LABEL_KEY, Constants.Stress.LABEL_VALUE)
            .endMetadata()
            .withNewSpec()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(Constants.Stress.LABEL_KEY, Constants.Stress.LABEL_VALUE)
            .endMetadata()
            .endTemplate()
            .endSpec()
            .build()

    @Test
    fun `a created Job carries both labels on its metadata, keeps its own, and leaves the pod template alone`() {
        whenever(jobs.createJob(any(), any<Job>())).thenReturn(Result.success("stress-1"))
        val original = job()

        ownedJobs.createJob("lab-a", original).getOrThrow()

        val created = argumentCaptor<Job>()
        verify(jobs).createJob(eq("lab-a"), created.capture())
        assertThat(created.firstValue.metadata.labels)
            .containsAllEntriesOf(OwnershipLabels.of("id-1"))
            .containsEntry(Constants.Stress.LABEL_KEY, Constants.Stress.LABEL_VALUE)
        assertThat(created.firstValue.spec.template).isEqualTo(job().spec.template)
        assertThat(original).isEqualTo(job())
    }

    @Test
    fun `a created ConfigMap carries both labels next to its own, with the cluster id of the moment`() {
        whenever(storage.createConfigMap(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        ownedStorage.createConfigMap("lab-a", "c", mapOf("k" to "v"), mapOf("app.kubernetes.io/name" to "grafana")).getOrThrow()
        scope = InstallScope(K8sNamespace("lab-a"), "id-2")
        ownedStorage.createConfigMap("lab-a", "c", mapOf("k" to "v")).getOrThrow()

        val labels = argumentCaptor<Map<String, String>>()
        verify(storage, org.mockito.kotlin.times(2)).createConfigMap(eq("lab-a"), eq("c"), eq(mapOf("k" to "v")), labels.capture())
        assertThat(labels.firstValue)
            .containsAllEntriesOf(OwnershipLabels.of("id-1"))
            .containsEntry("app.kubernetes.io/name", "grafana")
        assertThat(labels.secondValue).isEqualTo(OwnershipLabels.of("id-2"))
    }

    @Test
    fun `a ConfigMap's own value for an ownership label is replaced by the cluster's`() {
        whenever(storage.createConfigMap(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        ownedStorage.createConfigMap("lab-a", "c", emptyMap(), mapOf(Constants.K8s.CLUSTER_LABEL to "someone-else")).getOrThrow()

        val labels = argumentCaptor<Map<String, String>>()
        verify(storage).createConfigMap(any(), any(), any(), labels.capture())
        assertThat(labels.firstValue).containsEntry(Constants.K8s.CLUSTER_LABEL, "id-1")
    }
}
