package com.rustyrazorblade.easydblab.configuration.s3manager

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds S3 Manager K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control plane providing a web UI for S3 browsing.
 * Pinned to v0.5.0 which uses simple env vars (USE_IAM, ENDPOINT, REGION).
 * The latest image switched to numbered instance env vars (1_NAME, etc.) which
 * are incompatible with K8s env var naming rules.
 *
 * @property templateService Used to read AWS region from cluster state
 */
class S3ManagerManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "s3manager"
        private const val IMAGE = "cloudlena/s3manager:v0.5.0"

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
    fun buildDeployment(): io.fabric8.kubernetes.api.model.apps.Deployment {
        val region = templateService.buildContextVariables()["AWS_REGION"] ?: "us-east-1"

        return DeploymentBuilder()
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
            .withNewStrategy()
            .withType("Recreate")
            .withRollingUpdate(null)
            .endStrategy()
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
            .withName("ENDPOINT")
            .withValue("s3.$region.amazonaws.com")
            .endEnv()
            .addNewEnv()
            .withName("REGION")
            .withValue(region)
            .endEnv()
            .addNewEnv()
            .withName("PORT")
            .withValue(Constants.K8s.S3MANAGER_PORT.toString())
            .endEnv()
            .withNewLivenessProbe()
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
}
