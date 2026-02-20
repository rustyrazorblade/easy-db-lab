package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Grafana K8s resources as typed Fabric8 objects.
 *
 * Replaces the YAML-based approach where dashboard JSON was embedded inside ConfigMap YAML files.
 * Dashboard JSON is loaded from classpath resource files and wrapped in programmatically-built
 * ConfigMaps. The Deployment is also built in code, dynamically adding volume mounts for each
 * dashboard entry in [GrafanaDashboard].
 *
 * @property templateService Used for loading dashboard JSON from classpath and performing
 *   `__KEY__` variable substitution
 */
class GrafanaManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "grafana"
        private const val GRAFANA_IMAGE = "grafana/grafana:latest"
        private const val GRAFANA_PORT = 3000
        private const val PROVISIONING_CONFIGMAP_NAME = "grafana-dashboards-config"
        private const val DATASOURCES_VOLUME = "datasources"
        private const val DASHBOARDS_CONFIG_VOLUME = "dashboards-config"
        private const val DATA_VOLUME = "data"

        @Suppress("MagicNumber")
        private const val FS_GROUP = 472L

        @Suppress("MagicNumber")
        private const val RUN_AS_USER = 472L

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10

        private const val GRAFANA_PLUGINS =
            "grafana-clickhouse-datasource,victoriametrics-logs-datasource,grafana-pyroscope-datasource,grafana-polystat-panel"

        private const val DASHBOARDS_YAML_RESOURCE = "dashboards.yaml"
    }

    /**
     * Builds the dashboard provisioning ConfigMap that tells Grafana where to find dashboards.
     *
     * Loads dashboards.yaml from a classpath resource file.
     * Replaces `core/14-grafana-dashboards-configmap.yaml`.
     */
    fun buildDashboardProvisioningConfigMap(): ConfigMap {
        val dashboardsYaml =
            templateService
                .fromResource(
                    GrafanaManifestBuilder::class.java,
                    DASHBOARDS_YAML_RESOURCE,
                ).substitute()

        return ConfigMapBuilder()
            .withNewMetadata()
            .withName(PROVISIONING_CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData("dashboards.yaml", dashboardsYaml)
            .build()
    }

    /**
     * Builds a ConfigMap for a single dashboard.
     *
     * Loads the dashboard JSON via [TemplateService.fromResource] which performs
     * `__KEY__` variable substitution, then wraps it in a Fabric8 ConfigMap.
     *
     * @param dashboard The dashboard to build a ConfigMap for
     * @return ConfigMap containing the substituted dashboard JSON
     */
    fun buildDashboardConfigMap(dashboard: GrafanaDashboard): ConfigMap {
        val template =
            templateService.fromResource(
                GrafanaDashboard::class.java,
                dashboard.resourcePath,
            )
        val json = template.substitute()

        return ConfigMapBuilder()
            .withNewMetadata()
            .withName(dashboard.configMapName)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .addToLabels("grafana_dashboard", "1")
            .endMetadata()
            .addToData(dashboard.jsonFileName, json)
            .build()
    }

    /**
     * Builds the Grafana Deployment.
     *
     * Dynamically generates volume and volumeMount entries for every [GrafanaDashboard] entry,
     * so adding a new dashboard only requires a new enum entry and JSON file.
     * The cluster name for Grafana branding is read from TemplateService context variables.
     *
     * Replaces `core/41-grafana-deployment.yaml`.
     *
     * @return Fabric8 Deployment object
     */
    fun buildDeployment(): io.fabric8.kubernetes.api.model.apps.Deployment {
        val clusterName = templateService.buildContextVariables()["CLUSTER_NAME"] ?: "cluster"

        return DeploymentBuilder()
            .withNewMetadata()
            .withName("grafana")
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
            .withSecurityContext(
                PodSecurityContextBuilder()
                    .withFsGroup(FS_GROUP)
                    .withRunAsUser(RUN_AS_USER)
                    .build(),
            ).withContainers(buildGrafanaContainer(clusterName))
            .withVolumes(buildVolumes())
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /**
     * Builds all Grafana K8s resources: provisioning ConfigMap, dashboard ConfigMaps, and Deployment.
     *
     * @return List of all Grafana K8s resources in apply order
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(buildDashboardProvisioningConfigMap()) +
            GrafanaDashboard.entries.map { buildDashboardConfigMap(it) } +
            listOf(buildDeployment())

    private fun buildGrafanaContainer(clusterName: String): Container =
        ContainerBuilder()
            .withName("grafana")
            .withImage(GRAFANA_IMAGE)
            .addNewPort()
            .withContainerPort(GRAFANA_PORT)
            .withHostPort(GRAFANA_PORT)
            .withProtocol("TCP")
            .endPort()
            .withEnv(buildEnvVars(clusterName))
            .withVolumeMounts(buildVolumeMounts())
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/api/health")
            .withNewPort(GRAFANA_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/api/health")
            .withNewPort(GRAFANA_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .build()

    private fun buildVolumeMounts(): List<VolumeMount> {
        val dashboardMounts =
            GrafanaDashboard.entries.map { dashboard ->
                VolumeMountBuilder()
                    .withName(dashboard.volumeName)
                    .withMountPath(dashboard.mountPath)
                    .withReadOnly(true)
                    .build()
            }

        return listOf(
            VolumeMountBuilder()
                .withName(DATASOURCES_VOLUME)
                .withMountPath("/etc/grafana/provisioning/datasources")
                .withReadOnly(true)
                .build(),
            VolumeMountBuilder()
                .withName(DASHBOARDS_CONFIG_VOLUME)
                .withMountPath("/etc/grafana/provisioning/dashboards")
                .withReadOnly(true)
                .build(),
        ) + dashboardMounts +
            listOf(
                VolumeMountBuilder()
                    .withName(DATA_VOLUME)
                    .withMountPath("/var/lib/grafana")
                    .build(),
            )
    }

    private fun buildVolumes(): List<Volume> {
        val dashboardVolumes =
            GrafanaDashboard.entries.map { dashboard ->
                VolumeBuilder()
                    .withName(dashboard.volumeName)
                    .withConfigMap(
                        ConfigMapVolumeSourceBuilder()
                            .withName(dashboard.configMapName)
                            .withOptional(dashboard.optional)
                            .build(),
                    ).build()
            }

        return listOf(
            VolumeBuilder()
                .withName(DATASOURCES_VOLUME)
                .withNewConfigMap()
                .withName("grafana-datasources")
                .endConfigMap()
                .build(),
            VolumeBuilder()
                .withName(DASHBOARDS_CONFIG_VOLUME)
                .withNewConfigMap()
                .withName(PROVISIONING_CONFIGMAP_NAME)
                .endConfigMap()
                .build(),
        ) + dashboardVolumes +
            listOf(
                VolumeBuilder()
                    .withName(DATA_VOLUME)
                    .withNewEmptyDir()
                    .endEmptyDir()
                    .build(),
            )
    }

    private fun buildEnvVars(clusterName: String): List<EnvVar> =
        listOf(
            envVar("GF_INSTALL_PLUGINS", GRAFANA_PLUGINS),
            envVar("GF_SECURITY_ADMIN_USER", "admin"),
            envVar("GF_SECURITY_ADMIN_PASSWORD", "admin"),
            envVar("GF_USERS_ALLOW_SIGN_UP", "false"),
            envVar("GF_AUTH_ANONYMOUS_ENABLED", "true"),
            envVar("GF_USERS_DEFAULT_ORG_NAME", "Main Org."),
            envVar("GF_AUTH_ANONYMOUS_ORG_NAME", "Main Org."),
            envVar("GF_AUTH_ANONYMOUS_ORG_ROLE", "Admin"),
            envVar("GF_AUTH_DISABLE_LOGIN_FORM", "false"),
            envVar("GF_AUTH_BASIC_ENABLED", "true"),
            envVar("GF_BRANDING_APP_TITLE", clusterName),
            envVar(
                "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH",
                "${GrafanaDashboard.SYSTEM.mountPath}/${GrafanaDashboard.SYSTEM.jsonFileName}",
            ),
        )

    private fun envVar(
        name: String,
        value: String,
    ) = EnvVarBuilder()
        .withName(name)
        .withValue(value)
        .build()
}
