package com.rustyrazorblade.easydblab.configuration.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StateVolume
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Grafana K8s resources as typed Fabric8 objects.
 *
 * The provisioning ConfigMap declares one provider that sweeps [GRAFANA_DASHBOARD_ROOT]; the core
 * dashboards are ConfigMaps that [GrafanaDashboardConfigMaps] mounts there, one projected volume per
 * folder. Nothing built here varies with how many dashboards or folders exist; the home dashboard
 * is a fixed path in that tree ([Constants.Grafana.HOME_DASHBOARD_PATH]).
 *
 * Grafana listens on loopback only, on [Constants.Grafana.INTERNAL_PORT]. An nginx front in the pod
 * holds Grafana's client port and serves the test documents under `/reports/` beside it
 * ([GrafanaFront]), so the dashboards' documents iframe is same-origin. Grafana runs with
 * `disable_sanitize_html` so a Text panel can hold that iframe.
 *
 * @property templateService Used for reading the cluster name for Grafana branding
 */
class GrafanaManifestBuilder(
    private val templateService: TemplateService,
) {
    private val front = GrafanaFront(templateService)

    companion object {
        /** The Grafana Deployment's name, which the deploy waits on to finish rolling out. */
        const val DEPLOYMENT_NAME = "grafana"
        private const val NAMESPACE = "default"
        private const val APP_LABEL = Constants.K8s.GRAFANA_APP_LABEL

        /** The Grafana image the cluster deploys; tests run the same one. */
        const val GRAFANA_IMAGE = "grafana/grafana:13.2.2"
        private const val IMAGE_RENDERER_IMAGE = "grafana/grafana-image-renderer:v5.12.4"
        private const val IMAGE_RENDERER_PORT = 8081
        private const val PROVISIONING_CONFIGMAP_NAME = "grafana-dashboards-config"
        private const val DATASOURCES_VOLUME = "datasources"
        private const val DASHBOARDS_CONFIG_VOLUME = "dashboards-config"
        private const val DATA_VOLUME = "data"
        const val GRAFANA_DATA_PATH = "/mnt/db1/grafana"

        private val STATE = StateVolume(APP_LABEL, DATA_VOLUME, GRAFANA_DATA_PATH, owner = GRAFANA_UID.toLong())

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
            "grafana-clickhouse-datasource,grafana-polystat-panel"

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
     * @param documents The bucket and region the documents front and its signing proxy read from
     * @param hosting Where the Deployment is placed and keeps its database and dashboard tree
     * @return Fabric8 Deployment object
     */
    fun buildDeployment(
        documents: DocumentsBucket,
        hosting: ObservabilityHosting,
    ): Deployment = hostedResources(documents, hosting).filterIsInstance<Deployment>().single()

    private fun hostedResources(
        documents: DocumentsBucket,
        hosting: ObservabilityHosting,
    ): List<HasMetadata> = hosting.host(buildPodTemplate(documents), STATE, isBackend = false)

    private fun buildPodTemplate(documents: DocumentsBucket): Deployment {
        val clusterName = templateService.buildContextVariables()["CLUSTER_NAME"] ?: "cluster"

        return DeploymentBuilder()
            .withNewMetadata()
            .withName(DEPLOYMENT_NAME)
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
            .withSecurityContext(
                PodSecurityContextBuilder()
                    .withFsGroup(FS_GROUP)
                    .withRunAsUser(RUN_AS_USER)
                    .build(),
            ).withContainers(
                listOf(buildGrafanaContainer(clusterName), buildImageRendererContainer()) + front.containers(documents),
            ).withVolumes(buildVolumes() + front.volume())
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
    }

    /**
     * Grafana's Service on its HTTP port, so a port-forward reaches Grafana through a Service, as it
     * does Mimir, Loki, Tempo and Pyroscope, rather than through a pod; [publishing] puts it on
     * [Constants.K8s.GRAFANA_NODE_PORT].
     */
    fun buildService(publishing: ServicePublishing = ServicePublishing.ClusterIp): Service =
        publishing.publish(
            clusterIpService(),
            mapOf(
                "http" to Constants.K8s.GRAFANA_NODE_PORT,
            ),
        )

    private fun clusterIpService(): Service =
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
            .withPort(Constants.K8s.GRAFANA_PORT)
            .withNewTargetPort(Constants.K8s.GRAFANA_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds all Grafana K8s resources: the provisioning ConfigMap, the documents web server's
     * ConfigMap, the Deployment and the Service.
     *
     * @param documents The bucket and region the documents front and its signing proxy read from
     * @param hosting Where the Deployment is placed and keeps its database and dashboard tree
     * @return List of all Grafana K8s resources in apply order
     */
    fun buildAllResources(
        documents: DocumentsBucket,
        hosting: ObservabilityHosting,
    ): List<HasMetadata> =
        listOf(buildDashboardProvisioningConfigMap(), front.configMap(documents)) +
            hostedResources(documents, hosting) +
            buildService(hosting.publishing)

    private fun buildGrafanaContainer(clusterName: String): Container =
        ContainerBuilder()
            .withName("grafana")
            .withImage(GRAFANA_IMAGE)
            // No container port: Grafana listens on loopback only, and the front holds its client port.
            .withEnv(buildEnvVars(clusterName))
            .withVolumeMounts(buildVolumeMounts())
            .withNewLivenessProbe()
            .withNewExec()
            .withCommand(healthCheck())
            .endExec()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewExec()
            .withCommand(healthCheck())
            .endExec()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .build()

    /**
     * Grafana's health, asked from inside its container: the kubelet dials the pod IP, which a
     * loopback listener does not answer, so an HTTP probe would never reach it.
     */
    private fun healthCheck(): List<String> =
        listOf("wget", "-q", "-O", "/dev/null", "http://127.0.0.1:${Constants.Grafana.INTERNAL_PORT}${GrafanaFront.HEALTH_PATH}")

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
            VolumeBuilder().withName(STATE.volumeName).build(),
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
            envVar(
                "GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH",
                "$GRAFANA_DASHBOARD_ROOT/${Constants.Grafana.HOME_DASHBOARD_PATH}",
            ),
            envVar("GF_RENDERING_SERVER_URL", "http://localhost:$IMAGE_RENDERER_PORT/render"),
            envVar("GF_RENDERING_CALLBACK_URL", "http://127.0.0.1:${Constants.Grafana.INTERNAL_PORT}/"),
            // Grafana listens on loopback only; the front holds its client port.
            envVar("GF_SERVER_HTTP_ADDR", "127.0.0.1"),
            envVar("GF_SERVER_HTTP_PORT", Constants.Grafana.INTERNAL_PORT.toString()),
            envVar("GF_RENDERING_RENDERER_TOKEN", RENDERER_TOKEN),
            // The Tests and comparison dashboards show a test's documents in an iframe in a Text panel.
            envVar(Constants.Grafana.DISABLE_SANITIZE_HTML_ENV, "true"),
        )

    private fun envVar(
        name: String,
        value: String,
    ) = EnvVarBuilder()
        .withName(name)
        .withValue(value)
        .build()
}
