package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraConfigPatch
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraPlacement
import com.rustyrazorblade.easydblab.services.KitContribution
import com.rustyrazorblade.easydblab.services.KitPlacementVariables
import com.rustyrazorblade.easydblab.services.KitVariableContributor
import com.rustyrazorblade.easydblab.services.RoleNodes
import java.io.File

/**
 * The cassandra-pods kit's `cassandra-artifact` contributor: what its `start` and `upgrade` need from
 * the CLI before any resource exists.
 *
 * - The artifact: `--version` or `--build` (and `--java`) resolved by [CassandraTarballResolver] to
 *   the `EDL_TARBALL_*` variables, `EDL_CASSANDRA_BUILD` and the `cassandra-runtime` tag of the JDK,
 *   also written to [Constants.Kit.CassandraPods.ARTIFACT_FILE]. `--image` skips all of it.
 * - The config inputs: the workspace `cassandra.patch.yaml` (or the common patch where the
 *   workspace has none, as off AWS), the pods' placement patch and `cassandra-rackdc.properties`.
 * - The sizing defaults: `CASSANDRA_MEMORY` and `CASSANDRA_CPUS` when not given, from the smallest
 *   db node and whether pods are Guaranteed (design D8).
 */
class CassandraArtifactContributor(
    private val tarballs: CassandraTarballResolver,
    private val roleNodes: RoleNodes,
) : KitVariableContributor {
    override val name = Constants.Kit.Contributors.CASSANDRA_ARTIFACT

    override fun contribute(
        args: Map<String, String>,
        state: ClusterState,
        kitDir: File,
    ): KitContribution {
        val image = args.value(IMAGE)
        val build = args.value(BUILD)
        require(image == null || build == null) {
            "--build $build and --image $image cannot be used together: --image runs that image, --build runs a " +
                "cassandra-builds tarball on the cassandra-runtime image. Pass one of them."
        }
        val sizing = sizing(args, roleNodes.placement(state))
        if (image != null) return KitContribution(variables = sizing)

        val java = args.value(JAVA)
        val artifact =
            when (build) {
                null -> tarballs.resolveVersion(args.value(VERSION) ?: DEFAULT_VERSION, java, state)
                else -> tarballs.resolveBuild(build, java)
            }
        val artifactVariables = artifactVariables(artifact)
        return KitContribution(
            variables = artifactVariables + sizing,
            files = configFiles(args, state, kitDir) + (Constants.Kit.CassandraPods.ARTIFACT_FILE to envFile(artifactVariables)),
        )
    }

    private fun artifactVariables(artifact: TarballArtifact): Map<String, String> =
        mapOf(
            "EDL_TARBALL_URL" to artifact.url,
            "EDL_TARBALL_FALLBACK_URL" to artifact.fallbackUrl.orEmpty(),
            "EDL_TARBALL_SHA256" to artifact.sha256.hex,
            "EDL_CASSANDRA_BUILD" to artifact.build,
            "EDL_RUNTIME_IMAGE" to
                "${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REPOSITORY}:${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION}" +
                "-jre${artifact.java}",
        )

    private fun configFiles(
        args: Map<String, String>,
        state: ClusterState,
        kitDir: File,
    ): Map<String, String> {
        val workspacePatch = File(kitDir.absoluteFile.parentFile, Constants.ConfigPaths.CASSANDRA_PATCH_FILE)
        val common =
            when (workspacePatch.isFile) {
                true -> workspacePatch.readText()
                false -> CassandraConfigPatch.common(state.name, Constants.CassandraConfig.DEFAULT_TOKENS).toYaml() + "\n"
            }
        val placement = CassandraConfigPatch.placement(CassandraPlacement.Pods(seedDns = Constants.Kit.CassandraPods.SEED_DNS))
        val dc = args.value(DC) ?: DEFAULT_DC
        return mapOf(
            Constants.ConfigPaths.CASSANDRA_PATCH_FILE to common,
            Constants.Kit.CassandraPods.PLACEMENT_FILE to placement.toYaml() + "\n",
            Constants.Kit.CassandraPods.RACKDC_FILE to "dc=$dc\nrack=${Constants.Kit.CassandraPods.RACK}\n",
        )
    }

    /** `--memory` and `--cpus` as given, or their defaults for this cluster (design D8). */
    private fun sizing(
        args: Map<String, String>,
        placement: KitPlacementVariables,
    ): Map<String, String> {
        val allocatable = placement.dbNodeAllocatable
        val memory =
            args.value(MEMORY) ?: when (placement.guaranteedPods) {
                true -> "${allocatable.memoryMib * Constants.Kit.CassandraPods.GUARANTEED_MEMORY_PERCENT / PERCENT}Mi"
                false -> Constants.Kit.CassandraPods.DEFAULT_MEMORY
            }
        val cpus = args.value(CPUS) ?: (allocatable.cpu - 1).coerceAtLeast(1).toString()
        return mapOf(MEMORY to memory, CPUS to cpus)
    }

    /** `KEY='value'` lines a POSIX shell can source, whatever a presigned URL holds. */
    private fun envFile(variables: Map<String, String>): String =
        variables.entries.joinToString("") { (key, value) -> "$key='${value.replace("'", "'\\''")}'\n" }

    private fun Map<String, String>.value(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

    private companion object {
        const val VERSION = "CASSANDRA_VERSION"
        const val BUILD = "CASSANDRA_BUILD"
        const val JAVA = "CASSANDRA_JAVA"
        const val IMAGE = "CASSANDRA_IMAGE"
        const val MEMORY = "CASSANDRA_MEMORY"
        const val CPUS = "CASSANDRA_CPUS"
        const val DC = "CASSANDRA_DC"
        const val DEFAULT_VERSION = "5.0"
        const val DEFAULT_DC = "dc1"
        const val PERCENT = 100
    }
}
