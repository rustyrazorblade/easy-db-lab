package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder

/**
 * The live stack's backends, reading the shared store through the gate with read-path components
 * only. No component compacts, merges or deletes; nothing receives data to write.
 *
 * - Mimir and Loki run the cluster's own `mimir.yaml` and `loki.yaml`, byte for byte, with flag
 *   overrides, as the account compactor does. Mimir runs its read path plus an idle ingester (with
 *   `query_ingesters_within: 0`, a query ending now fails with "empty ring" without one; no
 *   distributor runs, so it never receives a sample). Loki runs `read,query-scheduler` against the
 *   store only, with its own address as the compactor's (it exits at start without one) and no
 *   compactor module.
 * - Mimir's four caches are the stack's memcached, an ordinary service on the project network,
 *   with the cluster's size, connection and thread settings.
 * - Tempo runs as its single binary with the generated [TempoLocalConfig].
 *
 * Each keeps its working state under `cache/<component>`.
 */
object BackendsPart {
    private const val MIMIR_CONFIG = "/etc/mimir/mimir.yaml"
    private const val LOKI_CONFIG = "/etc/loki/loki.yaml"
    private const val TEMPO_CONFIG = "/etc/tempo/tempo.yaml"
    private const val TEMPO_FILE = "tempo.yaml"

    /** Keys for Mimir and Loki to sign with; the signer replaces the signature. */
    private const val PLACEHOLDER_KEY = "local-stack"

    /** The backends run as root, so they can write the state directories docker creates for them. */
    private const val ROOT_USER = "0"

    /** The stack's cluster name and experiment, which the cluster files expand but no reader uses. */
    private const val LOCAL_CLUSTER = "local"

    private val MEMCACHED = "${Constants.LocalStack.Services.MEMCACHED}:${Constants.K8s.MIMIR_MEMCACHED_PORT}"

