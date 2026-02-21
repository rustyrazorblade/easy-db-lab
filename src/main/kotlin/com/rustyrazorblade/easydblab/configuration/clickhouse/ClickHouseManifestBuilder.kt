package com.rustyrazorblade.easydblab.configuration.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ClickHouseConfigService
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.KeyToPathBuilder
import io.fabric8.kubernetes.api.model.ObjectFieldSelectorBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ConfigMapEnvSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder

/**
 * Builds all ClickHouse K8s resources as typed Fabric8 objects.
 *
 * Resources include:
 * - Keeper ConfigMap, headless Service, and StatefulSet (3 replicas for Raft consensus)
 * - Server ConfigMap (config.xml with dynamic shard injection, users.xml),
 *   Cluster Config ConfigMap (runtime values), headless Service, client NodePort Service,
 *   and StatefulSet (dynamic replicas with PVC template)
 *
 * @property clickHouseConfigService Used for dynamic shard injection into config.xml
 */
@Suppress("TooManyFunctions")
class ClickHouseManifestBuilder(
    private val clickHouseConfigService: ClickHouseConfigService,
) {
    companion object {
        private const val NAMESPACE = "default"

        // Labels
        private const val KEEPER_APP_LABEL = "clickhouse-keeper"
        private const val SERVER_APP_LABEL = "clickhouse-server"

        // Images
        private const val KEEPER_IMAGE = "clickhouse/clickhouse-keeper:latest"
        private const val SERVER_IMAGE = "clickhouse/clickhouse-server:latest"
        private const val BUSYBOX_IMAGE = "busybox:1.36"

        // Ports
        private const val KEEPER_CLIENT_PORT = 2181
        private const val KEEPER_RAFT_PORT = 9234
        private const val KEEPER_HTTP_CONTROL_PORT = 9182
        private const val METRICS_PORT = 9363
        private const val INTERSERVER_PORT = 9009

        // StatefulSet
        private const val KEEPER_REPLICAS = 3

        // Probes
        private const val LIVENESS_INITIAL_DELAY = 30
        private const val LIVENESS_PERIOD = 15
        private const val READINESS_INITIAL_DELAY = 10
        private const val READINESS_PERIOD = 10

        // ClickHouse user/group ID
        private const val CLICKHOUSE_UID = 101L
        private const val CLICKHOUSE_GID = 101L

        // PVC
        private const val PVC_STORAGE_SIZE = "100Gi"
    }

    /**
     * Builds all ClickHouse K8s resources in apply order.
     *
     * @param totalReplicas Total number of ClickHouse server replicas
     * @param replicasPerShard Number of replicas per shard
     * @param s3CacheSize S3 cache size (e.g. "10Gi")
     * @param s3CacheOnWrite Whether to cache on write operations
     * @return List of all K8s resources
     */
    fun buildAllResources(
        totalReplicas: Int,
        replicasPerShard: Int,
        s3CacheSize: String,
        s3CacheOnWrite: String,
    ): List<HasMetadata> =
        listOf(
            buildKeeperConfigMap(),
            buildServerConfigMap(totalReplicas, replicasPerShard),
            buildClusterConfigMap(replicasPerShard, s3CacheSize, s3CacheOnWrite),
            buildKeeperService(),
            buildServerHeadlessService(),
            buildServerClientService(),
            buildKeeperStatefulSet(),
            buildServerStatefulSet(),
        )

    /**
     * Builds the Keeper ConfigMap containing keeper_config.xml.
     */
    fun buildKeeperConfigMap(): HasMetadata {
        val keeperConfig = loadResource("keeper_config.xml")
        return ConfigMapBuilder()
            .withNewMetadata()
            .withName("clickhouse-keeper-config")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endMetadata()
            .addToData("keeper_config.xml", keeperConfig)
            .build()
    }

    /**
     * Builds the Server ConfigMap containing config.xml (with dynamic shard injection) and users.xml.
     */
    fun buildServerConfigMap(
        totalReplicas: Int,
        replicasPerShard: Int,
    ): HasMetadata {
        val templateConfigXml = loadResource("config.xml")
        val configXml = clickHouseConfigService.addShardsToConfigXml(templateConfigXml, totalReplicas, replicasPerShard)
        val usersXml = loadResource("users.xml")

        return ConfigMapBuilder()
            .withNewMetadata()
            .withName("clickhouse-server-config")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .addToData("config.xml", configXml)
            .addToData("users.xml", usersXml)
            .build()
    }

    /**
     * Builds the Cluster Config ConfigMap with runtime values read by pods via env vars.
     */
    fun buildClusterConfigMap(
        replicasPerShard: Int,
        s3CacheSize: String,
        s3CacheOnWrite: String,
    ): HasMetadata =
        ConfigMapBuilder()
            .withNewMetadata()
            .withName("clickhouse-cluster-config")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .addToData("replicas-per-shard", replicasPerShard.toString())
            .addToData("s3-cache-size", s3CacheSize)
            .addToData("s3-cache-on-write", s3CacheOnWrite)
            .build()

    /**
     * Builds the Keeper headless Service (ClusterIP=None) for StatefulSet DNS.
     */
    fun buildKeeperService(): HasMetadata =
        ServiceBuilder()
            .withNewMetadata()
            .withName("clickhouse-keeper")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .withClusterIP("None")
            .withPublishNotReadyAddresses(true)
            .addNewPort()
            .withName("client")
            .withPort(KEEPER_CLIENT_PORT)
            .withNewTargetPort(KEEPER_CLIENT_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("raft")
            .withPort(KEEPER_RAFT_PORT)
            .withNewTargetPort(KEEPER_RAFT_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("metrics")
            .withPort(METRICS_PORT)
            .withNewTargetPort(METRICS_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Server headless Service (ClusterIP=None) for StatefulSet DNS.
     */
    fun buildServerHeadlessService(): HasMetadata =
        ServiceBuilder()
            .withNewMetadata()
            .withName("clickhouse")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .withClusterIP("None")
            .addNewPort()
            .withName("http")
            .withPort(Constants.ClickHouse.HTTP_PORT)
            .withNewTargetPort(Constants.ClickHouse.HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("native")
            .withPort(Constants.ClickHouse.NATIVE_PORT)
            .withNewTargetPort(Constants.ClickHouse.NATIVE_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("interserver")
            .withPort(INTERSERVER_PORT)
            .withNewTargetPort(INTERSERVER_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("metrics")
            .withPort(METRICS_PORT)
            .withNewTargetPort(METRICS_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Server client NodePort Service for external access.
     */
    fun buildServerClientService(): HasMetadata =
        ServiceBuilder()
            .withNewMetadata()
            .withName("clickhouse-client")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .addToLabels("app.kubernetes.io/component", "client")
            .endMetadata()
            .withNewSpec()
            .withType("NodePort")
            .addNewPort()
            .withName("http")
            .withPort(Constants.ClickHouse.HTTP_PORT)
            .withNewTargetPort(Constants.ClickHouse.HTTP_PORT)
            .withProtocol("TCP")
            .endPort()
            .addNewPort()
            .withName("native")
            .withPort(Constants.ClickHouse.NATIVE_PORT)
            .withNewTargetPort(Constants.ClickHouse.NATIVE_PORT)
            .withProtocol("TCP")
            .endPort()
            .addToSelector("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endSpec()
            .build()

    /**
     * Builds the Keeper StatefulSet (3 replicas for Raft consensus).
     */
    @Suppress("LongMethod")
    fun buildKeeperStatefulSet(): HasMetadata =
        StatefulSetBuilder()
            .withNewMetadata()
            .withName("clickhouse-keeper")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withServiceName("clickhouse-keeper")
            .withReplicas(KEEPER_REPLICAS)
            .withPodManagementPolicy("Parallel")
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .addToNodeSelector("type", "db")
            .withNewAffinity()
            .withNewPodAntiAffinity()
            .addNewRequiredDuringSchedulingIgnoredDuringExecution()
            .withNewLabelSelector()
            .addToMatchLabels("app.kubernetes.io/name", KEEPER_APP_LABEL)
            .endLabelSelector()
            .withTopologyKey("kubernetes.io/hostname")
            .endRequiredDuringSchedulingIgnoredDuringExecution()
            .endPodAntiAffinity()
            .endAffinity()
            .withInitContainers(buildKeeperInitContainer())
            .withContainers(buildKeeperContainer())
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName("clickhouse-keeper-config")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("data")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/mnt/db1/clickhouse/keeper")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("logs")
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

    private fun buildKeeperInitContainer() =
        ContainerBuilder()
            .withName("init-data-dir")
            .withImage(BUSYBOX_IMAGE)
            .withCommand(
                "/bin/sh",
                "-c",
                """
                mkdir -p /var/lib/clickhouse-keeper/log
                mkdir -p /var/lib/clickhouse-keeper/snapshots
                chown -R 101:101 /var/lib/clickhouse-keeper
                mkdir -p /mnt/db1/clickhouse/keeper/logs
                chown -R 101:101 /mnt/db1/clickhouse/keeper/logs
                chmod 755 /mnt/db1/clickhouse/keeper/logs
                """.trimIndent(),
            ).addToVolumeMounts(
                VolumeMountBuilder().withName("data").withMountPath("/var/lib/clickhouse-keeper").build(),
                VolumeMountBuilder().withName("logs").withMountPath("/mnt/db1/clickhouse/keeper/logs").build(),
            ).build()

    private fun buildKeeperContainer() =
        ContainerBuilder()
            .withName("clickhouse-keeper")
            .withImage(KEEPER_IMAGE)
            .withSecurityContext(
                SecurityContextBuilder()
                    .withRunAsUser(CLICKHOUSE_UID)
                    .withRunAsGroup(CLICKHOUSE_GID)
                    .build(),
            ).addNewPort()
            .withContainerPort(KEEPER_CLIENT_PORT)
            .withName("client")
            .endPort()
            .addNewPort()
            .withContainerPort(KEEPER_RAFT_PORT)
            .withName("raft")
            .endPort()
            .addNewPort()
            .withContainerPort(METRICS_PORT)
            .withName("metrics")
            .endPort()
            .addNewPort()
            .withContainerPort(KEEPER_HTTP_CONTROL_PORT)
            .withName("http-control")
            .endPort()
            .addNewEnv()
            .withName("KEEPER_SERVER_ID")
            .withNewValueFrom()
            .withNewFieldRef()
            .withFieldPath("metadata.name")
            .endFieldRef()
            .endValueFrom()
            .endEnv()
            .withCommand(
                "/bin/bash",
                "-c",
                """
                umask 0022
                export KEEPER_SERVER_ID=${'$'}((${"\${HOSTNAME##*-}"} + 1))
                exec clickhouse-keeper --config-file=/etc/clickhouse-keeper/keeper_config.xml
                """.trimIndent(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/clickhouse-keeper")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder().withName("data").withMountPath("/var/lib/clickhouse-keeper").build(),
                VolumeMountBuilder().withName("logs").withMountPath("/mnt/db1/clickhouse/keeper/logs").build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(KEEPER_HTTP_CONTROL_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ready")
            .withNewPort(KEEPER_HTTP_CONTROL_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .build()

    /**
     * Builds the Server StatefulSet.
     *
     * Starts with 0 replicas (scaled up by ClickHouseStart after applying).
     * Uses hostNetwork, PVC template for local storage, and dynamic shard calculation.
     */
    @Suppress("LongMethod")
    fun buildServerStatefulSet(): HasMetadata =
        StatefulSetBuilder()
            .withNewMetadata()
            .withName("clickhouse")
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withServiceName("clickhouse")
            .withReplicas(0)
            .withPodManagementPolicy("Parallel")
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
            .addToNodeSelector("type", "db")
            .withNewAffinity()
            .withNewPodAntiAffinity()
            .addNewRequiredDuringSchedulingIgnoredDuringExecution()
            .withNewLabelSelector()
            .addToMatchLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
            .endLabelSelector()
            .withTopologyKey("kubernetes.io/hostname")
            .endRequiredDuringSchedulingIgnoredDuringExecution()
            .endPodAntiAffinity()
            .endAffinity()
            .withInitContainers(
                buildServerWaitForKeeperInitContainer(),
                buildServerInitDataDirContainer(),
            ).withContainers(buildServerContainer())
            .addNewVolume()
            .withName("config")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName("clickhouse-server-config")
                    .withItems(
                        KeyToPathBuilder().withKey("config.xml").withPath("config.xml").build(),
                    ).build(),
            ).endVolume()
            .addNewVolume()
            .withName("users")
            .withConfigMap(
                ConfigMapVolumeSourceBuilder()
                    .withName("clickhouse-server-config")
                    .withItems(
                        KeyToPathBuilder().withKey("users.xml").withPath("users.xml").build(),
                    ).build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .withVolumeClaimTemplates(
                PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName("data")
                    .addToLabels("app.kubernetes.io/name", SERVER_APP_LABEL)
                    .endMetadata()
                    .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withStorageClassName("local-storage")
                    .withNewResources()
                    .addToRequests("storage", Quantity(PVC_STORAGE_SIZE))
                    .endResources()
                    .endSpec()
                    .build(),
            ).endSpec()
            .build()

    private fun buildServerWaitForKeeperInitContainer() =
        ContainerBuilder()
            .withName("wait-for-keeper")
            .withImage(BUSYBOX_IMAGE)
            .withCommand(
                "/bin/sh",
                "-c",
                """
                echo "Waiting for ClickHouse Keeper to be ready..."
                until nc -z clickhouse-keeper-0.clickhouse-keeper.default.svc.cluster.local 2181; do
                  echo "Keeper not ready, sleeping..."
                  sleep 5
                done
                echo "Keeper is ready!"
                """.trimIndent(),
            ).build()

    private fun buildServerInitDataDirContainer() =
        ContainerBuilder()
            .withName("init-data-dir")
            .withImage(BUSYBOX_IMAGE)
            .withCommand(
                "/bin/sh",
                "-c",
                """
                mkdir -p /mnt/db1/clickhouse
                mkdir -p /mnt/db1/clickhouse/tmp
                mkdir -p /mnt/db1/clickhouse/user_files
                mkdir -p /mnt/db1/clickhouse/format_schemas
                mkdir -p /mnt/db1/clickhouse/disks/s3_disk
                mkdir -p /mnt/db1/clickhouse/disks/s3_cache
                mkdir -p /mnt/db1/clickhouse/logs
                chown -R 101:101 /mnt/db1/clickhouse
                """.trimIndent(),
            ).addToVolumeMounts(
                VolumeMountBuilder().withName("data").withMountPath("/mnt/db1/clickhouse").build(),
            ).build()

    @Suppress("LongMethod")
    private fun buildServerContainer() =
        ContainerBuilder()
            .withName("clickhouse")
            .withImage(SERVER_IMAGE)
            .withSecurityContext(
                SecurityContextBuilder()
                    .withRunAsUser(CLICKHOUSE_UID)
                    .withRunAsGroup(CLICKHOUSE_GID)
                    .build(),
            ).addNewPort()
            .withContainerPort(Constants.ClickHouse.HTTP_PORT)
            .withHostPort(Constants.ClickHouse.HTTP_PORT)
            .withName("http")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.ClickHouse.NATIVE_PORT)
            .withHostPort(Constants.ClickHouse.NATIVE_PORT)
            .withName("native")
            .endPort()
            .addNewPort()
            .withContainerPort(INTERSERVER_PORT)
            .withName("interserver")
            .endPort()
            .addNewPort()
            .withContainerPort(METRICS_PORT)
            .withName("metrics")
            .endPort()
            .withEnv(
                listOf(
                    EnvVarBuilder()
                        .withName("REPLICA")
                        .withValueFrom(
                            EnvVarSourceBuilder()
                                .withFieldRef(ObjectFieldSelectorBuilder().withFieldPath("metadata.name").build())
                                .build(),
                        ).build(),
                    EnvVarBuilder()
                        .withName("REPLICAS_PER_SHARD")
                        .withValueFrom(
                            EnvVarSourceBuilder()
                                .withConfigMapKeyRef(
                                    ConfigMapKeySelectorBuilder()
                                        .withName("clickhouse-cluster-config")
                                        .withKey("replicas-per-shard")
                                        .build(),
                                ).build(),
                        ).build(),
                    EnvVarBuilder()
                        .withName("CLICKHOUSE_S3_CACHE_SIZE")
                        .withValueFrom(
                            EnvVarSourceBuilder()
                                .withConfigMapKeyRef(
                                    ConfigMapKeySelectorBuilder()
                                        .withName("clickhouse-cluster-config")
                                        .withKey("s3-cache-size")
                                        .build(),
                                ).build(),
                        ).build(),
                    EnvVarBuilder()
                        .withName("CLICKHOUSE_S3_CACHE_ON_WRITE")
                        .withValueFrom(
                            EnvVarSourceBuilder()
                                .withConfigMapKeyRef(
                                    ConfigMapKeySelectorBuilder()
                                        .withName("clickhouse-cluster-config")
                                        .withKey("s3-cache-on-write")
                                        .build(),
                                ).build(),
                        ).build(),
                ),
            ).withEnvFrom(
                io.fabric8.kubernetes.api.model
                    .EnvFromSourceBuilder()
                    .withConfigMapRef(ConfigMapEnvSourceBuilder().withName(Constants.ClickHouse.S3_CONFIG_NAME).withOptional(false).build())
                    .build(),
            ).withCommand(
                "/bin/bash",
                "-c",
                """
                # Calculate shard from pod ordinal
                # REPLICA env var is the pod name (clickhouse-X), extract X and compute shard
                # Note: Can't use ${'$'}HOSTNAME because hostNetwork=true makes it the EC2 hostname
                ORDINAL=${'$'}{REPLICA##*-}
                export SHARD=${'$'}(( (ORDINAL / REPLICAS_PER_SHARD) + 1 ))
                echo "Pod ${'$'}REPLICA: ordinal=${'$'}ORDINAL, SHARD=${'$'}SHARD, REPLICAS_PER_SHARD=${'$'}REPLICAS_PER_SHARD"
                umask 0022
                exec /entrypoint.sh
                """.trimIndent(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("config")
                    .withMountPath("/etc/clickhouse-server/config.d")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("users")
                    .withMountPath("/etc/clickhouse-server/users.d")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder().withName("data").withMountPath("/mnt/db1/clickhouse").build(),
            ).withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/ping")
            .withNewPort(Constants.ClickHouse.HTTP_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withPeriodSeconds(LIVENESS_PERIOD)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/ping")
            .withNewPort(Constants.ClickHouse.HTTP_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withPeriodSeconds(READINESS_PERIOD)
            .endReadinessProbe()
            .build()

    private fun loadResource(name: String): String =
        ClickHouseManifestBuilder::class.java
            .getResourceAsStream(name)
            ?.bufferedReader()
            ?.readText()
            ?: error("Resource not found: $name")
}
