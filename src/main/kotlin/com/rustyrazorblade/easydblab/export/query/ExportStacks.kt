package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.localstack.BackendsPart
import com.rustyrazorblade.easydblab.localstack.ComposeMount
import com.rustyrazorblade.easydblab.localstack.ComposePart
import com.rustyrazorblade.easydblab.localstack.ComposeService
import com.rustyrazorblade.easydblab.localstack.ExportedFiles
import com.rustyrazorblade.easydblab.localstack.LocalImages
import com.rustyrazorblade.easydblab.localstack.LocalStackComposer
import com.rustyrazorblade.easydblab.localstack.LocalStackSpec
import com.rustyrazorblade.easydblab.localstack.StoreAccessPart
import com.rustyrazorblade.easydblab.localstack.YamlDocument
import com.rustyrazorblade.easydblab.localstack.YamlPatch
import com.rustyrazorblade.easydblab.localstack.backendEnvironment

/** Publishes a container port on a random port of the loopback address, which `docker compose port` then names. */
private fun randomLoopback(port: Int): String = "${Constants.LocalStack.LOOPBACK}::$port"

/**
 * The temporary reader of a query export: the live local stack's read-only access to the store
 * (the signer with the operator's profile, the GET/HEAD gate, the store check) and its read-only
 * backends, with no Grafana. Each backend is published on a random loopback port, so two exports never
 * clash and nothing else on the machine can be reached through it.
 */
object ExportReaderStack {
    /** The backends, by service, and the container port each is reached on. */
    val PUBLISHED: Map<String, Int> =
        mapOf(
            Constants.LocalStack.Services.MIMIR to Constants.K8s.MIMIR_HTTP_PORT,
            Constants.LocalStack.Services.LOKI to Constants.K8s.LOKI_HTTP_PORT,
            Constants.LocalStack.Services.TEMPO to Constants.K8s.TEMPO_PORT,
        )

    /** Every file of the reader over the store [live] names, by path relative to its directory. */
    fun compose(live: LocalStackSpec.Mode.Live): Map<String, ByteArray> {
        val backends = BackendsPart.live(live)
        val published =
            backends.copy(
                services =
                    backends.services.mapValues { (name, service) ->
                        PUBLISHED[name]?.let { service.copy(ports = listOf(randomLoopback(it))) } ?: service
                    },
            )
        return LocalStackComposer.join(listOf(StoreAccessPart.live(live), published))
    }
}

/**
 * The temporary writer of a query export: a Loki and a Tempo over one filesystem store at [STORE],
 * which becomes the zip's logs and traces. It has no Mimir: metrics are copied as blocks, and a Mimir
 * writer's shipper needs an xattr a macOS bind mount does not support (spike S4).
 *
 * - Loki is the cluster's `loki.yaml` with the offline stack's filesystem storage
 *   ([ExportedFiles.lokiConfig], so the zip's Loki reads what this one wrote) and old lines accepted.
 * - Tempo is the cluster's `tempo.yaml` with the local backend and both WAL ingestion slacks at
 *   [INGESTION_SLACK]. At the default 2m an old span's block takes the push time as its range and no
 *   reader finds it over its real time; 0 would clamp every span, since the clamp has no "off"
 *   (spike S3). Compaction stays off, as in the cluster's file.
 *
 * Both run as the operator ([owner], `uid:gid`), so everything they write in the work directory is the
 * operator's, and each is published on a random loopback port.
 */
object ExportWriterStack {
    /** Where both writers see the store they write. */
    const val STORE = ExportedFiles.STORE

    /** The store's directory, relative to the writer's directory. */
    const val STORE_DIR = "store"

    /** The writers' working state, relative to the writer's directory. */
    const val STATE_DIR = "state"

    /** Wide enough for any span a cluster wrote; never 0 (spike S3). */
    const val INGESTION_SLACK = "87600h"

    /** Tempo's live-store WAL, relative to its state directory, where the drain looks for `flushed` markers. */
    const val TEMPO_LIVE_WAL = "live-store/wal"

    private const val LOKI_CONFIG_DIR = "/etc/loki"
    private const val TEMPO_CONFIG_DIR = "/etc/tempo"
    private const val TEMPO_FILE = "tempo.yaml"
    private const val LOKI_STATE = "/loki"
    private const val TEMPO_STATE = "/var/tempo"

