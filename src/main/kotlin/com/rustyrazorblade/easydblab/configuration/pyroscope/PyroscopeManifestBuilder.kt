package com.rustyrazorblade.easydblab.configuration.pyroscope

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ObservabilityHosting
import com.rustyrazorblade.easydblab.configuration.ServiceDns
import com.rustyrazorblade.easydblab.configuration.ServicePublishing
import com.rustyrazorblade.easydblab.configuration.StateVolume
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
import com.rustyrazorblade.easydblab.configuration.hostNetworked
import com.rustyrazorblade.easydblab.configuration.store.StoreBlockRenderer
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder

/**
 * Builds all Pyroscope K8s resources as typed Fabric8 objects.
 *
 * Creates the Pyroscope server (Deployment + Service + ConfigMap) for continuous profiling storage,
 * and the Grafana Alloy eBPF agent (DaemonSet + ConfigMap) that collects CPU profiles from all nodes.
 *
 * @property templateService Used for loading config files from classpath resources
 * @property storeBlocks Places the object-store block of the cluster's store ([TemplateService.storeBackend]) into the config.
 */
class PyroscopeManifestBuilder(
    private val templateService: TemplateService,
    private val storeBlocks: StoreBlockRenderer = StoreBlockRenderer.of(templateService::storeBackend),
) {
    companion object {
        // Pyroscope server constants
        private const val SERVER_APP_LABEL = Constants.K8s.PYROSCOPE_APP_LABEL
        private const val SERVER_CONFIGMAP_NAME = "pyroscope-config"
        const val SERVER_IMAGE = "grafana/pyroscope:2.3.1"
        const val SERVER_PORT = 4040

        /** The ConfigMap key of the server's config file. */
        const val SERVER_CONFIG_KEY = "config.yaml"

        /** The ConfigMap key of the ingest front's HAProxy config; the Service targets the front instead of [SERVER_PORT]. */
        const val FRONT_CONFIG_KEY = "ingest-front.cfg"

        /** The server's data directory on the control node: the v2 metastore index and Raft state. */
        const val DATA_HOST_PATH = "/mnt/db1/pyroscope"

        private val STATE =
            StateVolume(SERVER_APP_LABEL, "data", DATA_HOST_PATH, owner = Constants.PyroscopeServer.UID)

        /** Where [DATA_HOST_PATH] is mounted in the server container; config.yaml names paths under it. */
        private const val DATA_MOUNT_PATH = "/data"

        // eBPF agent constants
        private const val EBPF_APP_LABEL = "pyroscope-ebpf"
        private const val EBPF_CONFIGMAP_NAME = "pyroscope-ebpf-config"
        const val ALLOY_IMAGE = "grafana/alloy:v1.20.0"
        private const val ALLOY_PORT = 12345

        // RBAC constants — the Alloy `discovery.kubernetes` component lists pods on the
        // node so eBPF samples can be attributed to a pod/container/service_name.
        private const val EBPF_SERVICE_ACCOUNT_NAME = "pyroscope-ebpf"
        private const val EBPF_CLUSTER_ROLE_NAME = "pyroscope-ebpf"
    }

    /**
     * Builds all Pyroscope K8s resources in apply order (server + eBPF agent).
     *
     * @param telemetryRedirect When non-null, the eBPF agent writes profiles to this external
     *   stack instead of the in-cluster server. Passing it here keeps [buildAllResources] valid in
     *   redirect mode even though a redirect cluster normally deploys only [buildAgentResources].
     * @param hosting where the server is placed and keeps its metastore.
     * @return List of: server ConfigMap, server Service, server Deployment,
     *   eBPF ServiceAccount, eBPF ClusterRole, eBPF ClusterRoleBinding,
     *   eBPF ConfigMap, eBPF DaemonSet
     */
    fun buildAllResources(
        scope: InstallScope,
        telemetryRedirect: TelemetryRedirect? = null,
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
    ): List<HasMetadata> = buildServerResources(scope, hosting) + buildAgentResources(scope, telemetryRedirect)

    /**
     * Builds the Pyroscope server resources (ConfigMap, Service, Deployment).
     *
     * A redirect cluster ships profiles to an external Pyroscope and never stands up this server,
     * so the observability stack deploys these only in local mode.
     */
    fun buildServerResources(
        scope: InstallScope,
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
    ): List<HasMetadata> =
        listOf(buildServerConfigMap(scope), buildServerService(scope, hosting.publishing)) +
            hosting.host(buildServerPodTemplate(scope), STATE, isBackend = true)

    /** The Pyroscope server Deployment as [hosting] runs it. */
    fun buildServerDeployment(
        scope: InstallScope,
        hosting: ObservabilityHosting = ObservabilityHosting.AWS,
    ): Deployment = hosting.host(buildServerPodTemplate(scope), STATE, isBackend = true).filterIsInstance<Deployment>().single()

    /**
     * Builds the Grafana Alloy eBPF agent resources (RBAC, ConfigMap, DaemonSet).
     *
     * The agent runs on every node in both modes; its write URL is the only thing that changes
     * under redirect, injected as the DaemonSet's `PYROSCOPE_WRITE_URL` env var by
     * [buildEbpfDaemonSet].
     */
    fun buildAgentResources(
        scope: InstallScope,
        telemetryRedirect: TelemetryRedirect? = null,
    ): List<HasMetadata> =
        listOf(
            buildEbpfServiceAccount(scope),
            buildEbpfClusterRole(scope),
            buildEbpfClusterRoleBinding(scope),
            buildEbpfConfigMap(scope),
            buildEbpfDaemonSet(scope, telemetryRedirect),
        )

    /**
     * Builds the ServiceAccount used by the Alloy eBPF DaemonSet.
     *
     * Required so the `discovery.kubernetes` component in config.alloy can list pods
     * to attribute eBPF samples to the owning pod/container/service_name.
     */
    fun buildEbpfServiceAccount(scope: InstallScope) =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(EBPF_SERVICE_ACCOUNT_NAME)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .build()

    /**
     * Builds the ClusterRole granting the eBPF agent read access to pods.
     *
     * `discovery.kubernetes` with `role = "pod"` needs list/watch on pods cluster-wide
     * (scoped per-node at query time via a field selector) to resolve container id → pod.
     */
    fun buildEbpfClusterRole(scope: InstallScope) =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(scope.clusterScoped(EBPF_CLUSTER_ROLE_NAME))
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .addNewRule()
            .withApiGroups("")
            .withResources("pods")
            .withVerbs("get", "watch", "list")
            .endRule()
            .build()

    /**
     * Builds the ClusterRoleBinding linking the eBPF ServiceAccount to its ClusterRole.
     */
    fun buildEbpfClusterRoleBinding(scope: InstallScope) =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(scope.clusterScoped(EBPF_CLUSTER_ROLE_NAME))
            .addToLabels("app.kubernetes.io/name", EBPF_APP_LABEL)
            .endMetadata()
            .withNewRoleRef()
            .withApiGroup("rbac.authorization.k8s.io")
            .withKind("ClusterRole")
            .withName(scope.clusterScoped(EBPF_CLUSTER_ROLE_NAME))
            .endRoleRef()
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName(EBPF_SERVICE_ACCOUNT_NAME)
            .withNamespace(scope.namespace.value)
            .endSubject()
            .build()

    /**
     * Builds the Pyroscope server ConfigMap: the server's config.yaml and the ingest front's
     * HAProxy configuration ([FRONT_CONFIG_KEY]).
     */
    fun buildServerConfigMap(scope: InstallScope) =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(SERVER_CONFIGMAP_NAME)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .addToData(
                "config.yaml",
                templateService
                    .fromString(storeBlocks.renderResource(PyroscopeManifestBuilder::class.java, "config.yaml"))
                    .substitute(),
            ).addToData(FRONT_CONFIG_KEY, buildFrontConfig())
            .build()

    /**
     * The ingest front's HAProxy configuration: it listens on [Constants.PyroscopeServer.FRONT_PORT]
     * and forwards to the server on the pod's loopback, answering 503 for the 422 "service is
     * unavailable" the server sends while it shuts down.
     */
    fun buildFrontConfig(): String =
        templateService
            .fromResource(PyroscopeManifestBuilder::class.java, FRONT_CONFIG_KEY)
            .substitute(
                mapOf(
                    "FRONT_PORT" to Constants.PyroscopeServer.FRONT_PORT.toString(),
                    "SERVER_PORT" to SERVER_PORT.toString(),
                ),
            )

    /**
     * The Pyroscope Service on 4040, sent to the ingest front; [publishing] puts it on
     * [Constants.K8s.PYROSCOPE_NODE_PORT].
     */
    fun buildServerService(
        scope: InstallScope,
        publishing: ServicePublishing = ServicePublishing.ClusterIp,
    ): Service =
        publishing.publish(
            clusterIpService(scope),
            mapOf(
                "http" to Constants.K8s.PYROSCOPE_NODE_PORT,
            ),
        )

    private fun clusterIpService(scope: InstallScope): Service =
        ServiceBuilder()
            .withNewMetadata()
            .withName(SERVER_APP_LABEL)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addNewPort()
            .withName("http")
            .withPort(SERVER_PORT)
            .withNewTargetPort(Constants.PyroscopeServer.FRONT_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Pyroscope server Deployment before it is hosted. [ObservabilityHosting] places it
     * and sets where its metastore volume lives; callers outside the pod network
     * reach it through its Service's NodePort on nodes easy-db-lab owns. Profiles are stored in S3 under
     * `pyroscope` in the account bucket (configured at build time via TemplateService);
     * the v2 metastore index lives on the node at [DATA_HOST_PATH], so a pod restart keeps it.
     *
     * A startup probe holds liveness off until `/ready` first passes, because Pyroscope 2 takes
     * tens of seconds to start; see [Constants.PyroscopeProbes].
     */
    private fun buildServerPodTemplate(scope: InstallScope): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(SERVER_APP_LABEL)
            .withNamespace(scope.namespace.value)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSelector()
            .withNewStrategy()
            .withType("Recreate")
            .withRollingUpdate(null)
            .endStrategy()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withContainers(buildServerContainer(), buildFrontContainer())
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(SERVER_CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName(STATE.volumeName)
            .endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    private fun buildServerContainer(): Container =
        ContainerBuilder()
            .withName(SERVER_APP_LABEL)
            .withImage(SERVER_IMAGE)
            .withArgs("-config.file=${Constants.PyroscopeServer.CONFIG_MOUNT_PATH}/$SERVER_CONFIG_KEY")
            .addNewPort()
            .withContainerPort(SERVER_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewVolumeMount()
            .withName("config")
            .withMountPath(Constants.PyroscopeServer.CONFIG_MOUNT_PATH)
            .endVolumeMount()
            .addNewVolumeMount()
            .withName("data")
            .withMountPath(DATA_MOUNT_PATH)
            .endVolumeMount()
            .withNewStartupProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(SERVER_PORT)
            .endHttpGet()
            .withPeriodSeconds(Constants.PyroscopeProbes.STARTUP_PERIOD_SECONDS)
            .withFailureThreshold(Constants.PyroscopeProbes.STARTUP_FAILURE_THRESHOLD)
            .endStartupProbe()
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(SERVER_PORT)
            .endHttpGet()
            .withPeriodSeconds(Constants.PyroscopeProbes.LIVENESS_PERIOD_SECONDS)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(SERVER_PORT)
            .endHttpGet()
            .withPeriodSeconds(Constants.PyroscopeProbes.READINESS_PERIOD_SECONDS)
            .endReadinessProbe()
            .build()

    /**
     * The ingest front: HAProxy on [Constants.PyroscopeServer.FRONT_PORT], reading [FRONT_CONFIG_KEY]
     * from the server's ConfigMap. While the server container is down and the pod is up, the
     * front's connection to it is refused and it answers 503, which every client retries too.
     *
     * Its readiness probe asks the front for `/ready`, which it passes through to the server, so a
     * front that crashed or never bound its port takes the pod out of the Service instead of
     * leaving pushes to a dead port. Its liveness probe is a TCP connect, so the kubelet restarts
     * a front that stopped listening but never one whose server is only starting or stopping.
     */
    private fun buildFrontContainer(): Container =
        ContainerBuilder()
            .withName(Constants.PyroscopeServer.FRONT_NAME)
            .withImage(Constants.PyroscopeServer.FRONT_IMAGE)
            .withArgs("-f", "${Constants.PyroscopeServer.CONFIG_MOUNT_PATH}/$FRONT_CONFIG_KEY")
            .addNewPort()
            .withContainerPort(Constants.PyroscopeServer.FRONT_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewVolumeMount()
            .withName("config")
            .withMountPath(Constants.PyroscopeServer.CONFIG_MOUNT_PATH)
            .withReadOnly(true)
            .endVolumeMount()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(Constants.PyroscopeServer.FRONT_PORT)
            .endHttpGet()
            .withPeriodSeconds(Constants.PyroscopeServer.FRONT_READINESS_PERIOD_SECONDS)
            .endReadinessProbe()
            .withNewLivenessProbe()
            .withNewTcpSocket()
            .withNewPort(Constants.PyroscopeServer.FRONT_PORT)
            .endTcpSocket()
            .withPeriodSeconds(Constants.PyroscopeServer.FRONT_LIVENESS_PERIOD_SECONDS)
            .endLivenessProbe()
            .build()

    /**
     * Builds the eBPF agent ConfigMap containing config.alloy.
     *
     * config.alloy carries no `__KEY__` placeholders: the cluster name and the Pyroscope write URL
     * are read at runtime from the `CLUSTER_NAME` and `PYROSCOPE_WRITE_URL` env vars set on the
     * DaemonSet ([buildEbpfDaemonSet]). This is deliberate — Alloy's own `__meta_kubernetes_*`
     * labels collide with the `__`/`__` template delimiters, so a build-time substitution of those
     * two values silently left them unresolved. `substitute()` runs with no resolvable variables
     * and leaves the Alloy meta labels untouched.
     */
    fun buildEbpfConfigMap(scope: InstallScope) =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(EBPF_CONFIGMAP_NAME)
            .withNamespace(scope.namespace.value)
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
     * The `cluster` label every eBPF profile carries — the origin-identity mechanism that keeps two
     * redirected clusters apart on one shared stack. Fail fast rather than let it degrade to an empty
     * label: an unlabeled profile stream defeats the whole point of redirect. Never blank for a real
     * cluster, since it is `"$name-$clusterId"`.
     */
    private fun resolveClusterName(): String {
        val clusterName = templateService.buildContextVariables()["CLUSTER_NAME"].orEmpty()
        check(clusterName.isNotBlank()) {
            "CLUSTER_NAME is blank; eBPF profiles would ship with no cluster label, making a " +
                "redirected cluster indistinguishable from another on the shared observability stack."
        }
        return clusterName
    }

    /**
     * Builds the eBPF agent DaemonSet.
     *
     * Runs Grafana Alloy with pyroscope.ebpf on all nodes (tolerates everything).
     * Requires hostPID and privileged mode for eBPF access. Uses the
     * [EBPF_SERVICE_ACCOUNT_NAME] ServiceAccount so the `discovery.kubernetes`
     * component can list pods and attribute samples to pod/container/service_name.
     *
     * The `CLUSTER_NAME`, `PYROSCOPE_WRITE_URL` and `TENANT` env vars feed the matching
     * `sys.env(...)` reads in config.alloy. The write URL is the Pyroscope Service (the agent runs with
     * host networking and `ClusterFirstWithHostNet`, so it resolves Service DNS on any node), or the
     * external Pyroscope under redirect; the tenant, read from the cluster-config ConfigMap, is sent
     * either way. The agent also runs the node-local profiles relay on loopback
     * ([Constants.K8s.ALLOY_RELAY_PORT]) for host processes that cannot resolve cluster DNS.
     *
     * @param telemetryRedirect when non-null, profiles are written to [TelemetryRedirect.profiles].
     */
    fun buildEbpfDaemonSet(
        scope: InstallScope,
        telemetryRedirect: TelemetryRedirect? = null,
    ): DaemonSet =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(EBPF_APP_LABEL)
            .withNamespace(scope.namespace.value)
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
            .withServiceAccountName(EBPF_SERVICE_ACCOUNT_NAME)
            .hostNetworked()
            .withHostPID(true)
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addToContainers(buildAlloyContainer(scope, telemetryRedirect))
            .addAllToVolumes(buildAlloyVolumes())
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /** The privileged Alloy container: eBPF profiling plus the loopback profiles relay. */
    private fun buildAlloyContainer(
        scope: InstallScope,
        telemetryRedirect: TelemetryRedirect?,
    ): Container =
        ContainerBuilder()
            .withName("alloy")
            .withImage(ALLOY_IMAGE)
            .withArgs(
                "run",
                "--server.http.listen-addr=0.0.0.0:$ALLOY_PORT",
                "/etc/alloy/config.alloy",
            ).withEnv(buildAlloyEnv(scope, telemetryRedirect))
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
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
            ).build()

    /** The env vars config.alloy reads with `sys.env(...)`, plus the node name Alloy reports as. */
    private fun buildAlloyEnv(
        scope: InstallScope,
        telemetryRedirect: TelemetryRedirect?,
    ): List<EnvVar> =
        listOf(
            EnvVarBuilder()
                .withName("HOSTNAME")
                .withNewValueFrom()
                .withNewFieldRef()
                .withFieldPath("spec.nodeName")
                .endFieldRef()
                .endValueFrom()
                .build(),
            EnvVarBuilder().withName("CLUSTER_NAME").withValue(resolveClusterName()).build(),
            EnvVarBuilder()
                .withName(
                    "PYROSCOPE_WRITE_URL",
                ).withValue(pyroscopeIngestBaseUrl(telemetryRedirect, ServiceDns(scope.namespace)))
                .build(),
            EnvVarBuilder()
                .withName("TENANT")
                .withNewValueFrom()
                .withNewConfigMapKeyRef()
                .withName("cluster-config")
                .withKey("tenant")
                .endConfigMapKeyRef()
                .endValueFrom()
                .build(),
        )

    /** The Alloy config ConfigMap and the symbol cache scratch directory. */
    private fun buildAlloyVolumes(): List<Volume> =
        listOf(
            VolumeBuilder()
                .withName("config")
                .withConfigMap(
                    ConfigMapVolumeSourceBuilder()
                        .withName(EBPF_CONFIGMAP_NAME)
                        .build(),
                ).build(),
            VolumeBuilder()
                .withName("sym-cache")
                .withNewEmptyDir()
                .endEmptyDir()
                .build(),
        )
}
