package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Grafana K8s resources as typed Fabric8 objects.
 *
 * Dashboards are not K8s objects. The CLI copies the whole dashboard tree onto the control
 * node's Grafana data directory ([GRAFANA_DASHBOARD_HOST_PATH]), which the Deployment already
 * mounts at `/var/lib/grafana`, and the provisioning ConfigMap declares one provider that sweeps
 * it. Nothing built here varies with how many dashboards or folders exist; the [catalog] is used
 * only to locate the home dashboard inside the tree.
 *
 * @property templateService Used for reading the cluster name for Grafana branding
 * @property catalog Every core dashboard on the classpath; supplies the home dashboard
 */
class GrafanaManifestBuilder(
    private val templateService: TemplateService,
    private val catalog: GrafanaDashboardCatalog,
) {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "grafana"
        private const val GRAFANA_IMAGE = "grafana/grafana:13.0.2"
        private const val IMAGE_RENDERER_IMAGE =
            "grafana/grafana-image-renderer@sha256:6c432f1aed266ce56433becacd197cdab708f9089104d67664207b4fe0975055"
        private const val GRAFANA_PORT = 3000
        private const val IMAGE_RENDERER_PORT = 8081
        private const val PROVISIONING_CONFIGMAP_NAME = "grafana-dashboards-config"
        private const val DATASOURCES_VOLUME = "datasources"
        private const val DASHBOARDS_CONFIG_VOLUME = "dashboards-config"
        private const val DATA_VOLUME = "data"
        const val GRAFANA_DATA_PATH = "/mnt/db1/grafana"

        /**
         * Where the dashboard tree lives on the control node. It is [GRAFANA_DASHBOARD_ROOT] seen
         * from the host: the Deployment mounts [GRAFANA_DATA_PATH] at `/var/lib/grafana`.
         */
        const val GRAFANA_DASHBOARD_HOST_PATH = "$GRAFANA_DATA_PATH/dashboards"

        @Suppress("MagicNumber")
        const val GRAFANA_UID = 472

        @Suppress("MagicNumber")
        private const val FS_GROUP = 472L

        @Suppress("MagicNumber")
        private const val RUN_AS_USER = 472L

        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10

        // Only externally-distributed plugins belong here. The Pyroscope datasource
        // (grafana-pyroscope-datasource) is BUNDLED as a core plugin in Grafana 13.x — listing it
        // makes the boot-time install step fail ("cannot install a Core plugin") and Grafana
        // CrashLoopBackOffs. It is still declared as a datasource in GrafanaDatasourceConfig; the
        // bundled plugin serves it without an install.
        private const val GRAFANA_PLUGINS =
            "grafana-clickhouse-datasource,victoriametrics-logs-datasource,grafana-polystat-panel"

        private const val RENDERER_TOKEN = "easydblab-renderer"
    }

    /**
     * Builds the dashboard provisioning ConfigMap that tells Grafana where to find dashboards.
     *
     * One file provider over the copied tree; see [GrafanaDashboardProvisioningConfig].
     */
    fun buildDashboardProvisioningConfigMap(): ConfigMap {
        val dashboardsYaml = GrafanaDashboardProvisioningConfig.forDashboardTree().toYaml()

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
     * Builds the Grafana Deployment.
     *
     * The cluster name for Grafana branding is read from TemplateService context variables.
     *
     * @return Fabric8 Deployment object
     */
    fun buildDeployment(): Deployment {
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
            ).withContainers(buildGrafanaContainer(clusterName), buildImageRendererContainer())
            .withVolumes(buildVolumes())
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /**
     * Builds all Grafana K8s resources: the provisioning ConfigMap and the Deployment.
     *
     * @return List of all Grafana K8s resources in apply order
     */
    fun buildAllResources(): List<HasMetadata> = listOf(buildDashboardProvisioningConfigMap(), buildDeployment())

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

    private fun buildImageRendererContainer(): Container =
        ContainerBuilder()
            .withName("grafana-image-renderer")
            .withImage(IMAGE_RENDERER_IMAGE)
            .addNewPort()
            .withContainerPort(IMAGE_RENDERER_PORT)
            .withProtocol("TCP")
            .endPort()
            .withEnv(listOf(envVar("AUTH_TOKEN", RENDERER_TOKEN)))
            .build()

    private fun buildVolumeMounts(): List<VolumeMount> =
        listOf(
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
            VolumeMountBuilder()
                .withName(DATA_VOLUME)
                .withMountPath("/var/lib/grafana")
                .build(),
        )

    private fun buildVolumes(): List<Volume> =
        listOf(
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
            VolumeBuilder()
                .withName(DATA_VOLUME)
                .withHostPath(
                    HostPathVolumeSourceBuilder()
                        .withPath(GRAFANA_DATA_PATH)
                        .withType("DirectoryOrCreate")
                        .build(),
                ).build(),
        )

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
            envVar("GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH", "$GRAFANA_DASHBOARD_ROOT/${catalog.home.relativePath}"),
            envVar("GF_RENDERING_SERVER_URL", "http://localhost:$IMAGE_RENDERER_PORT/render"),
            envVar("GF_RENDERING_CALLBACK_URL", "http://localhost:$GRAFANA_PORT/"),
            envVar("GF_RENDERING_RENDERER_TOKEN", RENDERER_TOKEN),
        )

    private fun envVar(
        name: String,
        value: String,
    ) = EnvVarBuilder()
        .withName(name)
        .withValue(value)
        .build()
}
