package com.rustyrazorblade.easydblab.configuration.cassandra

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Builds the cassandra.yaml patches both Cassandra paths merge onto a version's original config
 * (with `edl_merge_cassandra_yaml`, in that order: common, then placement).
 *
 * Split in two so the VM path and the cassandra-pods kit agree by construction: [common] is what
 * both share (and what the user tunes in the workspace `cassandra.patch.yaml`); [placement] is the
 * only source of the keys that differ between them.
 */
object CassandraConfigPatch {
    /** The keys both paths share: the cluster name, token count, concurrency and `trickle_fsync`. */
    fun common(
        clusterName: String,
        tokens: Int,
    ): CassandraPatch =
        CassandraPatch(
            clusterName = clusterName,
            numTokens = tokens,
            concurrentReads = Constants.CassandraConfig.CONCURRENT_READS,
            concurrentWrites = Constants.CassandraConfig.CONCURRENT_WRITES,
            trickleFsync = true,
        )

    /** The keys that depend on where Cassandra runs: seeds, directories, the snitch and, for pods, the native port. */
    fun placement(placement: CassandraPlacement): CassandraPatch =
        when (placement) {
            is CassandraPlacement.Vm ->
                directories(placement.dataRoot).copy(
                    seedProvider = SeedProvider.of(placement.seedIps.joinToString(",")),
                    endpointSnitch = placement.snitch,
                )
            is CassandraPlacement.Pods ->
                directories(placement.dataRoot).copy(
                    seedProvider = SeedProvider.of(placement.seedDns),
                    savedCachesDirectory = "${placement.dataRoot}/saved_caches",
                    endpointSnitch = Constants.CassandraConfig.POD_SNITCH,
                    nativeTransportPort = placement.nativePort,
                )
        }

    private fun directories(root: String) =
        CassandraPatch(
            hintsDirectory = "$root/hints",
            dataFileDirectories = listOf("$root/data"),
            commitlogDirectory = "$root/commitlog",
        )
}

/**
 * One cassandra.yaml patch: only the keys it sets are written, so it can be merged onto any
 * version's original config without touching anything else. [plus] combines two the way the yq
 * merge does, the right-hand patch winning a key both set.
 */
@Serializable
data class CassandraPatch(
    @SerialName("cluster_name") val clusterName: String? = null,
    @SerialName("num_tokens") val numTokens: Int? = null,
    @SerialName("seed_provider") val seedProvider: SeedProvider? = null,
    @SerialName("hints_directory") val hintsDirectory: String? = null,
    @SerialName("data_file_directories") val dataFileDirectories: List<String>? = null,
    @SerialName("commitlog_directory") val commitlogDirectory: String? = null,
    @SerialName("saved_caches_directory") val savedCachesDirectory: String? = null,
    @SerialName("concurrent_reads") val concurrentReads: Int? = null,
    @SerialName("concurrent_writes") val concurrentWrites: Int? = null,
    @SerialName("trickle_fsync") val trickleFsync: Boolean? = null,
    @SerialName("endpoint_snitch") val endpointSnitch: String? = null,
    @SerialName("native_transport_port") val nativeTransportPort: Int? = null,
) {
    /** This patch with every key [other] sets replaced by [other]'s value. */
    operator fun plus(other: CassandraPatch): CassandraPatch =
        CassandraPatch(
            clusterName = other.clusterName ?: clusterName,
            numTokens = other.numTokens ?: numTokens,
            seedProvider = other.seedProvider ?: seedProvider,
            hintsDirectory = other.hintsDirectory ?: hintsDirectory,
            dataFileDirectories = other.dataFileDirectories ?: dataFileDirectories,
            commitlogDirectory = other.commitlogDirectory ?: commitlogDirectory,
            savedCachesDirectory = other.savedCachesDirectory ?: savedCachesDirectory,
            concurrentReads = other.concurrentReads ?: concurrentReads,
            concurrentWrites = other.concurrentWrites ?: concurrentWrites,
            trickleFsync = other.trickleFsync ?: trickleFsync,
            endpointSnitch = other.endpointSnitch ?: endpointSnitch,
            nativeTransportPort = other.nativeTransportPort ?: nativeTransportPort,
        )

    /** The patch as YAML, with unset keys left out. */
    fun toYaml(): String = YAML.encodeToString(serializer(), this)

    private companion object {
        val YAML = Yaml(configuration = YamlConfiguration(encodeDefaults = false))
    }
}

/** cassandra.yaml's `seed_provider`, in the map form `write-config` has always written. */
@Serializable
data class SeedProvider(
    @SerialName("class_name") val className: String,
    val parameters: SeedParameters,
) {
    companion object {
        /** The SimpleSeedProvider with [seeds], a comma-separated list of addresses or names. */
        fun of(seeds: String) = SeedProvider(Constants.CassandraConfig.SEED_PROVIDER_CLASS, SeedParameters(seeds))
    }
}

/** The SimpleSeedProvider's one parameter. */
@Serializable
data class SeedParameters(
    val seeds: String,
)
