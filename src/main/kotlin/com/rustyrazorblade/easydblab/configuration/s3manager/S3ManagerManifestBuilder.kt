package com.rustyrazorblade.easydblab.configuration.s3manager

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds S3 Manager K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane providing a web UI for S3 browsing.
 * No TemplateService needed — all configuration is via env vars.
 */
class S3ManagerManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "s3manager"
        private const val IMAGE = "cloudlena/s3manager:latest"
        private const val MEMORY_LIMIT = "256Mi"
        private const val MEMORY_REQUEST = "64Mi"
        private const val CPU_REQUEST = "50m"

        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
    }

    /**
     * Builds all S3 Manager K8s resources in apply order.
     *
     * @return List of: Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildDeployment(),
        )

    /**
     * Builds the S3 Manager Deployment.
     *
     * Runs on control plane with hostNetwork. Uses IAM role for AWS credentials.
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
            .withContainerPort(Constants.K8s.S3MANAGER_PORT)
            .withHostPort(Constants.K8s.S3MANAGER_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewEnv()
            .withName("USE_IAM")
            .withValue("true")
            .endEnv()
            .addNewEnv()
            .withName("PORT")
            .withValue(Constants.K8s.S3MANAGER_PORT.toString())
            .endEnv()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(CPU_REQUEST))
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.S3MANAGER_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.S3MANAGER_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