    /** What the cluster files expand where no bucket, region or cluster exists. */
    private const val NONE = "export"

    /** Every file of the writer for an export whose home tenant is [home], run as [owner]. */
    fun compose(
        home: String,
        owner: String,
    ): Map<String, ByteArray> {
        val environment = backendEnvironment(bucket = NONE, region = NONE, home = home)
        val clusterLoki = LocalStackComposer.resourceNextTo(LokiManifestBuilder::class.java, LokiManifestBuilder.CONFIG_FILE)
        val clusterTempo = LocalStackComposer.resourceNextTo(TempoManifestBuilder::class.java, TEMPO_FILE)
        val offline = ExportedFiles(LocalStackSpec.Mode.Offline(dataDir = STORE_DIR, home = home))
        val part =
            ComposePart(
                services =
                    mapOf(
                        Constants.LocalStack.Services.LOKI to loki(owner, environment),
                        Constants.LocalStack.Services.TEMPO to tempo(owner, environment),
                    ),
                files =
                    mapOf(
                        LocalStackComposer.configPath(Constants.LocalStack.Services.LOKI, LokiManifestBuilder.CONFIG_FILE) to
                            YamlDocument.patch(
                                offline.lokiConfig(clusterLoki),
                                listOf(YamlPatch.set("limits_config.reject_old_samples", "false")),
                            ),
                        LocalStackComposer.configPath(Constants.LocalStack.Services.TEMPO, TEMPO_FILE) to tempoConfig(clusterTempo),
                    ),
            )
        return LocalStackComposer.join(listOf(part))
    }

    /** The store both writers write, read-write. */
    private val store: ComposeMount get() = ComposeMount.bind("./$STORE_DIR", STORE, readOnly = false)

    private fun loki(
        owner: String,
        environment: Map<String, String>,
    ): ComposeService =
        ComposeService(
            image = LocalImages.LOKI,
            user = owner,
            command =
                listOf(
                    "-config.file=$LOKI_CONFIG_DIR/${LokiManifestBuilder.CONFIG_FILE}",
                    "-config.expand-env=true",
                    "-target=all",
                    "-reporting.enabled=false",
                ),
            environment = environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(Constants.LocalStack.Services.LOKI, LOKI_CONFIG_DIR),
                    store,
                    state(Constants.LocalStack.Services.LOKI, LOKI_STATE),
                ),
            ports = listOf(randomLoopback(Constants.K8s.LOKI_HTTP_PORT)),
            restart = "no",
        )

    private fun tempo(
        owner: String,
        environment: Map<String, String>,
    ): ComposeService =
        ComposeService(
            image = LocalImages.TEMPO,
            user = owner,
            command = listOf("-config.file=$TEMPO_CONFIG_DIR/$TEMPO_FILE", "-config.expand-env=true"),
            environment = environment,
            volumes =
                listOf(
                    LocalStackComposer.configMount(Constants.LocalStack.Services.TEMPO, TEMPO_CONFIG_DIR),
                    store,
                    state(Constants.LocalStack.Services.TEMPO, TEMPO_STATE),
                ),
            ports = listOf(randomLoopback(Constants.K8s.TEMPO_PORT), randomLoopback(Constants.K8s.TEMPO_OTLP_HTTP_PORT)),
            restart = "no",
        )

    /** The writer's Tempo: the local backend over the store, old spans keeping their own times. */
    private fun tempoConfig(clusterYaml: String): String =
        YamlDocument.patch(
            clusterYaml,
            listOf(
                YamlPatch.set("storage.trace.backend", "local"),
                YamlPatch.remove("storage.trace.s3"),
                YamlPatch.set("storage.trace.local.path", "$STORE/${Constants.Observability.TRACES_ROOT}"),
                YamlPatch.set("storage.trace.wal.ingestion_time_range_slack", INGESTION_SLACK),
                YamlPatch.set("live_store.wal.ingestion_time_range_slack", INGESTION_SLACK),
                YamlPatch.set("overrides.defaults.compaction.compaction_disabled", "true"),
                YamlPatch.set("usage_report.reporting_enabled", "false"),
            ),
        )

    private fun state(
        component: String,
        target: String,
    ): ComposeMount = ComposeMount.bind("./$STATE_DIR/$component", target, readOnly = false)
}
