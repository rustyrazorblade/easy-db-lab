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
