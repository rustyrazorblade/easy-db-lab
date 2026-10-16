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
 *   with the cluster's connection and thread settings and room for Tempo's block reads.
 * - Tempo runs as its single binary with the generated configuration. Reading the live store, it
 *   keeps every block read in the same memcached ([TempoLocalConfig.live]); it starts after
 *   memcached in either mode, as Mimir does.
 *
 * Each keeps its working state under `cache/<component>`.
 */
object BackendsPart {
    private const val MIMIR_CONFIG_DIR = "/etc/mimir"
    private const val LOKI_CONFIG_DIR = "/etc/loki"
    private const val TEMPO_CONFIG_DIR = "/etc/tempo"
    private const val TEMPO_FILE = "tempo.yaml"
    private const val MIMIR_CONFIG = "$MIMIR_CONFIG_DIR/mimir.yaml"
    private const val LOKI_CONFIG = "$LOKI_CONFIG_DIR/loki.yaml"
    private const val TEMPO_CONFIG = "$TEMPO_CONFIG_DIR/$TEMPO_FILE"

    /** The backends run as root, so they can write the state directories docker creates for them. */
    private const val ROOT_USER = "0"

    /** The stack's memcached as a reader backend needs it ([Constants.LocalStack.MEMCACHED_MEMORY_MB]), on the project network. */
    private val MEMCACHED_ARGS =
        listOf(
            "-m",
            "${Constants.LocalStack.MEMCACHED_MEMORY_MB}",
            "-I",
            "${Constants.LocalStack.MEMCACHED_MAX_ITEM_MB}m",
            "-c",
            "${Constants.LocalStack.MEMCACHED_MAX_CONNECTIONS}",
            "-t",
            "${Constants.LocalStack.MEMCACHED_THREADS}",
            "-p",
            "${Constants.K8s.MIMIR_MEMCACHED_PORT}",
        )

    private val AFTER_MEMCACHED = Constants.LocalStack.Services.MEMCACHED to ComposeDependency.STARTED

    /** The part for the live store in [live]'s bucket. */
    fun live(live: LocalStackSpec.Mode.Live): ComposePart = compose(GatedStore(live))

    /** The part for the export's copy of the store in [offline]'s data directory. */
    fun offline(offline: LocalStackSpec.Mode.Offline): ComposePart = compose(ExportedFiles(offline))

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
                    LocalStackComposer.configPath(Constants.LocalStack.Services.MIMIR, MimirManifestBuilder.CONFIG_FILE) to
                        LocalStackComposer.resourceNextTo(MimirManifestBuilder::class.java, MimirManifestBuilder.CONFIG_FILE),
                    LocalStackComposer.configPath(Constants.LocalStack.Services.LOKI, LokiManifestBuilder.CONFIG_FILE) to
                        storage.lokiConfig(clusterLoki),
                    LocalStackComposer.configPath(Constants.LocalStack.Services.TEMPO, TEMPO_FILE) to storage.tempoConfig(clusterTempo),
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
            command = MEMCACHED_ARGS,
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
                        "-blocks-storage.bucket-store.index-cache.memcached.addresses=${LocalStackComposer.MEMCACHED_ENDPOINT}",
                        "-blocks-storage.bucket-store.chunks-cache.memcached.addresses=${LocalStackComposer.MEMCACHED_ENDPOINT}",
                        "-blocks-storage.bucket-store.metadata-cache.memcached.addresses=${LocalStackComposer.MEMCACHED_ENDPOINT}",
                        "-query-frontend.results-cache.memcached.addresses=${LocalStackComposer.MEMCACHED_ENDPOINT}",
                    ),
            environment = storage.environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(Constants.LocalStack.Services.MIMIR, MIMIR_CONFIG_DIR),
                    stateMount(Constants.LocalStack.Services.MIMIR, "/data"),
                ) + storage.mounts,
            dependsOn = storage.dependsOn + AFTER_MEMCACHED,
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
            environment = storage.lokiEnvironment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(Constants.LocalStack.Services.LOKI, LOKI_CONFIG_DIR),
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
                    LocalStackComposer.configMount(Constants.LocalStack.Services.TEMPO, TEMPO_CONFIG_DIR),
                    stateMount(Constants.LocalStack.Services.TEMPO, "/var/tempo"),
                ) + storage.mounts,
            dependsOn = storage.dependsOn + AFTER_MEMCACHED,
            restart = "unless-stopped",
        )
}
