package com.rustyrazorblade.easydblab.configuration.headlamp

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder

/**
 * Builds all Headlamp K8s resources as typed Fabric8 objects.
 *
 * Headlamp is a Kubernetes web UI that runs on the control plane.
 * It includes the inspektor-gadget headlamp plugin, installed via an init container,
 * which provides a UI for running eBPF gadgets directly from the browser.
 *
 * The init container installs the plugin from npm using the headlamp-plugin CLI,
 * placing it in the shared plugins volume at /headlamp/plugins.
 *
 * Accessible at http://<control-node-ip>:4466
 */
class HeadlampManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "headlamp"
        private const val IMAGE = "ghcr.io/headlamp-k8s/headlamp:v0.26.0"
        private const val PLUGIN_INSTALLER_IMAGE = "node:20-alpine"
        private const val SERVICE_ACCOUNT_NAME = "headlamp"
        private const val CLUSTER_ROLE_NAME = "headlamp"
        const val PORT = Constants.K8s.HEADLAMP_PORT
        private const val PLUGINS_DIR = "/headlamp/plugins"
        private const val INSPEKTOR_GADGET_PLUGIN = "@inspektor-gadget/headlamp-plugin"
    }

    /**
     * Builds all Headlamp K8s resources in apply order.
     *
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, Service, Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildService(),
            buildDeployment(),
        )

    /**
     * Builds the ServiceAccount for Headlamp pods.
     */
    fun buildServiceAccount() =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(SERVICE_ACCOUNT_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .build()

    /**
     * Builds the ClusterRole granting Headlamp read access to all K8s resources.
     * Required to display cluster resources in the Headlamp dashboard.
     */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addNewRule()
            .withApiGroups("*")
            .withResources("*")
            .withVerbs("get", "list", "watch")
            .endRule()
            .build()

    /**
     * Builds the ClusterRoleBinding linking the ServiceAccount to the ClusterRole.
     */
    fun buildClusterRoleBinding() =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewRoleRef()
            .withApiGroup("rbac.authorization.k8s.io")
            .withKind("ClusterRole")
            .withName(CLUSTER_ROLE_NAME)
            .endRoleRef()
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName(SERVICE_ACCOUNT_NAME)
            .withNamespace(NAMESPACE)
            .endSubject()
            .build()

    /**
     * Builds the Headlamp ClusterIP Service.
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
            .withPort(PORT)
            .withNewTargetPort(PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Headlamp Deployment.
     *
     * Runs on the control plane with hostNetwork and Recreate strategy.
     * An init container installs the inspektor-gadget headlamp plugin into a shared
     * plugins volume, which the main container mounts at /headlamp/plugins.
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
            .withNewStrategy()
            .withType("Recreate")
            .withRollingUpdate(null)
            .endStrategy()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(SERVICE_ACCOUNT_NAME)
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
            .addNewToleration()
            .withKey("node-role.kubernetes.io/control-plane")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .endToleration()
            .addNewInitContainer()
            .withName("install-plugins")
            .withImage(PLUGIN_INSTALLER_IMAGE)
            .withCommand("sh", "-c")
            .withArgs(
                "npx --yes @kinvolk/headlamp-plugin install $INSPEKTOR_GADGET_PLUGIN " +
                    "--dest $PLUGINS_DIR",
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("plugins")
                    .withMountPath(PLUGINS_DIR)
                    .build(),
            ).endInitContainer()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs(
                "-in-cluster",
                "-plugins-dir=$PLUGINS_DIR",
                "-listen-addr=0.0.0.0:$PORT",
            ).addNewPort()
            .withContainerPort(PORT)
            .withHostPort(PORT)
            .withProtocol("TCP")
            .withName("http")
            .endPort()
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("plugins")
                    .withMountPath(PLUGINS_DIR)
                    .withReadOnly(true)
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("plugins")
            .withEmptyDir(EmptyDirVolumeSourceBuilder().build())
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
