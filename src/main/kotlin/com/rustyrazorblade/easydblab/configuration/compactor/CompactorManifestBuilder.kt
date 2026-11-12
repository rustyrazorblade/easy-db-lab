package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds the `account-compactor` Deployment that runs on every cluster's control node, and the
 * ConfigMap it reads, as typed Fabric8 objects.
 *
 * One pod runs the lease agent and the Mimir, Loki, Tempo backend-scheduler and Tempo backend-worker
 * compactors, each under the gate (`compactor-gate.sh`), so a compactor runs only while this pod
 * holds the store lease. Pyroscope is not here: its compaction runs in each cluster. The pod uses
 * the pod network, so the compactors' ports, which are the cluster backends' own, never collide with
 * the backends on the host network, and every process shares one private `127.0.0.1`.
 *
 * Mimir and Loki read the cluster's own `mimir.yaml` and `loki.yaml`, rendered by [TemplateService],
 * so the compactors always read the store as the clusters write it. Compaction is on; retention and
 * every other deletion path are off.
 *
 * @property templateService renders the configuration files from the classpath.
 */
class CompactorManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        const val NAME = "account-compactor"
        const val CONFIGMAP_NAME = "account-compactor-config"

        const val GATE_INIT_CONTAINER = "gate-busybox"
        const val LEASE_CONTAINER = "lease"
        const val MIMIR_CONTAINER = "mimir-compactor"
        const val LOKI_CONTAINER = "loki-compactor"
        const val TEMPO_SCHEDULER_CONTAINER = "tempo-backend-scheduler"
        const val TEMPO_WORKER_CONTAINER = "tempo-backend-worker"

        const val CONFIG_VOLUME = "config"
        const val GATE_VOLUME = "gate-bin"
        const val LEASE_VOLUME = "lease"
        const val AGENT_VOLUME = "agent-jar"

        const val TEMPO_BACKEND_FILE = "tempo-backend.yaml"
        const val GATE_SCRIPT = "compactor-gate.sh"

        private const val NAMESPACE = Constants.K8s.NAMESPACE
        private const val APP_LABEL_KEY = "app.kubernetes.io/name"
    }

    /**
     * Builds the resources in apply order: the ConfigMap, then the Deployment.
     *
     * @param agentJar the lease agent jar on the control node; a new jar changes the pod template.
     * @param bucketRegion the account bucket's region, which every container signs for.
     */
    fun buildAllResources(
        agentJar: CompactorAgentJar,
        bucketRegion: String,
    ): List<HasMetadata> = listOf(buildConfigMap(), buildDeployment(agentJar, bucketRegion))

    /** The configuration files and the gate script. */
    fun buildConfigMap(): ConfigMap =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(APP_LABEL_KEY, NAME)
            .endMetadata()
            .addToData(MimirManifestBuilder.CONFIG_FILE, render(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE))
            .addToData(LokiManifestBuilder.CONFIG_FILE, render(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE))
            .addToData(TEMPO_BACKEND_FILE, render(CompactorManifestBuilder::class.java, TEMPO_BACKEND_FILE))
            .addToData(GATE_SCRIPT, render(CompactorManifestBuilder::class.java, GATE_SCRIPT))
            .build()

    private fun render(
        anchor: Class<*>,
        name: String,
    ): String = templateService.fromResource(anchor, name).substitute()

    private fun hostPath(
        name: String,
        path: String,
        type: String,
    ): Volume =
        VolumeBuilder()
            .withName(name)
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath(path)
                    .withType(type)
                    .build(),
            ).build()

    private fun emptyDir(name: String): Volume =
        VolumeBuilder()
            .withName(name)
            .withNewEmptyDir()
            .endEmptyDir()
            .build()

    /** The Deployment: one pod, replaced, never doubled, on the control node. */
    fun buildDeployment(
        agentJar: CompactorAgentJar,
        bucketRegion: String,
    ): Deployment {
        val containers = CompactorContainers(bucketRegion)
        return DeploymentBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(APP_LABEL_KEY, NAME)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels(APP_LABEL_KEY, NAME)
            .endSelector()
            .withNewStrategy()
            .withType("Recreate")
            .endStrategy()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(APP_LABEL_KEY, NAME)
            .endMetadata()
            .withNewSpec()
            .withTerminationGracePeriodSeconds(Constants.Compactor.Lease.TERMINATION_GRACE_SECONDS)
            .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
            .addNewToleration()
            .withKey("node-role.kubernetes.io/control-plane")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .endToleration()
            .withInitContainers(containers.init())
            .withContainers(listOf(containers.lease()) + containers.compactors())
            .withVolumes(
                listOf(
                    VolumeBuilder()
                        .withName(CONFIG_VOLUME)
                        .withNewConfigMap()
                        .withName(CONFIGMAP_NAME)
                        .endConfigMap()
                        .build(),
                    emptyDir(GATE_VOLUME),
                    emptyDir(LEASE_VOLUME),
                    hostPath(AGENT_VOLUME, agentJar.hostPath, "File"),
                ) + Scratch.entries.map { hostPath(it.volume, it.hostPath, "Directory") },
            ).endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }
}
