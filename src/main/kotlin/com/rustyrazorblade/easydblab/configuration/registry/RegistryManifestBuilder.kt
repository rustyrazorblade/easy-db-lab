package com.rustyrazorblade.easydblab.configuration.registry

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds Docker Registry K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane with TLS certificate hostPath mount
 * and HTTPS liveness/readiness probes. No TemplateService needed.
 */
class RegistryManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "registry"
        private const val IMAGE = "registry:2"
        private const val MEMORY_LIMIT = "256Mi"
        private const val MEMORY_REQUEST = "128Mi"
        private const val CPU_REQUEST = "50m"

        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all Registry K8s resources in apply order.
     *
     * @return List of: Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildDeployment(),
        )

    /**
     * Builds the Docker Registry Deployment.
     *
     * Runs on control plane with hostNetwork. Uses TLS certificates from
     * a hostPath volume and HTTPS health probes.
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
            .addNewPort()
            .withContainerPort(Constants.K8s.REGISTRY_PORT)
            .withHostPort(Constants.K8s.REGISTRY_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewEnv()
            .withName("REGISTRY_HTTP_TLS_CERTIFICATE")
            .withValue("/certs/registry.crt")
            .endEnv()
            .addNewEnv()
            .withName("REGISTRY_HTTP_TLS_KEY")
            .withValue("/certs/registry.key")
            .endEnv()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(CPU_REQUEST))
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("data")
                    .withMountPath("/var/lib/registry")
                    .build(),
                VolumeMountBuilder()
                    .withName("certs")
                    .withMountPath("/certs")
                    .withReadOnly(true)
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/v2/")
            .withNewPort(Constants.K8s.REGISTRY_PORT)
            .withScheme("HTTPS")
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/v2/")
            .withNewPort(Constants.K8s.REGISTRY_PORT)
            .withScheme("HTTPS")
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .addNewVolume()
            .withName("data")
            .withEmptyDir(
                EmptyDirVolumeSourceBuilder()
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("certs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath(Constants.Registry.CERT_DIR)
                    .withType("Directory")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
