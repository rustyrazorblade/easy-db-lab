package com.rustyrazorblade.easydblab.commands.clickhouse

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.services.ClickHouseConfigService
import com.rustyrazorblade.easydblab.services.K8sService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path

/**
 * Deploy ClickHouse cluster to K8s.
 *
 * This command deploys a ClickHouse cluster with ClickHouse Keeper
 * for distributed coordination.
 *
 * Storage policies available for tables:
 * - 'local': Local disk storage (default)
 * - 's3_main': S3 storage with local cache
 *
 * Example creating a distributed replicated table:
 * ```sql
 * -- Create local replicated table on all nodes
 * CREATE TABLE events_local ON CLUSTER easy_db_lab (
 *     id UInt64,
 *     timestamp DateTime,
 *     data String
 * ) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/events', '{replica}')
 * ORDER BY (timestamp, id)
 * SETTINGS storage_policy = 's3_main';
 *
 * -- Create distributed table for querying across all shards
 * CREATE TABLE events ON CLUSTER easy_db_lab AS events_local
 * ENGINE = Distributed(easy_db_lab, default, events_local, rand());
 * ```
 */
@McpCommand
@RequireProfileSetup
@Command(
    name = "start",
    description = ["Deploy ClickHouse cluster to K8s"],
)
class ClickHouseStart : PicoBaseCommand() {
    private val log = KotlinLogging.logger {}
    private val k8sService: K8sService by inject()
    private val userConfig: User by inject()
    private val clickHouseConfigService: ClickHouseConfigService by inject()

    companion object {
        private const val K8S_MANIFEST_BASE = "k8s/clickhouse"
        private const val DEFAULT_TIMEOUT_SECONDS = 300
        private const val DEFAULT_REPLICAS_PER_SHARD = 3
    }

    @Option(
        names = ["--timeout"],
        description = ["Timeout in seconds to wait for pods to be ready (default: 300)"],
    )
    var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS

    @Option(
        names = ["--skip-wait"],
        description = ["Skip waiting for pods to be ready"],
    )
    var skipWait: Boolean = false

    @Option(
        names = ["--replicas"],
        description = ["Number of ClickHouse server replicas (default: number of db nodes)"],
    )
    var replicas: Int? = null

    @Option(
        names = ["--replicas-per-shard"],
        description = ["Number of replicas per shard (default: 3). Total shards = nodes / replicas-per-shard"],
    )
    var replicasPerShard: Int = DEFAULT_REPLICAS_PER_SHARD

    override fun execute() {
        val controlNode = getControlNode()
        val dbHosts = getAndValidateDbHosts()
        val actualReplicas = calculateReplicaCount(dbHosts)
        val shardCount = actualReplicas / replicasPerShard

        outputHandler.handleMessage(
            "Deploying ClickHouse: $shardCount shards x $replicasPerShard replicas = $actualReplicas nodes",
        )

        createLocalPersistentVolumes(controlNode, actualReplicas)
        val bucket = setupS3SecretIfConfigured(controlNode)
        applyManifestsAndConfigureCluster(controlNode, actualReplicas)
        waitForPodsIfRequired(controlNode)
        displayAccessInformation(dbHosts.first().privateIp, bucket)
    }

    private fun getControlNode(): ClusterHost {
        val controlHosts = clusterState.hosts[ServerType.Control]
        if (controlHosts.isNullOrEmpty()) {
            error("No control nodes found. Please ensure the environment is running.")
        }
        val controlNode = controlHosts.first()
        log.debug { "Using control node: ${controlNode.alias} (${controlNode.publicIp})" }
        return controlNode
    }

    private fun getAndValidateDbHosts(): List<ClusterHost> {
        val dbHosts = clusterState.hosts[ServerType.Cassandra]
        if (dbHosts.isNullOrEmpty()) {
            error("No db nodes found. Please ensure the environment is running.")
        }
        if (dbHosts.size < Constants.ClickHouse.MINIMUM_NODES_REQUIRED) {
            error(
                "ClickHouse requires at least ${Constants.ClickHouse.MINIMUM_NODES_REQUIRED} nodes " +
                    "for Keeper coordination. Found ${dbHosts.size} node(s).",
            )
        }
        return dbHosts
    }

    private fun calculateReplicaCount(dbHosts: List<ClusterHost>): Int {
        val actualReplicas = replicas ?: dbHosts.size
        if (actualReplicas % replicasPerShard != 0) {
            error(
                "Total replicas ($actualReplicas) must be divisible by replicas-per-shard ($replicasPerShard)",
            )
        }
        return actualReplicas
    }

    /**
     * Creates Local PersistentVolumes for ClickHouse data.
     *
     * Each PV has node affinity to ensure clickhouse-X pod runs on dbX node.
     */
    private fun createLocalPersistentVolumes(
        controlNode: ClusterHost,
        replicaCount: Int,
    ) {
        outputHandler.handleMessage("Creating Local PersistentVolumes for ClickHouse...")
        k8sService
            .createLocalPersistentVolumes(
                controlHost = controlNode,
                dbName = "clickhouse",
                localPath = "/mnt/db1/clickhouse",
                count = replicaCount,
                storageSize = "100Gi",
                namespace = Constants.ClickHouse.NAMESPACE,
                volumeClaimTemplateName = "data",
            ).getOrElse { exception ->
                error("Failed to create Local PVs: ${exception.message}")
            }
    }

