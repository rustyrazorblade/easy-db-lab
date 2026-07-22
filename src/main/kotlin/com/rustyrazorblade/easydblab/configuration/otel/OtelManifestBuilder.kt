package com.rustyrazorblade.easydblab.configuration.otel

import com.charleskorn.kaml.Yaml
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.ListSerializer

/**
 * Builds all OpenTelemetry Collector K8s resources as typed Fabric8 objects.
 *
 * Creates a DaemonSet that runs on all nodes with hostNetwork, collecting
 * host metrics, Prometheus scrapes (Beyla, ebpf_exporter, MAAC, YACE, Hubble),
 * plus dynamic per-workload scrape jobs from the metrics registry ConfigMaps,
 * file-based logs (system, Cassandra, ClickHouse), and OTLP.
 * Exports to VictoriaMetrics, VictoriaLogs, and Tempo.
 *
 * Config uses OTel runtime env expansion (`${env:HOSTNAME}`, `${env:CLUSTER_NAME}`),
 * not `__KEY__` template substitution.
 *
 * @property templateService Used for loading config files from classpath resources
 */
class OtelManifestBuilder(
    private val templateService: TemplateService,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val NAMESPACE = Constants.K8s.NAMESPACE
        private const val APP_LABEL = "otel-collector"
        private const val CONFIGMAP_NAME = "otel-collector-config"
        private const val CONFIG_DATA_KEY = "otel-collector-config.yaml"
        private const val IMAGE = "otel/opentelemetry-collector-contrib:latest"
        private const val SERVICE_ACCOUNT_NAME = "otel-collector"
        private const val CLUSTER_ROLE_NAME = "otel-collector"
        private const val LIVENESS_INITIAL_DELAY = 10
        private const val LIVENESS_PERIOD = 30
        private const val READINESS_INITIAL_DELAY = 5
        private const val READINESS_PERIOD = 10
        private val WORKLOAD_METRICS_LABEL = Constants.K8s.WORKLOAD_METRICS_LABEL
        private const val SCRAPE_INTERVAL = "15s"
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
            val path = data["path"] ?: "/metrics"
            val username = data["username"] ?: ""
            val podSelector = data["pod-selector"] ?: ""
            // Falls back to jobName for ConfigMaps written before the kit-name key was added
            val kitName = data["kit-name"] ?: jobName
            WorkloadScrapeConfig(
                kitName = kitName,
                jobName = jobName,
                port = port,
                path = path,
                username = username,
                podSelector = podSelector,
            )
        }
    }

    /**
     * Builds all OTel Collector K8s resources in apply order.
     *
     * @param scrapeConfigs Dynamic per-workload scrape targets from the metrics registry.
     *   Pass the result of [listWorkloadScrapeConfigs] to include currently-running workloads.
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, DaemonSet, Service
     */
    fun buildAllResources(scrapeConfigs: List<WorkloadScrapeConfig> = emptyList()): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildConfigMap(scrapeConfigs),
            buildDaemonSet(),
            buildService(),
        )

    /**
     * Builds the ServiceAccount for OTel Collector pods.
     * Required by k8sattributes processor for K8s API access.
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
     * Builds the ClusterRole granting read access to pods and nodes.
     * Required by k8sattributes processor to extract node labels as resource attributes.
     */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addNewRule()
            .withApiGroups("")
            .withResources("pods", "nodes")
            .withVerbs("get", "watch", "list")
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
     * Builds the OTel Collector ConfigMap, merging static infrastructure scrape jobs
     * with dynamic per-workload scrape jobs from the metrics registry.
     *
     * @param scrapeConfigs Workload scrape targets to inject (from [listWorkloadScrapeConfigs])
     */
    fun buildConfigMap(scrapeConfigs: List<WorkloadScrapeConfig> = emptyList()) =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName(CONFIGMAP_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addToData(
                CONFIG_DATA_KEY,
                templateService
                    .fromResource(
                        OtelManifestBuilder::class.java,
                        "otel-collector-config.yaml",
                    ).substitute(mapOf("KIT_SCRAPE_JOBS" to buildDynamicScrapeJobsYaml(scrapeConfigs))),
            ).build()

    private fun buildDynamicScrapeJobsYaml(configs: List<WorkloadScrapeConfig>): String {
        if (configs.isEmpty()) return ""
        val jobs =
            configs.map { config ->
                if (config.podSelector.isNotBlank()) buildPodScrapeJob(config) else buildStaticScrapeJob(config)
            }
        // 8-space indent matches the depth of `- job_name:` entries under
        // `receivers.prometheus.config.scrape_configs` in otel-collector-config.yaml.
        // If the scrape_configs indentation changes, this must change too.
        return Yaml()
            .encodeToString(ListSerializer(PrometheusScrapeJob.serializer()), jobs)
            .trimEnd()
            .lines()
            .joinToString("\n") { line -> if (line.isEmpty()) "" else "        $line" }
    }

    /**
     * Builds a static NodePort scrape job. Every collector on every node scrapes the same
     * `localhost:<nodePort>` target, so `instance` is stamped with the collector's own hostname.
     */
    private fun buildStaticScrapeJob(config: WorkloadScrapeConfig) =
        PrometheusScrapeJob(
            // Use kitName-jobName compound key to ensure uniqueness in all cases:
            // - Same kit with multiple scrape targets (e.g. kafka-exporter and kafka-jmx)
            // - Multiple kit instances sharing the same jobName (e.g. postgres-duckdb and postgres-postgis)
            jobName = "${config.kitName}-${config.jobName}",
            scrapeInterval = SCRAPE_INTERVAL,
            staticConfigs = listOf(PrometheusStaticConfig(targets = listOf("localhost:${config.port}"))),
            metricsPath = config.path,
            relabelConfigs =
                listOf(
                    // Preserve the logical job name from kit.yaml as the `job` label so
                    // Prometheus queries like job="postgres" continue to work across instances.
                    PrometheusRelabelConfig(targetLabel = "job", replacement = config.jobName),
                    PrometheusRelabelConfig(targetLabel = "instance", replacement = "\${env:HOSTNAME}:${config.port}"),
                    PrometheusRelabelConfig(targetLabel = "cluster", replacement = "\${env:CLUSTER_NAME}"),
                ),
            basicAuth = if (config.username.isNotBlank()) PrometheusBasicAuth(username = config.username) else null,
        )

    /**
     * Builds a pod service-discovery scrape job. Each pod is scraped directly on its own IP, so
     * `instance` carries the pod name (e.g. `tidb-tikv-0`) — the stable per-store identity a shared
     * NodePort cannot provide. Discovery is filtered to pods matching [WorkloadScrapeConfig.podSelector]
     * that are co-located on the scraping collector's node (`__meta_kubernetes_pod_node_name` == the
     * collector's `HOSTNAME`). Node-local filtering is essential: without it, every collector in the
     * DaemonSet would scrape every pod, producing duplicate series for the same `instance`.
     */
    private fun buildPodScrapeJob(config: WorkloadScrapeConfig): PrometheusScrapeJob {
        val selectorRelabels =
            config.podSelector
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { pair ->
                    val (key, value) = pair.split("=", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                    PrometheusRelabelConfig(
                        sourceLabels = listOf(podLabelMetaKey(key)),
                        regex = value,
                        action = "keep",
                    )
                }
        return PrometheusScrapeJob(
            jobName = "${config.kitName}-${config.jobName}",
            scrapeInterval = SCRAPE_INTERVAL,
            kubernetesSdConfigs =
                listOf(
                    PrometheusKubernetesSdConfig(
                        role = "pod",
                        namespaces = PrometheusSdNamespaces(names = listOf(NAMESPACE)),
                    ),
                ),
            metricsPath = config.path,
            relabelConfigs =
                selectorRelabels +
                    listOf(
                        // Only scrape pods on this collector's node — one TiKV pod per db node.
                        PrometheusRelabelConfig(
                            sourceLabels = listOf("__meta_kubernetes_pod_node_name"),
                            regex = "\${env:HOSTNAME}",
                            action = "keep",
                        ),
                        // Target the pod IP directly on its container metrics port (not the NodePort).
                        // OTel expands $$ to a literal $, leaving Prometheus its $1 capture group.
                        PrometheusRelabelConfig(
                            sourceLabels = listOf("__meta_kubernetes_pod_ip"),
                            targetLabel = "__address__",
                            replacement = "\$\$1:${config.port}",
                        ),
                        // Per-pod identity: instance = pod name (the store), not the NodePort.
                        PrometheusRelabelConfig(
                            sourceLabels = listOf("__meta_kubernetes_pod_name"),
                            targetLabel = "instance",
                        ),
                        PrometheusRelabelConfig(targetLabel = "job", replacement = config.jobName),
                        PrometheusRelabelConfig(targetLabel = "cluster", replacement = "\${env:CLUSTER_NAME}"),
                    ),
            basicAuth = if (config.username.isNotBlank()) PrometheusBasicAuth(username = config.username) else null,
        )
    }

    /**
     * Converts a K8s pod label key (e.g. `app.kubernetes.io/component`) into the Prometheus
     * pod-discovery meta-label (`__meta_kubernetes_pod_label_app_kubernetes_io_component`).
     * Prometheus sanitizes label names by replacing every non-alphanumeric character with `_`.
     */
    private fun podLabelMetaKey(key: String): String {
        val sanitized = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "__meta_kubernetes_pod_label_$sanitized"
    }

    /**
     * Builds the OTel Collector DaemonSet.
     *
     * Runs on all nodes with hostNetwork, privileged mode.
     * Mounts log directories as hostPath volumes for system, Cassandra,
     * and K8s container log collection. `/mnt/db1/container-logs` is mounted so the
     * collector can follow the `/var/log/pods` symlink when logs are on NVMe storage.
     * ClickHouse logs via container stdout — no host path mount needed.
     */
    @Suppress("LongMethod")
    fun buildDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(SERVICE_ACCOUNT_NAME)
            .withHostNetwork(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).withArgs("--config=/etc/otel-collector-config.yaml")
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_GRPC_PORT)
            .withHostPort(Constants.K8s.OTEL_GRPC_PORT)
            .withProtocol("TCP")
            .withName("otlp-grpc")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_HTTP_PORT)
            .withHostPort(Constants.K8s.OTEL_HTTP_PORT)
            .withProtocol("TCP")
            .withName("otlp-http")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.OTEL_HEALTH_PORT)
            .withHostPort(Constants.K8s.OTEL_HEALTH_PORT)
            .withProtocol("TCP")
            .withName("health")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.K8s.JAEGER_THRIFT_COMPACT_PORT)
            .withHostPort(Constants.K8s.JAEGER_THRIFT_COMPACT_PORT)
            .withProtocol("UDP")
            .withName("jaeger-compact")
            .endPort()
            .addNewEnv()
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
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/otel-collector-config.yaml")
                    .withSubPath(CONFIG_DATA_KEY)
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("var-log")
                    .withMountPath("/var/log")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("container-logs")
                    .withMountPath("/mnt/db1/container-logs")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("cassandra-logs")
                    .withMountPath("/mnt/db1/cassandra/logs")
                    .withReadOnly(true)
                    .build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/")
            .withNewPort(Constants.K8s.OTEL_HEALTH_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .endContainer()
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName(CONFIGMAP_NAME)
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("var-log")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/var/log")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("container-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/container-logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("cassandra-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/cassandra/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /**
     * Builds a ClusterIP Service exposing the OTLP gRPC port (4317) for in-cluster access.
     *
     * Allows standard K8s pods (non-hostNetwork, e.g. TiDB Operator-managed pods) to push
     * traces to `otel-collector.default.svc.cluster.local:4317` without knowing node IPs.
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
            .addToSelector("app.kubernetes.io/name", APP_LABEL)
            .addNewPort()
            .withName("otlp-grpc")
            .withPort(Constants.K8s.OTEL_GRPC_PORT)
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
