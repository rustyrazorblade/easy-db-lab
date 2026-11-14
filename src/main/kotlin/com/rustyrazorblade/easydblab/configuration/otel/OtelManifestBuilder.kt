package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.TelemetryRedirect
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
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Probe
import io.fabric8.kubernetes.api.model.ProbeBuilder
import io.fabric8.kubernetes.api.model.SecurityContext
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Builds the core OpenTelemetry Collector's K8s resources as typed Fabric8 objects.
 *
 * The core is a DaemonSet on the pod network: no `hostNetwork`, no `hostPort`, no privilege. It is
 * the only collector that exports to the backends, so it runs wherever pods can run. It owns the
 * OTLP and Jaeger receivers behind the `otel-collector` Service (`internalTrafficPolicy: Local`, so a
 * producer reaches its own node's core pod), every pod-discovery scrape, static kit NodePort jobs at
 * the node IP, the `kubelet_stats` receiver, `k8s_attributes`, the span connectors and every
 * exporter. Pod stdout/stderr is collected only with [PodLogSource.NodeFiles]. Everything that needs
 * the host (host metrics and files, Beyla, ebpf_exporter and Cilium scrapes, the node's OTLP
 * listener) is in the host add-on, [OtelHostCollectorManifestBuilder], which forwards to the core.
 *
 * Config uses OTel runtime env expansion (`${env:K8S_NODE_NAME}`, `${env:K8S_NODE_IP}`,
 * `${env:CLUSTER_NAME}`, `${env:TENANT}`), not `__KEY__` template substitution. `CLUSTER_NAME` and
 * `TENANT` come from the cluster-config ConfigMap; the node name and IP from the downward API.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class OtelManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val NAMESPACE = Constants.K8s.NAMESPACE
        private const val APP_LABEL = Constants.K8s.OTEL_COLLECTOR_APP_LABEL
        private const val NAME_LABEL = "app.kubernetes.io/name"
        private const val CONFIGMAP_NAME = "otel-collector-config"
        private const val CONFIG_DATA_KEY = "otel-collector-config.yaml"
        private const val CONFIG_PATH = "/etc/$CONFIG_DATA_KEY"
        private const val POD_LOGS_RESOURCE = "otel-collector-pod-logs.yaml"
        private const val POD_LOGS_PATH = "/var/log/pods"
        private const val POD_LOGS_VOLUME = "pod-logs"
        const val IMAGE = "otel/opentelemetry-collector-contrib:${Constants.OtelCollector.VERSION}"
        private const val SERVICE_ACCOUNT_NAME = "otel-collector"
        private const val CLUSTER_ROLE_NAME = "otel-collector"
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
        private val WORKLOAD_METRICS_LABEL = Constants.K8s.WORKLOAD_METRICS_LABEL
        private const val YACE_SCRAPE_INTERVAL = "5s"

        /**
         * The guard every log-derived metric's condition carries: the record's resource has no
         * `source`. Every host file receiver of the host add-on, and Fluent Bit's journald lines,
         * set `source`; Cassandra's OTLP logs do not. So a forwarded host log line is never counted
         * as a Cassandra log record.
         */
        const val NOT_A_HOST_LOG = "resource.attributes[\"source\"] == nil"

        /** In-cluster export endpoints used in local mode: Mimir's remote write and Loki's OTLP base. */
        private val LOCAL_METRICS_ENDPOINT =
            "http://${Constants.K8s.MIMIR_APP_LABEL}.default.svc.cluster.local:${Constants.K8s.MIMIR_HTTP_PORT}/api/v1/push"
        private val LOCAL_LOGS_ENDPOINT =
            "http://${Constants.K8s.LOKI_APP_LABEL}.default.svc.cluster.local:${Constants.K8s.LOKI_HTTP_PORT}/otlp"
        private val LOCAL_TEMPO_ENDPOINT =
            "tempo.default.svc.cluster.local:${Constants.K8s.TEMPO_OTLP_GRPC_PORT}"

        /** The pod-log pipeline, rendered into `service.pipelines` only with [PodLogSource.NodeFiles]. */
        private val POD_LOG_PIPELINE =
            """
            |    # Pod stdout and stderr from the kubelet's log files (only where HOST_PATH).
            |    logs/containers:
            |      receivers: [file_log/containers, file_log/clickhouse-containers]
            |      processors: [k8s_attributes, transform/add_service, resource/cluster, batch]
            |      exporters: [otlp_http/logs]
            """.trimMargin()
    }

    /**
     * Reads the metrics registry — all ConfigMaps labeled `easydblab.com/workload-metrics=true` —
     * and returns one [WorkloadScrapeConfig] per running workload.
     */
    fun listWorkloadScrapeConfigs(client: KubernetesClient): List<WorkloadScrapeConfig> {
        val configMaps =
            client
                .configMaps()
                .inAnyNamespace()
                .withLabel(WORKLOAD_METRICS_LABEL, "true")
                .list()
                .items
        log.debug { "Found ${configMaps.size} workload metrics ConfigMaps" }
        return configMaps.mapNotNull { cm ->
            val data = cm.data ?: return@mapNotNull null
            val jobName = data["job-name"] ?: return@mapNotNull null
            val port = data["port"]?.toIntOrNull() ?: return@mapNotNull null
            // Falls back to jobName for ConfigMaps written before the kit-name key was added
            WorkloadScrapeConfig(
                kitName = data["kit-name"] ?: jobName,
                jobName = jobName,
                port = port,
                path = data["path"] ?: "/metrics",
                username = data["username"] ?: "",
                podSelector = data["pod-selector"] ?: "",
            )
        }
    }

    /**
     * Builds all core collector K8s resources in apply order.
     *
     * @param capabilities What the environment provides; the `yace` job needs CloudWatch.
     * @param scrapeConfigs Dynamic per-workload scrape targets from the metrics registry.
     * @param telemetryRedirect When non-null, the collector exports to this external stack's
     *   endpoints instead of the in-cluster backends.
     * @param podLogSource Whether the core tails pod log files from the node.
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, DaemonSet, Service
     */
    fun buildAllResources(
        capabilities: NodeCapabilities,
        scrapeConfigs: List<WorkloadScrapeConfig> = emptyList(),
        telemetryRedirect: TelemetryRedirect? = null,
        podLogSource: PodLogSource = PodLogSource.NodeFiles,
    ): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildConfigMap(capabilities, scrapeConfigs, telemetryRedirect, podLogSource),
            buildDaemonSet(podLogSource),
            buildService(),
        )

    /**
     * Builds the ServiceAccount for OTel Collector pods.
     * Required by the k8s_attributes processor and the kubelet_stats receiver for K8s API access.
     */
    fun buildServiceAccount() =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(SERVICE_ACCOUNT_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, APP_LABEL)
            .endMetadata()
            .build()

    /**
     * Builds the ClusterRole: read access to pods and nodes for k8s_attributes and pod discovery,
     * and `nodes/stats` for the kubelet_stats receiver.
     */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels(NAME_LABEL, APP_LABEL)
            .endMetadata()
            .addNewRule()
            .withApiGroups("")
            .withResources("pods", "nodes")
            .withVerbs("get", "watch", "list")
            .endRule()
            .addNewRule()
            .withApiGroups("")
            .withResources("nodes/stats")
            .withVerbs("get")
            .endRule()
            .build()

    /**
     * Builds the ClusterRoleBinding linking the ServiceAccount to the ClusterRole.
     */
    fun buildClusterRoleBinding() =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels(NAME_LABEL, APP_LABEL)
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
     * Builds the core collector ConfigMap, merging the infrastructure scrape jobs with the dynamic
     * per-workload scrape jobs from the metrics registry.
     *
     * @param capabilities What the environment provides; the `yace` job needs CloudWatch.
     * @param scrapeConfigs Workload scrape targets to inject (from [listWorkloadScrapeConfigs])
     * @param telemetryRedirect When non-null, the three export endpoints resolve to this external
     *   stack; when null they resolve to the in-cluster service addresses (local mode, unchanged).
     * @param podLogSource [PodLogSource.NodeFiles] renders the pod-log receivers and pipeline;
     *   [PodLogSource.None] renders neither.
     */
    fun buildConfigMap(
        capabilities: NodeCapabilities,
        scrapeConfigs: List<WorkloadScrapeConfig> = emptyList(),
        telemetryRedirect: TelemetryRedirect? = null,
        podLogSource: PodLogSource = PodLogSource.NodeFiles,
    ) = ConfigMapBuilder()
        .withNewMetadata()
        .withName(CONFIGMAP_NAME)
        .withNamespace(NAMESPACE)
        .addToLabels(NAME_LABEL, APP_LABEL)
        .endMetadata()
        .addToData(CONFIG_DATA_KEY, renderConfig(capabilities, scrapeConfigs, telemetryRedirect, podLogSource))
        .build()

    private fun renderConfig(
        capabilities: NodeCapabilities,
        scrapeConfigs: List<WorkloadScrapeConfig>,
        telemetryRedirect: TelemetryRedirect?,
        podLogSource: PodLogSource,
    ): String {
        val (podLogReceivers, podLogPipeline) =
            when (podLogSource) {
                PodLogSource.NodeFiles -> resource(POD_LOGS_RESOURCE).trimEnd() to POD_LOG_PIPELINE
                PodLogSource.None -> "" to ""
            }
        return templateService
            .fromResource(OtelManifestBuilder::class.java, CONFIG_DATA_KEY)
            .substitute(
                mapOf(
                    "KIT_SCRAPE_JOBS" to PrometheusScrapeJob.render(buildKitScrapeJobs(scrapeConfigs)),
                    "INFRA_SCRAPE_JOBS" to
                        PrometheusScrapeJob.render(buildLocalBackendScrapeJobs(telemetryRedirect) + buildInfraScrapeJobs(capabilities)),
                    "POD_LOG_RECEIVERS" to podLogReceivers,
                    "POD_LOG_PIPELINE" to podLogPipeline,
                    "METRICS_ENDPOINT" to (telemetryRedirect?.metrics ?: LOCAL_METRICS_ENDPOINT),
                    "LOGS_ENDPOINT" to (telemetryRedirect?.logs ?: LOCAL_LOGS_ENDPOINT),
                    "TEMPO_ENDPOINT" to (telemetryRedirect?.traces ?: LOCAL_TEMPO_ENDPOINT),
                ),
            )
    }

    /** A config fragment, read verbatim: it carries no placeholder of its own. */
    private fun resource(name: String): String =
        checkNotNull(OtelManifestBuilder::class.java.getResource(name)) { "missing collector resource $name" }
            .readText()
            .lines()
            .filterNot { it.startsWith("#") }
            .joinToString("\n")

    /** One scrape job per running kit workload: pod discovery when it names pods, else its NodePort. */
    fun buildKitScrapeJobs(configs: List<WorkloadScrapeConfig>): List<PrometheusScrapeJob> =
        configs.map { config ->
            if (config.podSelector.isNotBlank()) buildPodScrapeJob(config) else buildStaticScrapeJob(config)
        }

    /**
     * The infrastructure scrape jobs that carry Prometheus meta-labels in their relabel rules and
     * so cannot live in the YAML template (a bare `__x__` there is a template placeholder):
     * kube-state-metrics on every cluster, and YACE where the environment has CloudWatch. The CNI
     * jobs scrape host-network endpoints and are the host add-on's
     * ([OtelHostCollectorManifestBuilder.buildCniScrapeJobs]).
     */
    fun buildInfraScrapeJobs(capabilities: NodeCapabilities): List<PrometheusScrapeJob> =
        listOf(buildKubeStateMetricsScrapeJob()) + buildCloudWatchScrapeJobs(capabilities)

    /**
     * The scrape jobs for the local telemetry backends' own metrics: Mimir, Loki, Tempo and the
     * Pyroscope server, so rejected writes, dropped samples and failed flushes are queryable.
     * Each is one pod on the control node, while the collector runs on every node, so a static
     * target would leave every other node's collector reporting the job down for the life of the
     * cluster. Like kube-state-metrics, each is found by node-local pod discovery (label
     * `app.kubernetes.io/name`, its metrics port), so only the collector on the pod's node scrapes
     * it; `instance` is the pod name. A redirect cluster deploys neither, so it renders none of them.
     */
    fun buildLocalBackendScrapeJobs(telemetryRedirect: TelemetryRedirect?): List<PrometheusScrapeJob> =
        when (telemetryRedirect) {
            null ->
                listOf(
                    Constants.K8s.MIMIR_APP_LABEL to Constants.K8s.MIMIR_HTTP_PORT,
                    Constants.K8s.LOKI_APP_LABEL to Constants.K8s.LOKI_HTTP_PORT,
                    Constants.K8s.TEMPO_APP_LABEL to Constants.K8s.TEMPO_PORT,
                    Constants.K8s.PYROSCOPE_APP_LABEL to Constants.K8s.PYROSCOPE_PORT,
                ).map { (name, port) -> NodeScrapeJobs.nodeLocalPodJob(name, NAMESPACE, NAME_LABEL, name, port) }
            else -> emptyList()
        }

    /**
     * kube-state-metrics runs as ONE pod on the control node, in the pod network. Every collector
     * in the DaemonSet could reach it through its ClusterIP Service, but then every node would
     * scrape the same series under its own `instance`. Pod discovery filtered to the collector's
     * own node means exactly one collector scrapes it; `instance` is the pod name.
     */
    private fun buildKubeStateMetricsScrapeJob() =
        NodeScrapeJobs.nodeLocalPodJob(
            jobName = Constants.KubeStateMetrics.NAME,
            namespace = NAMESPACE,
            podLabel = NAME_LABEL,
            podLabelValue = Constants.KubeStateMetrics.NAME,
            port = Constants.KubeStateMetrics.METRICS_PORT,
        )

    /**
     * The scrape jobs for workloads that exist only where the environment has CloudWatch: YACE.
     * YACE is one pod on the control node (hostNetwork, so its pod IP is the node IP), so it is
     * found by node-local pod discovery: only the collector on its node scrapes it, and no other
     * node reports `up{job="yace"} = 0`. `instance` keeps its old value, `<host>:<port>`.
     */
    private fun buildCloudWatchScrapeJobs(capabilities: NodeCapabilities): List<PrometheusScrapeJob> =
        if (NodeCapability.CLOUDWATCH in capabilities) {
            listOf(
                NodeScrapeJobs.nodeLocalPodJob(
                    jobName = "yace",
                    namespace = NAMESPACE,
                    podLabel = NAME_LABEL,
                    podLabelValue = Constants.Yace.DEPLOYMENT_NAME,
                    port = Constants.Yace.PORT,
                    scrapeInterval = YACE_SCRAPE_INTERVAL,
                    scrapeTimeout = YACE_SCRAPE_INTERVAL,
                    instance = "${NodeScrapeJobs.NODE_NAME}:${Constants.Yace.PORT}",
                ),
            )
        } else {
            emptyList()
        }

    /**
     * Builds a static NodePort scrape job. The core is on the pod network, where `localhost` is the
     * collector pod itself, so every collector scrapes its own node's IP on the port; `instance` is
     * stamped with the node's name.
     */
    private fun buildStaticScrapeJob(config: WorkloadScrapeConfig) =
        PrometheusScrapeJob(
            // Use kitName-jobName compound key to ensure uniqueness in all cases:
            // - Same kit with multiple scrape targets (e.g. kafka-exporter and kafka-jmx)
            // - Multiple kit instances sharing the same jobName (e.g. postgres-duckdb and postgres-postgis)
            jobName = "${config.kitName}-${config.jobName}",
            scrapeInterval = NodeScrapeJobs.SCRAPE_INTERVAL,
            staticConfigs = listOf(PrometheusStaticConfig(targets = listOf("${NodeScrapeJobs.NODE_IP}:${config.port}"))),
            metricsPath = config.path,
            relabelConfigs =
                listOf(
                    // Preserve the logical job name from kit.yaml as the `job` label so
                    // Prometheus queries like job="postgres" continue to work across instances.
                    PrometheusRelabelConfig(targetLabel = "job", replacement = config.jobName),
                    PrometheusRelabelConfig(targetLabel = "instance", replacement = "${NodeScrapeJobs.NODE_NAME}:${config.port}"),
                    NodeScrapeJobs.clusterRelabel,
                ),
            basicAuth = basicAuth(config),
        )

    /**
     * Builds a pod service-discovery scrape job. Each pod is scraped directly on its own IP, so
     * `instance` carries the pod name (e.g. `tidb-tikv-0`) — the stable per-store identity a shared
     * NodePort cannot provide. Discovery is filtered to pods matching [WorkloadScrapeConfig.podSelector]
     * that are co-located on the scraping collector's node. Node-local filtering is essential:
     * without it, every collector in the DaemonSet would scrape every pod, producing duplicate
     * series for the same `instance`.
     */
    private fun buildPodScrapeJob(config: WorkloadScrapeConfig): PrometheusScrapeJob {
        val selectorRelabels =
            config.podSelector
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { pair ->
                    val (key, value) = pair.split("=", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                    NodeScrapeJobs.keep(NodeScrapeJobs.podLabelMetaKey(key), value)
                }
        return PrometheusScrapeJob(
            jobName = "${config.kitName}-${config.jobName}",
            scrapeInterval = NodeScrapeJobs.SCRAPE_INTERVAL,
            kubernetesSdConfigs = listOf(NodeScrapeJobs.podDiscovery(NAMESPACE)),
            metricsPath = config.path,
            relabelConfigs =
                selectorRelabels +
                    listOf(
                        // Only scrape pods on this collector's node — one TiKV pod per db node.
                        NodeScrapeJobs.onThisNode(),
                        // Target the pod IP directly on its container metrics port (not the NodePort).
                        NodeScrapeJobs.podAddress(config.port),
                        // Per-pod identity: instance = pod name (the store), not the NodePort.
                        PrometheusRelabelConfig(sourceLabels = listOf("__meta_kubernetes_pod_name"), targetLabel = "instance"),
                        PrometheusRelabelConfig(targetLabel = "job", replacement = config.jobName),
                        NodeScrapeJobs.clusterRelabel,
                    ),
            basicAuth = basicAuth(config),
        )
    }

    private fun basicAuth(config: WorkloadScrapeConfig) =
        if (config.username.isNotBlank()) PrometheusBasicAuth(username = config.username) else null

    /**
     * Builds the core collector DaemonSet: on the pod network, unprivileged, on every node.
     *
     * It tolerates every taint, so the control node and any tainted node run a core pod, which the
     * Service's `internalTrafficPolicy: Local` relies on. It rolls one node at a time with a surge
     * pod (`maxSurge: 1, maxUnavailable: 0`), so a node is never left without a core pod. With
     * [PodLogSource.NodeFiles] it mounts `/var/log/pods` read-only and runs as root to read the
     * kubelet's files; with [PodLogSource.None] it mounts no hostPath and runs restricted.
     */
    fun buildDaemonSet(podLogSource: PodLogSource = PodLogSource.NodeFiles) =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels(NAME_LABEL, APP_LABEL)
            .endSelector()
            .withNewUpdateStrategy()
            .withType("RollingUpdate")
            .withNewRollingUpdate()
            .withMaxSurge(IntOrString(1))
            .withMaxUnavailable(IntOrString(0))
            .endRollingUpdate()
            .endUpdateStrategy()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(NAME_LABEL, APP_LABEL)
            .endMetadata()
            .withSpec(buildPodSpec(podLogSource))
            .endTemplate()
            .endSpec()
            .build()

    private fun buildPodSpec(podLogSource: PodLogSource): PodSpec =
        PodSpecBuilder()
            .withServiceAccountName(SERVICE_ACCOUNT_NAME)
            .withHostNetwork(false)
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .withContainers(buildContainer(podLogSource))
            .withVolumes(buildVolumes(podLogSource))
            .build()

    private fun buildContainer(podLogSource: PodLogSource): Container =
        ContainerBuilder()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withSecurityContext(securityContext(podLogSource))
            .withArgs("--config=$CONFIG_PATH")
            .withPorts(containerPorts())
            .withEnv(env())
            .withVolumeMounts(volumeMounts(podLogSource))
            .withLivenessProbe(healthProbe(LIVENESS_INITIAL_DELAY, LIVENESS_PERIOD))
            .withReadinessProbe(healthProbe(READINESS_INITIAL_DELAY, READINESS_PERIOD))
            .build()

    /**
     * Never privileged, never able to escalate, and with a read-only root filesystem: the config
     * writes nothing to disk (no file_storage, no persistent queue), so nothing needs a writable path.
     *
     * [PodLogSource.NodeFiles] runs as **UID 0 with `DAC_READ_SEARCH`** and every other capability
     * dropped. The kubelet's pod log files are root-owned, mode 0640, and the override lets the
     * collector read them (and any file it can reach) regardless of permissions. Its only host
     * mount is `/var/log/pods`, read-only, so the override reaches nothing else on the node.
     * [PodLogSource.None] is a restricted, non-root context.
     */
    private fun securityContext(podLogSource: PodLogSource): SecurityContext {
        val builder =
            SecurityContextBuilder()
                .withPrivileged(false)
                .withAllowPrivilegeEscalation(false)
                .withReadOnlyRootFilesystem(true)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
        return when (podLogSource) {
            PodLogSource.NodeFiles ->
                builder
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .withNewCapabilities()
                    .withDrop("ALL")
                    .withAdd("DAC_READ_SEARCH")
                    .endCapabilities()
                    .build()
            PodLogSource.None ->
                builder
                    .withRunAsNonRoot(true)
                    .withRunAsUser(Constants.OtelCollector.NON_ROOT_UID)
                    .withRunAsGroup(Constants.OtelCollector.NON_ROOT_UID)
                    .withNewCapabilities()
                    .withDrop("ALL")
                    .endCapabilities()
                    .build()
        }
    }

    /** Container ports only: the core is reached through its Service, never a host port. */
    private fun containerPorts(): List<ContainerPort> =
        listOf(
            port(Constants.K8s.OTEL_GRPC_PORT, "TCP", "otlp-grpc"),
            port(Constants.K8s.OTEL_HTTP_PORT, "TCP", "otlp-http"),
            port(Constants.K8s.OTEL_HEALTH_PORT, "TCP", "health"),
            port(Constants.K8s.JAEGER_THRIFT_COMPACT_PORT, "UDP", "jaeger-compact"),
        )

    private fun port(
        number: Int,
        protocol: String,
        name: String,
    ) = ContainerPortBuilder()
        .withContainerPort(number)
        .withProtocol(protocol)
        .withName(name)
        .build()

    private fun env(): List<EnvVar> =
        listOf(
            fieldEnv("K8S_NODE_NAME", "spec.nodeName"),
            fieldEnv("K8S_NODE_IP", "status.hostIP"),
            clusterConfigEnv("CLUSTER_NAME", "cluster_name"),
            clusterConfigEnv("TENANT", "tenant"),
        )

    private fun volumeMounts(podLogSource: PodLogSource): List<VolumeMount> =
        listOfNotNull(
            VolumeMountBuilder()
                .withName("config")
                .withMountPath(CONFIG_PATH)
                .withSubPath(CONFIG_DATA_KEY)
                .withReadOnly(true)
                .build(),
            when (podLogSource) {
                PodLogSource.NodeFiles ->
                    VolumeMountBuilder()
                        .withName(POD_LOGS_VOLUME)
                        .withMountPath(POD_LOGS_PATH)
                        .withReadOnly(true)
                        .build()
                PodLogSource.None -> null
            },
        )

    /**
     * The config, and with [PodLogSource.NodeFiles] the kubelet's pod log directory. The kubelet
     * resolves `/var/log/pods` itself, so a node whose pod logs live on NVMe through a symlink
     * still mounts the real directory.
     */
    private fun buildVolumes(podLogSource: PodLogSource): List<Volume> =
        listOfNotNull(
            VolumeBuilder()
                .withName("config")
                .withNewConfigMap()
                .withName(CONFIGMAP_NAME)
                .endConfigMap()
                .build(),
            when (podLogSource) {
                PodLogSource.NodeFiles ->
                    VolumeBuilder()
                        .withName(POD_LOGS_VOLUME)
                        .withNewHostPath()
                        .withPath(POD_LOGS_PATH)
                        .withType("Directory")
                        .endHostPath()
                        .build()
                PodLogSource.None -> null
            },
        )

    private fun healthProbe(
        initialDelay: Int,
        period: Int,
    ): Probe =
        ProbeBuilder()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(initialDelay)
            .withPeriodSeconds(period)
            .build()

    /**
     * Builds the `otel-collector` ClusterIP Service, the one OTLP endpoint for every in-cluster
     * producer (`otel-collector.<ns>.svc`, see [OtelEndpoints]): OTLP gRPC 4317, OTLP HTTP 4318 and
     * Jaeger compact 6831/UDP. `internalTrafficPolicy: Local` keeps each producer on its own node's
     * core pod. It selects `app.kubernetes.io/name=otel-collector` only, which the host add-on's pods
     * (`otel-collector-host`) do not carry.
     */
    fun buildService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels(NAME_LABEL, APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .withInternalTrafficPolicy("Local")
            .addToSelector(NAME_LABEL, APP_LABEL)
            .addNewPort()
            .withName("otlp-grpc")
            .withPort(Constants.K8s.OTEL_GRPC_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("otlp-http")
            .withPort(Constants.K8s.OTEL_HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("jaeger-compact")
            .withPort(Constants.K8s.JAEGER_THRIFT_COMPACT_PORT)
            .withProtocol("UDP")
            .endPort()
            .endSpec()
            .build()
}

/** An env var from the downward API's [fieldPath]. */
internal fun fieldEnv(
    name: String,
    fieldPath: String,
): EnvVar =
    EnvVarBuilder()
        .withName(name)
        .withNewValueFrom()
        .withNewFieldRef()
        .withFieldPath(fieldPath)
        .endFieldRef()
        .endValueFrom()
        .build()
