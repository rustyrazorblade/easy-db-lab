package com.rustyrazorblade.easydblab.configuration.yace

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.controlPlaneDeployment
import com.rustyrazorblade.easydblab.configuration.hostNetworked
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.VolumeMountBuilder

/**
 * Builds YACE (Yet Another CloudWatch Exporter) K8s resources as typed Fabric8 objects.
 *
 * Creates a Deployment on the control node that scrapes CloudWatch metrics for
 * EMR, S3, EBS, EC2, and OpenSearch namespaces. OTel Collector scrapes YACE's
 * Prometheus endpoint, forwarding metrics to Mimir.
 *
 * Uses tag-based auto-discovery on the legacy marker tag ([Constants.Vpc.LEGACY_TAG_KEY]), which
 * every easy-db-lab resource carries, old or new, to find AWS resources.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class YaceManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val APP_LABEL = "yace"
        private const val CONFIG_DATA_KEY = "yace-config.yaml"
    }

    /**
     * Builds all YACE K8s resources in apply order.
     *
     * @return List of: ConfigMap, Deployment
     */
    fun buildAllResources(scope: InstallScope): List<HasMetadata> =
        listOf(
            buildConfigMap(scope),
            buildDeployment(scope),
        )

    /**
     * Builds the YACE ConfigMap containing the exporter config.
     */
    fun buildConfigMap(scope: InstallScope) =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(Constants.Yace.CONFIGMAP_NAME)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_DATA_KEY,
                templateService
                    .fromResource(
                        YaceManifestBuilder::class.java,
                        "yace-config.yaml",
                    ).substitute(
                        mapOf(
                            "LEGACY_TAG_KEY" to Constants.Vpc.LEGACY_TAG_KEY,
                            "TAG_VALUE" to Constants.Vpc.TAG_VALUE,
                            "CLUSTER_ID_TAG_KEY" to Constants.Vpc.CLUSTER_ID_TAG_KEY,
                        ),
                    ),
            ).build()

    /**
     * Builds the YACE Deployment.
     *
     * Runs on the control node with hostNetwork for YACE to use the node's
     * IAM role for CloudWatch API access.
     */
    fun buildDeployment(scope: InstallScope) =
        controlPlaneDeployment(Constants.Yace.DEPLOYMENT_NAME, scope.namespace.value, appLabel = APP_LABEL) {
            hostNetworked()
                .addNewContainer()
                .withName(APP_LABEL)
                .withImage(Constants.Yace.IMAGE)
                .withCommand("yace", "--config.file=/etc/yace/yace-config.yaml", "--listen-address=:${Constants.Yace.PORT}")
                .addNewPort()
                .withContainerPort(Constants.Yace.PORT)
                .withHostPort(Constants.Yace.PORT)
                .withProtocol("TCP")
                .withName("metrics")
                .endPort()
                .addToVolumeMounts(
                    VolumeMountBuilder()
                        .withName("config")
                        .withMountPath("/etc/yace/yace-config.yaml")
                        .withSubPath(CONFIG_DATA_KEY)
                        .withReadOnly(true)
                        .build(),
                ).endContainer()
                .addNewVolume()
                .withName("config")
                .withConfigMap(
                    ConfigMapVolumeSourceBuilder()
                        .withName(Constants.Yace.CONFIGMAP_NAME)
                        .build(),
                ).endVolume()
        }
}
