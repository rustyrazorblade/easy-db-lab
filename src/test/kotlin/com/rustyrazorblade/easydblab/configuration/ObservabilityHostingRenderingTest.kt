package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.grafana.DocumentsBucket
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaManifestBuilder
import com.rustyrazorblade.easydblab.configuration.kubestatemetrics.KubeStateMetricsManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The five observability workloads render from the resolved [ObservabilityHosting]: on AWS they keep
 * today's control-node selector, toleration and `/mnt/db1/<backend>` host paths; elsewhere they carry
 * neither, and none of them shares the node's network on any environment.
 */
class ObservabilityHostingRenderingTest : BaseKoinTest() {
    private val controlPlaneLabel = "node-role.kubernetes.io/control-plane"

    private companion object {
        /** Grafana is not a backend: it reads no store, so it gets no store credentials. */
        const val GRAFANA = "/mnt/db1/grafana"
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test", versions = mutableMapOf(), s3Bucket = "acct"))
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    /** Each backend's resources, keyed by the host path its state lives on today. */
    private fun backends(hosting: ObservabilityHosting): Map<String, List<HasMetadata>> {
        val templates: TemplateService = getKoin().get()
        return mapOf(
            "/mnt/db1/mimir" to MimirManifestBuilder(templates).buildAllResources(hosting),
            "/mnt/db1/loki" to LokiManifestBuilder(templates).buildAllResources(hosting),
            "/mnt/db1/tempo" to TempoManifestBuilder(templates).buildAllResources(hosting),
            "/mnt/db1/pyroscope" to PyroscopeManifestBuilder(templates).buildServerResources(hosting),
            GRAFANA to GrafanaManifestBuilder(templates).buildAllResources(DocumentsBucket("acct", "us-west-2"), hosting),
        )
    }

    private fun pod(resources: List<HasMetadata>): PodSpec =
        resources
            .filterIsInstance<Deployment>()
            .single()
            .spec.template.spec

    @Test
    fun `on AWS each workload keeps the control-node selector, toleration and its host path`() {
        backends(ObservabilityHosting.AWS).forEach { (hostPath, resources) ->
            val pod = pod(resources)

            assertThat(pod.nodeSelector).describedAs(hostPath).containsExactlyEntriesOf(mapOf(controlPlaneLabel to "true"))
            assertThat(pod.tolerations).describedAs(hostPath).singleElement().satisfies({
                assertThat(it.key).isEqualTo(controlPlaneLabel)
                assertThat(it.effect).isEqualTo("NoSchedule")
            })
            assertThat(pod.volumes.mapNotNull { it.hostPath?.path }).describedAs(hostPath).containsExactly(hostPath)
            assertThat(pod.hostNetwork ?: false).describedAs(hostPath).isFalse()
            assertThat(resources.filterIsInstance<PersistentVolumeClaim>()).describedAs(hostPath).isEmpty()
        }
    }

    @Test
    fun `with an empty placement no workload carries a selector, a toleration or the host network`() {
        val hosting = ObservabilityHosting.AWS.copy(placement = Placement.ANY_NODE)

        backends(hosting).forEach { (hostPath, resources) ->
            val pod = pod(resources)

            assertThat(pod.nodeSelector.orEmpty()).describedAs(hostPath).isEmpty()
            assertThat(pod.tolerations.orEmpty()).describedAs(hostPath).isEmpty()
            assertThat(pod.hostNetwork ?: false).describedAs(hostPath).isFalse()
            assertThat(pod.containers.flatMap { it.ports.orEmpty() }.mapNotNull { it.hostPort }).describedAs(hostPath).isEmpty()
        }
    }

    @Test
    fun `on AWS the ports callers outside the pod network use stay published on the node`() {
        val published =
            backends(ObservabilityHosting.AWS).values.flatMap { resources ->
                pod(resources).containers.flatMap { it.ports.orEmpty() }.mapNotNull { it.hostPort }
            }

        // Mimir and Loki push and read, Tempo's query and OTLP receivers, Pyroscope ingest and Grafana:
        // a redirected cluster, a node-side agent and the CLI over the SOCKS tunnel reach these on the
        // control node's private IP.
        assertThat(published).contains(9009, 3100, 3200, 4320, 4040, 3000)
    }

    @Test
    fun `kube-state-metrics follows the same placement`() {
        val aws =
            KubeStateMetricsManifestBuilder()
                .buildDeployment(Placement.CONTROL_NODE)
                .spec.template.spec
        val anyNode =
            KubeStateMetricsManifestBuilder()
                .buildDeployment(Placement.ANY_NODE)
                .spec.template.spec

        assertThat(aws.nodeSelector).containsEntry(controlPlaneLabel, "true")
        assertThat(aws.tolerations).anySatisfy { assertThat(it.key).isEqualTo(controlPlaneLabel) }
        assertThat(anyNode.nodeSelector.orEmpty()).isEmpty()
        assertThat(anyNode.tolerations.orEmpty()).isEmpty()
    }

    @Test
    fun `without host paths each backend keeps its state on its own retained claim and never on emptyDir`() {
        val claimed = ObservabilityHosting.AWS.copy(storage = BackendStorage.RetainedClaim("retained-class", Quantity("50Gi")))

        backends(claimed).forEach { (hostPath, resources) ->
            val pod = pod(resources)
            val claim = resources.filterIsInstance<PersistentVolumeClaim>().single()

            assertThat(claim.spec.storageClassName).describedAs(hostPath).isEqualTo("retained-class")
            assertThat(claim.spec.resources.requests).describedAs(hostPath).containsEntry("storage", Quantity("50Gi"))
            assertThat(pod.volumes.mapNotNull { it.persistentVolumeClaim?.claimName })
                .describedAs(hostPath)
                .containsExactly(claim.metadata.name)
            assertThat(pod.volumes.mapNotNull { it.hostPath }).describedAs(hostPath).isEmpty()
            assertThat(pod.volumes.mapNotNull { it.emptyDir }).describedAs(hostPath).isEmpty()
            // The claim is applied before the Deployment that mounts it.
            assertThat(resources.indexOf(claim)).describedAs(hostPath).isLessThan(resources.indexOfFirst { it is Deployment })
        }
        val claimNames =
            backends(claimed).values.map {
                it
                    .filterIsInstance<PersistentVolumeClaim>()
                    .single()
                    .metadata.name
            }
        assertThat(claimNames).doesNotHaveDuplicates()
    }

    @Test
    fun `every backend runs as the default service account, with no credentials Secret on AWS`() {
        backends(ObservabilityHosting.AWS).minus(GRAFANA).forEach { (hostPath, resources) ->
            val pod = pod(resources)

            assertThat(pod.serviceAccountName).describedAs(hostPath).isEqualTo("default")
            assertThat(pod.containers.flatMap { it.envFrom.orEmpty() }).describedAs(hostPath).isEmpty()
        }
    }

    @Test
    fun `a store-credentials Secret is mounted into each backend's own container`() {
        val withSecret = ObservabilityHosting.AWS.copy(credentials = StoreCredentials.FromSecret("store-credentials"))

        backends(withSecret).minus(GRAFANA).forEach { (hostPath, resources) ->
            val pod = pod(resources)
            val withEnvFrom =
                pod.containers.filter { container ->
                    container.envFrom.orEmpty().any {
                        it.secretRef?.name ==
                            "store-credentials"
                    }
                }

            assertThat(withEnvFrom.map { it.name }).describedAs(hostPath).containsExactly(pod.containers.first().name)
        }
    }
}