    /** The part for the live store in [live]'s bucket. */
    fun live(live: LocalStackSpec.Mode.Live): ComposePart {
        val clusterTempo = LocalStackComposer.resourceNextTo(TempoManifestBuilder::class.java, TEMPO_FILE)
        return ComposePart(
            services =
                mapOf(
                    Constants.LocalStack.Services.MEMCACHED to memcached(),
                    Constants.LocalStack.Services.MIMIR to mimir(live),
                    Constants.LocalStack.Services.LOKI to loki(live),
                    Constants.LocalStack.Services.TEMPO to tempo(live),
                ),
            files =
                mapOf(
                    LocalStackComposer.configPath(MimirManifestBuilder.CONFIG_FILE) to
                        LocalStackComposer.resourceNextTo(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                    LocalStackComposer.configPath(LokiManifestBuilder.CONFIG_FILE) to
                        LocalStackComposer.resourceNextTo(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE),
                    LocalStackComposer.configPath(
                        TEMPO_FILE,
                    ) to TempoLocalConfig.live(clusterTempo, LocalStackComposer.STORE_GATE_ENDPOINT),
                ),
        )
    }

    /** What the cluster files expand: the bucket, its region, each backend's root, and the home tenant. */
    private fun environment(live: LocalStackSpec.Mode.Live): Map<String, String> =
        mapOf(
            "S3_BUCKET" to live.bucket,
            "AWS_REGION" to live.region,
            "METRICS_S3_PREFIX" to Constants.Observability.METRICS_ROOT,
            "LOGS_S3_PREFIX" to Constants.Observability.LOGS_ROOT,
            "TRACES_S3_PREFIX" to Constants.Observability.TRACES_ROOT,
            "TENANT" to live.home,
            "CLUSTER_NAME" to LOCAL_CLUSTER,
            "EXPERIMENT" to LOCAL_CLUSTER,
        )

    /** Waits for the store check to pass and the gate to start. */
    private val STORE_READY =
        mapOf(
            Constants.LocalStack.Services.STORE_CHECK to ComposeDependency.COMPLETED,
            Constants.LocalStack.Services.STORE_GATE to ComposeDependency.STARTED,
        )

    private fun stateMount(
        component: String,
        target: String,
    ): ComposeMount = ComposeMount.bind("./${Constants.LocalStack.CACHE_DIR}/$component", target, readOnly = false)

    private fun memcached(): ComposeService =
        ComposeService(
            image = LocalImages.MEMCACHED,
            // No listen address: the cluster's loopback-only one would refuse Mimir's container.
            command = MimirManifestBuilder.MEMCACHED_SERVER_ARGS + listOf("-p", "${Constants.K8s.MIMIR_MEMCACHED_PORT}"),
            restart = "unless-stopped",
        )

    private fun mimir(live: LocalStackSpec.Mode.Live): ComposeService =
        ComposeService(
            image = LocalImages.MIMIR,
            user = ROOT_USER,
            command =
                listOf(
                    "-config.file=$MIMIR_CONFIG",
                    "-config.expand-env=true",
                    "-target=query-frontend,querier,store-gateway,query-scheduler,ingester",
                    "-usage-stats.enabled=false",
                    "-blocks-storage.s3.endpoint=${LocalStackComposer.STORE_GATE_ENDPOINT}",
                    "-blocks-storage.s3.insecure=true",
                    "-blocks-storage.s3.bucket-lookup-type=path",
                    "-blocks-storage.s3.access-key-id=$PLACEHOLDER_KEY",
                    "-blocks-storage.s3.secret-access-key=$PLACEHOLDER_KEY",
                    "-blocks-storage.bucket-store.index-cache.memcached.addresses=$MEMCACHED",
                    "-blocks-storage.bucket-store.chunks-cache.memcached.addresses=$MEMCACHED",
                    "-blocks-storage.bucket-store.metadata-cache.memcached.addresses=$MEMCACHED",
                    "-query-frontend.results-cache.memcached.addresses=$MEMCACHED",
                ),
            environment = environment(live),
            volumes =
                listOf(
                    LocalStackComposer.configMount(MimirManifestBuilder.CONFIG_FILE, MIMIR_CONFIG),
                    stateMount(Constants.LocalStack.Services.MIMIR, "/data"),
                ),
            dependsOn = STORE_READY + (Constants.LocalStack.Services.MEMCACHED to ComposeDependency.STARTED),
            restart = "unless-stopped",
        )

    private fun loki(live: LocalStackSpec.Mode.Live): ComposeService =
        ComposeService(
            image = LocalImages.LOKI,
            user = ROOT_USER,
            command =
                listOf(
                    "-config.file=$LOKI_CONFIG",
                    "-config.expand-env=true",
                    "-target=read,query-scheduler",
                    "-querier.query-store-only=true",
                    "-common.compactor-address=http://127.0.0.1:${Constants.K8s.LOKI_HTTP_PORT}",
                    "-reporting.enabled=false",
                    "-common.storage.s3.endpoint=${LocalStackComposer.STORE_GATE_ENDPOINT}",
                    "-common.storage.s3.insecure=true",
                    "-common.storage.s3.force-path-style=true",
                    "-common.storage.s3.access-key-id=$PLACEHOLDER_KEY",
                    "-common.storage.s3.secret-access-key=$PLACEHOLDER_KEY",
                ),
            environment = environment(live),
            volumes =
                listOf(
                    LocalStackComposer.configMount(LokiManifestBuilder.CONFIG_FILE, LOKI_CONFIG),
                    stateMount(Constants.LocalStack.Services.LOKI, "/loki"),
                ),
            dependsOn = STORE_READY,
            restart = "unless-stopped",
        )

    private fun tempo(live: LocalStackSpec.Mode.Live): ComposeService =
        ComposeService(
            image = LocalImages.TEMPO,
            user = ROOT_USER,
            command = listOf("-config.file=$TEMPO_CONFIG", "-config.expand-env=true"),
            environment = environment(live),
            volumes =
                listOf(
                    LocalStackComposer.configMount(TEMPO_FILE, TEMPO_CONFIG),
                    stateMount(Constants.LocalStack.Services.TEMPO, "/var/tempo"),
                ),
            dependsOn = STORE_READY,
            restart = "unless-stopped",
        )
}
