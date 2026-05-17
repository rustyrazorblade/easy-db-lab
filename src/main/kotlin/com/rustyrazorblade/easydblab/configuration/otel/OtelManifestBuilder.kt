package com.rustyrazorblade.easydblab.configuration.otel

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.oshai.kotlinlogging.KotlinLogging

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
        private const val NAMESPACE = "default"
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
        private const val WORKLOAD_METRICS_LABEL = "easydblab.com/workload-metrics"
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
            WorkloadScrapeConfig(jobName = jobName, port = port, path = path)
        }
    }

    /**
     * Builds all OTel Collector K8s resources in apply order.
     *
     * @param scrapeConfigs Dynamic per-workload scrape targets from the metrics registry.
     *   Pass the result of [listWorkloadScrapeConfigs] to include currently-running workloads.
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, DaemonSet
     */
    fun buildAllResources(scrapeConfigs: List<WorkloadScrapeConfig> = emptyList()): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildConfigMap(scrapeConfigs),
            buildDaemonSet(),
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
                    ).substitute(mapOf("WORKLOAD_SCRAPE_JOBS" to buildDynamicScrapeJobsYaml(scrapeConfigs))),
            ).build()

    private fun buildDynamicScrapeJobsYaml(configs: List<WorkloadScrapeConfig>): String {
        if (configs.isEmpty()) return ""
        val dollar = "\$"
        return configs.joinToString("\n") { config ->
            """
            |        - job_name: '${config.jobName}'
            |          scrape_interval: $SCRAPE_INTERVAL
            |          static_configs:
            |            - targets: ['localhost:${config.port}']
            |          metrics_path: '${config.path}'
            |          relabel_configs:
            |            - target_label: instance
            |              replacement: '$dollar{env:HOSTNAME}:${config.port}'
            |            - target_label: cluster
            |              replacement: '$dollar{env:CLUSTER_NAME}'
            """.trimMargin()
        }
    }

    /**
     * Builds the OTel Collector DaemonSet.
     *
     * Runs on all nodes with hostNetwork, privileged mode.
     * Mounts log directories as hostPath volumes for system, Cassandra, ClickHouse,
     * log collection.
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
                    .withName("cassandra-logs")
                    .withMountPath("/mnt/db1/cassandra/logs")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("clickhouse-server-logs")
                    .withMountPath("/mnt/db1/clickhouse/logs")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("clickhouse-keeper-logs")
                    .withMountPath("/mnt/db1/clickhouse/keeper/logs")
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
            .withName("cassandra-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/cassandra/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("clickhouse-server-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/clickhouse/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("clickhouse-keeper-logs")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/clickhouse/keeper/logs")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
