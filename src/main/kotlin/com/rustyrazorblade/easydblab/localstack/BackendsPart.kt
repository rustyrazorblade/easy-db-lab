package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder

/**
 * A stack's backends, reading a store ([BackendStorage]) with read-path components only. No
 * component compacts, merges or deletes; nothing receives data to write.
 *
 * - Mimir and Loki run the cluster's own `mimir.yaml` and `loki.yaml` with flag overrides, as the
 *   account compactor does. Mimir runs its read path plus an idle ingester (with
 *   `query_ingesters_within: 0`, a query ending now fails with "empty ring" without one; no
 *   distributor runs, so it never receives a sample). Loki runs `read,query-scheduler` against the
 *   store only, with its own address as the compactor's (it exits at start without one) and no
 *   compactor module.
 * - Mimir's four caches are the stack's memcached, an ordinary service on the project network,
 *   with the cluster's size, connection and thread settings.
 * - Tempo runs as its single binary with the generated configuration.
 *
 * Each keeps its working state under `cache/<component>`.
 */
object BackendsPart {
    private const val MIMIR_CONFIG = "/etc/mimir/mimir.yaml"
    private const val LOKI_CONFIG = "/etc/loki/loki.yaml"
    private const val TEMPO_CONFIG = "/etc/tempo/tempo.yaml"
    private const val TEMPO_FILE = "tempo.yaml"

    /** The backends run as root, so they can write the state directories docker creates for them. */
    private const val ROOT_USER = "0"

    private val MEMCACHED = "${Constants.LocalStack.Services.MEMCACHED}:${Constants.K8s.MIMIR_MEMCACHED_PORT}"

    /** The part for the live store in [live]'s bucket. */
    fun live(live: LocalStackSpec.Mode.Live): ComposePart = compose(GatedStore(live))

    /** The part over [storage]. */
    internal fun compose(storage: BackendStorage): ComposePart {
        val clusterTempo = LocalStackComposer.resourceNextTo(TempoManifestBuilder::class.java, TEMPO_FILE)
        val clusterLoki = LocalStackComposer.resourceNextTo(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE)
        return ComposePart(
            services =
                mapOf(
                    Constants.LocalStack.Services.MEMCACHED to memcached(),
                    Constants.LocalStack.Services.MIMIR to mimir(storage),
                    Constants.LocalStack.Services.LOKI to loki(storage),
                    Constants.LocalStack.Services.TEMPO to tempo(storage),
                ),
            files =
                mapOf(
                    LocalStackComposer.configPath(MimirManifestBuilder.CONFIG_FILE) to
                        LocalStackComposer.resourceNextTo(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                    LocalStackComposer.configPath(LokiManifestBuilder.CONFIG_FILE) to storage.lokiConfig(clusterLoki),
                    LocalStackComposer.configPath(TEMPO_FILE) to storage.tempoConfig(clusterTempo),
                ),
        )
    }

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

    private fun mimir(storage: BackendStorage): ComposeService =
        ComposeService(
            image = LocalImages.MIMIR,
            user = ROOT_USER,
            command =
                listOf(
                    "-config.file=$MIMIR_CONFIG",
                    "-config.expand-env=true",
                    "-target=query-frontend,querier,store-gateway,query-scheduler,ingester",
                    "-usage-stats.enabled=false",
                ) + storage.mimirFlags +
                    listOf(
                        "-blocks-storage.bucket-store.index-cache.memcached.addresses=$MEMCACHED",
                        "-blocks-storage.bucket-store.chunks-cache.memcached.addresses=$MEMCACHED",
                        "-blocks-storage.bucket-store.metadata-cache.memcached.addresses=$MEMCACHED",
                        "-query-frontend.results-cache.memcached.addresses=$MEMCACHED",
                    ),
            environment = storage.environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(MimirManifestBuilder.CONFIG_FILE, MIMIR_CONFIG),
                    stateMount(Constants.LocalStack.Services.MIMIR, "/data"),
                ) + storage.mounts,
            dependsOn = storage.dependsOn + (Constants.LocalStack.Services.MEMCACHED to ComposeDependency.STARTED),
            restart = "unless-stopped",
        )

    private fun loki(storage: BackendStorage): ComposeService =
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
                ) + storage.lokiFlags,
            environment = storage.environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(LokiManifestBuilder.CONFIG_FILE, LOKI_CONFIG),
                    stateMount(Constants.LocalStack.Services.LOKI, "/loki"),
                ) + storage.mounts,
            dependsOn = storage.dependsOn,
            restart = "unless-stopped",
        )

    private fun tempo(storage: BackendStorage): ComposeService =
        ComposeService(
            image = LocalImages.TEMPO,
            user = ROOT_USER,
            command = listOf("-config.file=$TEMPO_CONFIG", "-config.expand-env=true"),
            environment = storage.environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(TEMPO_FILE, TEMPO_CONFIG),
                    stateMount(Constants.LocalStack.Services.TEMPO, "/var/tempo"),
                ) + storage.mounts,
            dependsOn = storage.dependsOn,
            restart = "unless-stopped",
        )
}
