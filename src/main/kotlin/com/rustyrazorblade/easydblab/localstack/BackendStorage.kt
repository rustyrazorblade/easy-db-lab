package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants

/**
 * How a stack's backends reach the store they read: what each one is told about it, what it mounts
 * for it, and what it waits for. [BackendsPart] builds the same Mimir, memcached, Loki and Tempo
 * services over either kind, so the read-path component sets and the usage-reporting switches are one
 * definition for the live and the offline stack.
 */
internal interface BackendStorage {
    /** What the cluster's configuration files expand. */
    val environment: Map<String, String>

    /** Loki's environment: [environment], and whatever its S3 client needs besides. */
    val lokiEnvironment: Map<String, String> get() = environment

    /** The services every backend waits for before it starts. */
    val dependsOn: Map<String, ComposeDependency>

    /** Mounts every backend gets for the store. */
    val mounts: List<ComposeMount>

    /** Mimir's flags that point it at the store. */
    val mimirFlags: List<String>

    /** Loki's flags that point it at the store. */
    val lokiFlags: List<String>

    /** The Loki configuration file, from the cluster's [clusterYaml]. */
    fun lokiConfig(clusterYaml: String): String

    /** The Tempo configuration file, from the cluster's [clusterYaml]. */
    fun tempoConfig(clusterYaml: String): String
}

/**
 * The live shared store in S3, read through the gate that refuses every write. Mimir and Loki run the
 * cluster's files byte for byte, pointed at the gate by flags with placeholder keys the signer
 * replaces; Tempo's file is patched ([TempoLocalConfig.live]), since Tempo has no flag for it.
 *
 * @property live the bucket, its region and the home tenant.
 */
internal class GatedStore(
    private val live: LocalStackSpec.Mode.Live,
) : BackendStorage {
    private companion object {
        /** Keys for Mimir and Loki to sign with; the signer replaces the signature. */
        const val PLACEHOLDER_KEY = "local-stack"
    }

    override val environment: Map<String, String> get() = backendEnvironment(bucket = live.bucket, region = live.region, home = live.home)

    // Loki's aws-sdk-go-v2 would add an x-amz-checksum-mode header the signer does not sign, and S3 would
    // refuse every read: see Constants.LocalStack.AWS_RESPONSE_CHECKSUM_VALIDATION_ENV.
    override val lokiEnvironment: Map<String, String>
        get() =
            environment +
                mapOf(
                    Constants.LocalStack.AWS_RESPONSE_CHECKSUM_VALIDATION_ENV to Constants.LocalStack.AWS_CHECKSUM_WHEN_REQUIRED,
                    Constants.LocalStack.AWS_REQUEST_CHECKSUM_CALCULATION_ENV to Constants.LocalStack.AWS_CHECKSUM_WHEN_REQUIRED,
                )

    override val dependsOn: Map<String, ComposeDependency>
        get() =
            mapOf(
                Constants.LocalStack.Services.STORE_CHECK to ComposeDependency.COMPLETED,
                Constants.LocalStack.Services.STORE_GATE to ComposeDependency.STARTED,
            )

    override val mounts: List<ComposeMount> get() = emptyList()

    override val mimirFlags: List<String>
        get() =
            listOf(
                "-blocks-storage.s3.endpoint=${LocalStackComposer.STORE_GATE_ENDPOINT}",
                "-blocks-storage.s3.insecure=true",
                "-blocks-storage.s3.bucket-lookup-type=path",
                "-blocks-storage.s3.access-key-id=$PLACEHOLDER_KEY",
                "-blocks-storage.s3.secret-access-key=$PLACEHOLDER_KEY",
            )

    override val lokiFlags: List<String>
        get() =
            listOf(
                "-common.storage.s3.endpoint=${LocalStackComposer.STORE_GATE_ENDPOINT}",
                "-common.storage.s3.insecure=true",
                "-common.storage.s3.force-path-style=true",
                "-common.storage.s3.access-key-id=$PLACEHOLDER_KEY",
                "-common.storage.s3.secret-access-key=$PLACEHOLDER_KEY",
            )

    override fun lokiConfig(clusterYaml: String): String = clusterYaml

    override fun tempoConfig(clusterYaml: String): String = TempoLocalConfig.live(clusterYaml, LocalStackComposer.STORE_GATE_ENDPOINT)
}

/**
 * An export's copy of the store, on disk beside `compose.yml`, read with filesystem backends and no
 * network or credentials. The copy is mounted read-only at [STORE] in every backend, so no component
 * can change it: Mimir reads `mimir/<tenant>` with the zip's generated bucket index, Loki reads
 * `loki/` (its schema's object store switched to the filesystem, its S3 settings removed), and Tempo,
 * the single binary, lists `tempo/<tenant>` with the local backend.
 *
 * @property offline the data directory and the home tenant.
 */
internal class ExportedFiles(
    private val offline: LocalStackSpec.Mode.Offline,
) : BackendStorage {
    companion object {
        /** Where every backend sees the export's data directory. */
        const val STORE = "/store"

        /** What the configuration files expand for a bucket and a region that do not exist offline. */
        private const val NO_BUCKET = "offline"
    }

    override val environment: Map<String, String> get() = backendEnvironment(bucket = NO_BUCKET, region = NO_BUCKET, home = offline.home)

    override val dependsOn: Map<String, ComposeDependency> get() = emptyMap()

    override val mounts: List<ComposeMount> get() = listOf(ComposeMount.bind("./${offline.dataDir}", STORE, readOnly = true))

    override val mimirFlags: List<String>
        get() = listOf("-blocks-storage.backend=filesystem", "-blocks-storage.filesystem.dir=$STORE")

    override val lokiFlags: List<String> get() = emptyList()

    override fun lokiConfig(clusterYaml: String): String =
        YamlDocument.patch(
            clusterYaml,
            listOf(
                YamlPatch.remove("common.storage.s3"),
                YamlPatch.set("common.storage.filesystem.chunks_directory", STORE),
                YamlPatch.set("common.storage.filesystem.rules_directory", "/loki/rules"),
                YamlPatch.set("storage_config.filesystem.directory", STORE),
                YamlPatch.set("schema_config.configs.0.object_store", "filesystem"),
            ),
        )

    override fun tempoConfig(clusterYaml: String): String =
        TempoLocalConfig.offline(clusterYaml, "$STORE/${Constants.Observability.TRACES_ROOT}")
}

/** The stack's cluster name and experiment, which the cluster files expand but no reader uses. */
private const val LOCAL_CLUSTER = "local"

/** What the cluster files expand: the bucket, its region, each backend's root, and the home tenant. */
internal fun backendEnvironment(
    bucket: String,
    region: String,
    home: String,
): Map<String, String> =
    mapOf(
        "S3_BUCKET" to bucket,
        "AWS_REGION" to region,
        "METRICS_S3_PREFIX" to Constants.Observability.METRICS_ROOT,
        "LOGS_S3_PREFIX" to Constants.Observability.LOGS_ROOT,
        "TRACES_S3_PREFIX" to Constants.Observability.TRACES_ROOT,
        "TENANT" to home,
        "CLUSTER_NAME" to LOCAL_CLUSTER,
        "EXPERIMENT" to LOCAL_CLUSTER,
    )
