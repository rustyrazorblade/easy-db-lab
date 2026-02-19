package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Builds all Pyroscope K8s resources as typed Fabric8 objects.
 *
 * Creates the Pyroscope server (Deployment + Service + ConfigMap) for continuous profiling storage,
 * and the Grafana Alloy eBPF agent (DaemonSet + ConfigMap) that collects CPU profiles from all nodes.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class PyroscopeManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private const val NAMESPACE = "default"

        // Pyroscope server constants
        private const val SERVER_APP_LABEL = "pyroscope"
        private const val SERVER_CONFIGMAP_NAME = "pyroscope-config"
        private const val SERVER_IMAGE = "grafana/pyroscope:1.18.0"
        const val SERVER_PORT = 4040

        @Suppress("MagicNumber")
        const val PYROSCOPE_UID = 10001L
        private const val DATA_PATH = "/mnt/db1/pyroscope"
        private const val SERVER_MEMORY_LIMIT = "512Mi"
        private const val SERVER_MEMORY_REQUEST = "256Mi"
        private const val SERVER_CPU_REQUEST = "100m"
        private const val SERVER_LIVENESS_INITIAL_DELAY = 30
        private const val SERVER_LIVENESS_PERIOD = 15
        private const val SERVER_READINESS_INITIAL_DELAY = 5
        private const val SERVER_READINESS_PERIOD = 10

        // eBPF agent constants
        private const val EBPF_APP_LABEL = "pyroscope-ebpf"
        private const val EBPF_CONFIGMAP_NAME = "pyroscope-ebpf-config"
        private const val ALLOY_IMAGE = "grafana/alloy:v1.13.1"
        private const val ALLOY_PORT = 12345
        private const val EBPF_MEMORY_LIMIT = "256Mi"
        private const val EBPF_MEMORY_REQUEST = "128Mi"
        private const val EBPF_CPU_REQUEST = "50m"
    }

    /**
     * Builds all Pyroscope K8s resources in apply order.
     *
     * @return List of: server ConfigMap, server Service, server Deployment,
     *   eBPF ConfigMap, eBPF DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildServerConfigMap(),
            buildServerService(),
            buildServerDeployment(),
            buildEbpfConfigMap(),
            buildEbpfDaemonSet(),
        )

    /**
     * Builds the Pyroscope server ConfigMap containing config.yaml.
     */
    fun buildServerConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(SERVER_CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .addToData(
                "config.yaml",
                templateService
                    .fromResource(
                        PyroscopeManifestBuilder::class.java,
                        "config.yaml",
                    ).substitute(),
            ).build()

    /**
     * Builds the Pyroscope ClusterIP Service on port 4040.
     */
    fun buildServerService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(SERVER_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(SERVER_PORT)
            .withNewTargetPort(SERVER_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Pyroscope server Deployment.
     *
     * Runs on the control plane node with hostNetwork enabled.
     * Data is stored on a hostPath volume at /mnt/db1/pyroscope.
     * The directory permissions must be set via SSH before applying.
     */
    fun buildServerDeployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(SERVER_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
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
            .withContainers(buildServerContainer())
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(SERVER_CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath(DATA_PATH)
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private fun buildServerContainer(): Container =
        ContainerBuilder()
            .withName(SERVER_APP_LABEL)
            .withImage(SERVER_IMAGE)
            .withArgs("-config.file=/etc/pyroscope/config.yaml")
            .addNewPort()
            .withContainerPort(SERVER_PORT)
            .withHostPort(SERVER_PORT)
            .withProtocol("TCP")
            .endPort()
            .withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(SERVER_MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(SERVER_MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(SERVER_CPU_REQUEST))
                    .build(),
            ).addNewVolumeMount()
            .withName("config")
            .withMountPath("/etc/pyroscope")
            .endVolumeMount()
            .addNewVolumeMount()
            .withName("data")
            .withMountPath("/data")
            .endVolumeMount()
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(SERVER_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(SERVER_LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(SERVER_LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(SERVER_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(SERVER_READINESS_INITIAL_DELAY)
            .withPeriodSeconds(SERVER_READINESS_PERIOD)
            .endReadinessProbe()
            .build()

    /**
     * Builds the eBPF agent ConfigMap containing config.alloy.
     */
    fun buildEbpfConfigMap() =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(EBPF_CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .addToData(
                "config.alloy",
                templateService
                    .fromResource(
                        PyroscopeManifestBuilder::class.java,
                        "config.alloy",
                    ).substitute(),
            ).build()

    /**
     * Builds the eBPF agent DaemonSet.
     *
     * Runs Grafana Alloy with pyroscope.ebpf on all nodes (tolerates everything).
     * Requires hostPID and privileged mode for eBPF access.
     */
    @Suppress("LongMethod")
    fun buildEbpfDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(EBPF_APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withHostNetwork(true)
            .withHostPID(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName("alloy")
            .withImage(ALLOY_IMAGE)
            .withArgs(
                "run",
                "--server.http.listen-addr=0.0.0.0:$ALLOY_PORT",
                "/etc/alloy/config.alloy",
            ).addNewEnv()
            .withName("HOSTNAME")
            .withNewValueFrom()
            .withNewFieldRef()
            .withFieldPath("spec.nodeName")
            .endFieldRef()
            .endValueFrom()
            .endEnv()
            .addNewEnv()
            .withName("CLUSTER_NAME")
            .withNewValueFrom()
            .withNewConfigMapKeyRef()
            .withName("cluster-config")
            .withKey("cluster_name")
            .endConfigMapKeyRef()
            .endValueFrom()
            .endEnv()
            .addNewEnv()
            .withName("PYROSCOPE_URL")
            .withValue("http://pyroscope.default.svc.cluster.local:$SERVER_PORT")
            .endEnv()
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).withResources(
                ResourceRequirementsBuilder()
                    .addToLimits("memory", Quantity(EBPF_MEMORY_LIMIT))
                    .addToRequests("memory", Quantity(EBPF_MEMORY_REQUEST))
                    .addToRequests("cpu", Quantity(EBPF_CPU_REQUEST))
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/alloy")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("sym-cache")
                    .withMountPath("/tmp/symb-cache")
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(EBPF_CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("sym-cache")
            .withNewEmptyDir()
            .endEmptyDir()
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
