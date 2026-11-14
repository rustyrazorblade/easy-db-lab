package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.CniMode
import com.rustyrazorblade.easydblab.configuration.clusterConfigEnv
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPort
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder

/**
 * Builds the host add-on collector (`otel-collector-host`): the part of node telemetry only a
 * privileged, host-network pod can read.
 *
 * It exists because the core collector ([OtelManifestBuilder]) is unprivileged and must run in
 * environments that grant neither host networking nor hostPath volumes; everything that needs the
 * host lives here, and is deployed only where the environment grants `PRIVILEGED`. It owns the
 * node's OTLP listener on 4317 and 4318 (host processes that are not pods keep their endpoint),
 * host metrics, host log files and the localhost scrapes of Beyla, ebpf_exporter and, on Cilium
 * clusters, the Cilium agent, Hubble and operator. It exports nothing to a backend: every pipeline
 * forwards over OTLP to the core through the `otel-collector` Service, which keeps the traffic on
 * the node (`internalTrafficPolicy: Local`).
 *
 * @property templateService Used for loading the config from classpath resources
 */
class OtelHostCollectorManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private val NAMESPACE = Constants.K8s.NAMESPACE
        private const val NAME = Constants.OtelCollector.HOST_COLLECTOR_NAME
        private const val NAME_LABEL = "app.kubernetes.io/name"
        private const val CONFIGMAP_NAME = "otel-collector-host-config"
        private const val CONFIG_DATA_KEY = "otel-host-collector-config.yaml"
        private const val CONFIG_PATH = "/etc/$CONFIG_DATA_KEY"
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10

        /** The host-root volume, which `host_metrics` reads through its `root_path`. */
        const val HOST_ROOT_VOLUME = "host-root"

        /** Where the host root is mounted, and the `host_metrics` receiver's `root_path`. */
        const val HOST_ROOT_MOUNT_PATH = "/hostfs"

        /** The host directories the add-on's file receivers read, as volume name to host path. */
        private val HOST_LOG_DIRS =
            listOf(
                "var-log" to "/var/log",
                "cassandra-logs" to "/mnt/db1/cassandra/logs",
            )
    }

    /**
     * Builds every add-on resource in apply order.
     *
     * @param cni The cluster's CNI; Cilium adds the agent, Hubble and operator scrape jobs.
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, DaemonSet
     */
    fun buildAllResources(cni: CniMode): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildConfigMap(cni),
            buildDaemonSet(),
        )

    /** The add-on's own ServiceAccount, so its API access is its own, not the core's. */
    fun buildServiceAccount() =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .build()

    /** Read access to pods, for the pod-discovery scrape of the Cilium operator. */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(NAME)
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .addNewRule()
            .withApiGroups("")
            .withResources("pods")
            .withVerbs("get", "watch", "list")
            .endRule()
            .build()

    /** Binds [buildClusterRole] to the add-on's ServiceAccount. */
    fun buildClusterRoleBinding() =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(NAME)
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .withNewRoleRef()
            .withApiGroup("rbac.authorization.k8s.io")
            .withKind("ClusterRole")
            .withName(NAME)
            .endRoleRef()
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .endSubject()
            .build()

    /** The add-on's config, with the CNI scrape jobs rendered in. */
    fun buildConfigMap(cni: CniMode) =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .addToData(
                CONFIG_DATA_KEY,
                templateService
                    .fromResource(OtelHostCollectorManifestBuilder::class.java, CONFIG_DATA_KEY)
                    .substitute(mapOf("CNI_SCRAPE_JOBS" to PrometheusScrapeJob.render(buildCniScrapeJobs(cni)))),
            ).build()

    /**
     * The CNI's own scrape jobs. Flannel exposes no metrics, so it has none; rendering Cilium jobs
     * on a Flannel cluster would scrape three ports nothing listens on for the life of the cluster.
     *
     * The agent and Hubble are per-node host-network endpoints, scraped at `localhost`, which only a
     * host-network collector can reach. The operator runs on exactly one node, so it is found by pod
     * discovery in `kube-system` (label `io.cilium/app=operator`, its metrics port) and scraped only
     * by the add-on on that node.
     */
    fun buildCniScrapeJobs(cni: CniMode): List<PrometheusScrapeJob> =
        when (cni) {
            CniMode.Flannel -> emptyList()
            CniMode.Cilium ->
                listOf(
                    NodeScrapeJobs.localhostJob("cilium-agent", Constants.Cilium.AGENT_METRICS_PORT),
                    NodeScrapeJobs.localhostJob("hubble", Constants.Cilium.HUBBLE_METRICS_PORT),
                    NodeScrapeJobs.nodeLocalPodJob(
                        jobName = "cilium-operator",
                        namespace = Constants.Cilium.NAMESPACE,
                        podLabel = Constants.Cilium.OPERATOR_POD_LABEL,
                        podLabelValue = Constants.Cilium.OPERATOR_POD_LABEL_VALUE,
                        port = Constants.Cilium.OPERATOR_METRICS_PORT,
                    ),
                )
        }

    /**
     * The add-on DaemonSet: host network, privileged root, the host root and log directories
     * mounted read-only, on every node (it tolerates every taint).
     */
    fun buildDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels(NAME_LABEL, NAME)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(NAME_LABEL, NAME)
            .endMetadata()
            .withSpec(buildPodSpec())
            .endTemplate()
            .endSpec()
            .build()

    private fun buildPodSpec(): PodSpec =
        PodSpecBuilder()
            .withServiceAccountName(NAME)
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .withContainers(buildContainer())
            .withVolumes(buildVolumes())
            .build()

    private fun buildContainer(): Container =
        ContainerBuilder()
            .withName(NAME)
            .withImage(OtelManifestBuilder.IMAGE)
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).withArgs("--config=$CONFIG_PATH")
            .withPorts(containerPorts())
            .withEnv(env())
            .withVolumeMounts(volumeMounts())
            .withLivenessProbe(healthProbe(LIVENESS_INITIAL_DELAY, LIVENESS_PERIOD))
            .withReadinessProbe(healthProbe(READINESS_INITIAL_DELAY, READINESS_PERIOD))
            .build()

    /** The node's OTLP listener and the health check, on the host. */
    private fun containerPorts(): List<ContainerPort> =
        listOf(
            hostPort(Constants.K8s.OTEL_GRPC_PORT, "otlp-grpc"),
            hostPort(Constants.K8s.OTEL_HTTP_PORT, "otlp-http"),
            hostPort(Constants.K8s.OTEL_HEALTH_PORT, "health"),
        )

    private fun hostPort(
        number: Int,
        name: String,
    ) = ContainerPortBuilder()
        .withContainerPort(number)
        .withHostPort(number)
        .withProtocol("TCP")
        .withName(name)
        .build()

    private fun env(): List<EnvVar> =
        listOf(
            fieldEnv("K8S_NODE_NAME", "spec.nodeName"),
            clusterConfigEnv("CLUSTER_NAME", "cluster_name"),
            EnvVarBuilder()
                .withName("OTEL_CORE_ENDPOINT")
                .withValue("${OtelEndpoints.serviceHost(NAMESPACE)}:${Constants.K8s.OTEL_GRPC_PORT}")
                .build(),
        )

    private fun volumeMounts(): List<VolumeMount> =
        listOf(
            readOnlyMount("config", CONFIG_PATH, subPath = CONFIG_DATA_KEY),
            readOnlyMount(HOST_ROOT_VOLUME, HOST_ROOT_MOUNT_PATH),
        ) + HOST_LOG_DIRS.map { (name, path) -> readOnlyMount(name, path) }

    private fun readOnlyMount(
        name: String,
        path: String,
        subPath: String? = null,
    ): VolumeMount =
        VolumeMountBuilder()
            .withName(name)
            .withMountPath(path)
            .withSubPath(subPath)
            .withReadOnly(true)
            .build()

    private fun buildVolumes(): List<Volume> =
        listOf(
            VolumeBuilder()
                .withName("config")
                .withNewConfigMap()
                .withName(CONFIGMAP_NAME)
                .endConfigMap()
                .build(),
            hostPathVolume(HOST_ROOT_VOLUME, "/", "Directory"),
        ) + HOST_LOG_DIRS.map { (name, path) -> hostPathVolume(name, path, "DirectoryOrCreate") }

    private fun hostPathVolume(
        name: String,
        path: String,
        type: String,
    ): Volume =
        VolumeBuilder()
            .withName(name)
            .withNewHostPath()
            .withPath(path)
            .withType(type)
            .endHostPath()
            .build()

    private fun healthProbe(
        initialDelay: Int,
        period: Int,
    ) = ProbeBuilder()
        .withNewHttpGet()
        .withPath("/")
        .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
        .endHttpGet()
        .withInitialDelaySeconds(initialDelay)
        .withPeriodSeconds(period)
        .build()
}
