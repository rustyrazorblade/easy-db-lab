package com.rustyrazorblade.easydblab.configuration.victoria

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all VictoriaMetrics and VictoriaLogs K8s resources as typed Fabric8 objects.
 *
 * Both run on the control plane with hostNetwork and hostPath data volumes.
 * No ConfigMap or TemplateService needed — all configuration is via CLI args.
 */
class VictoriaManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"

        private const val VM_APP_LABEL = "victoriametrics"
        private const val VM_IMAGE = "victoriametrics/victoria-metrics:latest"
        private const val VM_DATA_PATH = "/mnt/db1/victoriametrics"

        private const val VL_APP_LABEL = "victorialogs"
        private const val VL_IMAGE = "victoriametrics/victoria-logs:latest"
        private const val VL_DATA_PATH = "/mnt/db1/victorialogs"

        private const val RETENTION_PERIOD = "7d"

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Victoria K8s resources in apply order.
     *
     * @return List of: VM Service, VM Deployment, VL Service, VL Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildMetricsService(),
            buildMetricsDeployment(),
            buildLogsService(),
            buildLogsDeployment(),
        )

    /**
     * Builds the VictoriaMetrics ClusterIP Service.
     */
    fun buildMetricsService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(VM_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", VM_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(Constants.K8s.VICTORIAMETRICS_PORT)
            .withNewTargetPort(Constants.K8s.VICTORIAMETRICS_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", VM_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the VictoriaMetrics Deployment.
     *
     * Runs on control plane with hostNetwork and hostPath data volume.
     */
    fun buildMetricsDeployment() =
        buildControlPlaneDeployment(
            name = VM_APP_LABEL,
            image = VM_IMAGE,
            port = Constants.K8s.VICTORIAMETRICS_PORT,
            dataPath = VM_DATA_PATH,
            containerDataPath = "/victoria-metrics-data",
            args =
                listOf(
                    "-storageDataPath=/victoria-metrics-data",
                    "-retentionPeriod=$RETENTION_PERIOD",
                    "-httpListenAddr=0.0.0.0:${Constants.K8s.VICTORIAMETRICS_PORT}",
                ),
            healthPath = "/health",
        )

    /**
     * Builds the VictoriaLogs ClusterIP Service.
     */
    fun buildLogsService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(VL_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", VL_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(Constants.K8s.VICTORIALOGS_PORT)
            .withNewTargetPort(Constants.K8s.VICTORIALOGS_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", VL_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the VictoriaLogs Deployment.
     *
     * Runs on control plane with hostNetwork and hostPath data volume.
     */
    fun buildLogsDeployment() =
        buildControlPlaneDeployment(
            name = VL_APP_LABEL,
            image = VL_IMAGE,
            port = Constants.K8s.VICTORIALOGS_PORT,
            dataPath = VL_DATA_PATH,
            containerDataPath = "/victoria-logs-data",
            args =
                listOf(
                    "-storageDataPath=/victoria-logs-data",
                    "-retentionPeriod=$RETENTION_PERIOD",
                    "-httpListenAddr=0.0.0.0:${Constants.K8s.VICTORIALOGS_PORT}",
                ),
            healthPath = "/health",
        )

    @Suppress("LongParameterList", "LongMethod")
    private fun buildControlPlaneDeployment(
        name: String,
        image: String,
        port: Int,
        dataPath: String,
        containerDataPath: String,
        args: List<String>,
        healthPath: String,
    ) = DeploymentBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace(NAMESPACE)
        .addToLabels("app.kubernetes.io/name", name)
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewSelector()
        .addToMatchLabels("app.kubernetes.io/name", name)
        .endSelector()
        .withNewTemplate()
        .withNewMetadata()
        .addToLabels("app.kubernetes.io/name", name)
        .endMetadata()
        .withNewSpec()
        .withHostNetwork(true)
        .withDnsPolicy("ClusterFirstWithHostNet")
        .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
        .addNewToleration()
        .withKey("node-role.kubernetes.io/control-plane")
        .withOperator("Exists")
        .withEffect("NoSchedule")
        .endToleration()
        .addNewContainer()
        .withName(name)
        .withImage(image)
        .withArgs(args)
        .addNewPort()
        .withContainerPort(port)
        .withHostPort(port)
        .withProtocol("TCP")
        .endPort()
        .addToVolumeMounts(
            VolumeMountBuilder()
                .withName("data")
                .withMountPath(containerDataPath)
                .build(),
        ).withNewLivenessProbe()
        .withNewHttpGet()
        .withPath(healthPath)
        .withNewPort(port)
        .endHttpGet()
        .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
        .withPeriodSeconds(LIVENESS_PERIOD)
        .endLivenessProbe()
        .withNewReadinessProbe()
        .withNewHttpGet()
        .withPath(healthPath)
        .withNewPort(port)
        .endHttpGet()
        .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
        .withPeriodSeconds(READINESS_PERIOD)
        .endReadinessProbe()
        .endContainer()
        .addNewVolume()
        .withName("data")
        .withHostPath(
            HostPathVolumeSourceBuilder()
                .withPath(dataPath)
                .withType("DirectoryOrCreate")
                .build(),
        ).endVolume()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
}
