package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [OtelManifestBuilder.listWorkloadScrapeConfigs] against a real API server: the metrics registry is
 * read in the cluster's namespace only, so a labelled ConfigMap another cluster, or an earlier
 * install, left in `default` is never injected as a scrape job.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkloadScrapeRegistryIntegrationTest {
    private companion object {
        const val CLUSTER_NAMESPACE = "scrape-registry-lab-a"
        const val OTHER_NAMESPACE = "default"
        const val CLUSTER_JOB = "scrape-registry-lab-a-job"
        const val OTHER_JOB = "scrape-registry-default-job"
    }

    private lateinit var client: KubernetesClient
    private lateinit var otel: OtelManifestBuilder

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(CLUSTER_NAMESPACE)
        client = SharedK3s.client()
        val stateManager = mock<ClusterStateManager>()
        whenever(stateManager.load()).thenReturn(ClusterState(name = "scrape-registry"))
        otel = OtelManifestBuilder(TemplateService(stateManager))
        registerJob(CLUSTER_NAMESPACE, CLUSTER_JOB)
        registerJob(OTHER_NAMESPACE, OTHER_JOB)
    }

    @AfterAll
    fun teardown() {
        client
            .configMaps()
            .inNamespace(OTHER_NAMESPACE)
            .withName(OTHER_JOB)
            .delete()
        client.close()
    }

    private fun registerJob(
        namespace: String,
        jobName: String,
    ) {
        client
            .configMaps()
            .inNamespace(namespace)
            .resource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .addToLabels(Constants.K8s.WORKLOAD_METRICS_LABEL, "true")
                    .endMetadata()
                    .addToData("job-name", jobName)
                    .addToData("port", "9100")
                    .build(),
            ).createOr { it.update() }
    }

    @Test
    fun `only the cluster namespace's registry is read`() {
        val scope = InstallScope(K8sNamespace(CLUSTER_NAMESPACE), "scrape-registry-cluster")

        val jobs = otel.listWorkloadScrapeConfigs(client, scope).map { it.jobName }

        assertThat(jobs).containsExactly(CLUSTER_JOB)
    }
}