    private fun setupS3SecretIfConfigured(controlNode: ClusterHost): String? {
        val bucket = clusterState.s3Bucket
        if (!bucket.isNullOrBlank()) {
            log.info { "Creating S3 secret for s3_main storage policy" }
            k8sService
                .createClickHouseS3Secret(controlNode, Constants.ClickHouse.NAMESPACE, userConfig.region, bucket)
                .getOrElse { exception ->
                    log.warn { "Failed to create S3 secret: ${exception.message}" }
                    outputHandler.handleMessage("Warning: S3 storage policy may not work (no S3 bucket configured)")
                }
        } else {
            outputHandler.handleMessage("Note: S3 bucket not configured. Only 'local' storage policy available.")
        }
        return bucket
    }

    private fun applyManifestsAndConfigureCluster(
        controlNode: ClusterHost,
        actualReplicas: Int,
    ) {
        log.info { "Applying ClickHouse manifests from $K8S_MANIFEST_BASE" }
        k8sService
            .applyManifests(controlNode, Path.of(K8S_MANIFEST_BASE))
            .getOrElse { exception ->
                error("Failed to apply ClickHouse manifests: ${exception.message}")
            }

        // Create cluster config with replicas-per-shard and s3-cache-size for pods
        val s3CacheSize =
            clusterState.clickHouseConfig?.s3CacheSize
                ?: Constants.ClickHouse.DEFAULT_S3_CACHE_SIZE

        k8sService
            .createConfigMap(
                controlHost = controlNode,
                namespace = Constants.ClickHouse.NAMESPACE,
                name = "clickhouse-cluster-config",
                data =
                    mapOf(
                        "replicas-per-shard" to replicasPerShard.toString(),
                        "s3-cache-size" to s3CacheSize,
                    ),
                labels = mapOf("app.kubernetes.io/name" to "clickhouse-server"),
            ).getOrElse { exception ->
                error("Failed to create ClickHouse cluster config: ${exception.message}")
            }

        val dynamicConfigMap =
            clickHouseConfigService.createDynamicConfigMap(actualReplicas, replicasPerShard, Path.of(K8S_MANIFEST_BASE))
        k8sService
            .createConfigMap(
                controlHost = controlNode,
                namespace = Constants.ClickHouse.NAMESPACE,
                name = "clickhouse-server-config",
                data = dynamicConfigMap,
                labels = mapOf("app.kubernetes.io/name" to "clickhouse-server"),
            ).getOrElse { exception ->
                error("Failed to create ClickHouse config: ${exception.message}")
            }

        k8sService
            .scaleStatefulSet(controlNode, Constants.ClickHouse.NAMESPACE, "clickhouse", actualReplicas)
            .getOrElse { exception ->
                error("Failed to scale ClickHouse StatefulSet: ${exception.message}")
            }
    }

    private fun waitForPodsIfRequired(controlNode: ClusterHost) {
        if (!skipWait) {
            outputHandler.handleMessage("Waiting for ClickHouse pods to be ready (this may take a few minutes)...")
            k8sService
                .waitForPodsReady(controlNode, timeoutSeconds, Constants.ClickHouse.NAMESPACE)
                .getOrElse { exception ->
                    outputHandler.handleError("Warning: Pods may not be ready: ${exception.message}")
                    outputHandler.handleMessage("You can check status with: easy-db-lab clickhouse status")
                }
        }
    }

    private fun displayAccessInformation(
        dbNodeIp: String,
        bucket: String?,
    ) {
        outputHandler.handleMessage("")
        outputHandler.handleMessage("ClickHouse cluster deployed successfully!")
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Storage policies available:")
        outputHandler.handleMessage("  - local: Local disk storage")
        if (!bucket.isNullOrBlank()) {
            val s3CacheSize =
                clusterState.clickHouseConfig?.s3CacheSize
                    ?: Constants.ClickHouse.DEFAULT_S3_CACHE_SIZE
            outputHandler.handleMessage("  - s3_main: S3 with local cache (bucket: $bucket, cache: $s3CacheSize)")
        }
        outputHandler.handleMessage(
            """

            Example - Create a distributed replicated table:

              -- Create local replicated table on all nodes
              CREATE TABLE events_local ON CLUSTER easy_db_lab (
                  id UInt64,
                  timestamp DateTime,
                  data String
              ) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/events', '{replica}')
              ORDER BY (timestamp, id)
              SETTINGS storage_policy = 's3_main';

              -- Create distributed table for querying across all shards
              CREATE TABLE events ON CLUSTER easy_db_lab AS events_local
              ENGINE = Distributed(easy_db_lab, default, events_local, rand());
            """.trimIndent(),
        )
        outputHandler.handleMessage("")
        outputHandler.handleMessage("HTTP Interface: http://$dbNodeIp:${Constants.ClickHouse.HTTP_PORT}")
        outputHandler.handleMessage("Native Protocol: $dbNodeIp:${Constants.ClickHouse.NATIVE_PORT}")
        outputHandler.handleMessage("")
        outputHandler.handleMessage("Connect with: clickhouse-client --host $dbNodeIp")
    }
}
