package com.rustyrazorblade.easydblab.configuration.tempo

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Tempo K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane with S3 backend for trace storage.
 * Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`),
 * not `__KEY__` template substitution. Env vars are injected from cluster-config ConfigMap.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class TempoManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "tempo"
        private const val CONFIGMAP_NAME = "tempo-config"
        private const val IMAGE = "grafana/tempo:2.10.0"
        private const val MEMORY_LIMIT = "512Mi"
        private const val MEMORY_REQUEST = "256Mi"
        private const val CPU_REQUEST = "100m"

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Tempo K8s resources in apply order.
     *
     * @return List of: ConfigMap, Service, Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildConfigMap(),
            buildService(),
            buildDeployment(),
        )

    /**
     * Builds the Tempo ConfigMap containing tempo.yaml.
     */
    fun buildConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                "tempo.yaml",
                templateService
                    .fromResource(
                        TempoManifestBuilder::class.java,
                        "tempo.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the Tempo ClusterIP Service with HTTP, OTLP gRPC, and OTLP HTTP ports.
     */
    fun buildService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(Constants.K8s.TEMPO_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("otlp-grpc")
            .withPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("otlp-http")
            .withPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withNewTargetPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Tempo Deployment.
     *
     * Runs on control plane with hostNetwork. Uses env vars from cluster-config
     * ConfigMap for S3 bucket and AWS region, with `-config.expand-env=true` to
     * enable Tempo's runtime env expansion in the config file.
     */
    @Suppress("LongMethod")
    fun buildDeployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
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
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs(
                "-config.file=/etc/tempo/tempo.yaml",
                "-config.expand-env=true",
            ).addNewEnv()
            .withName("AWS_REGION")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("aws_region")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .addNewEnv()
            .withName("S3_BUCKET")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("s3_bucket")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_PORT)
            .withHostPort(Constants.K8s.TEMPO_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withHostPort(Constants.K8s.TEMPO_OTLP_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withHostPort(Constants.K8s.TEMPO_OTLP_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(CPU_REQUEST))
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/tempo")
                    .build(),
                VolumeMountBuilder()
                    .withName("wal")
                    .withMountPath("/var/tempo")
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.TEMPO_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.K8s.TEMPO_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("wal")
            .withEmptyDir(
                EmptyDirVolumeSourceBuilder()
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
