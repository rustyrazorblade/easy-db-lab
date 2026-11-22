package com.rustyrazorblade.easydblab.services.cassandraversions

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraConfigPatch
import com.rustyrazorblade.easydblab.configuration.cassandra.CassandraPlacement
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.services.KitContribution
import com.rustyrazorblade.easydblab.services.KitPlacementVariables
import com.rustyrazorblade.easydblab.services.KitVariableContributor
import com.rustyrazorblade.easydblab.services.RoleNodes
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The cassandra-pods kit's `cassandra-artifact` contributor: what its `start` and `upgrade` need from
 * the CLI before any resource exists.
 *
 * - The artifact: `--version` or `--build` (and `--java`) resolved by [CassandraTarballResolver] to
 *   the `EDL_TARBALL_*` variables, `EDL_CASSANDRA_BUILD` and the `cassandra-runtime` tag of the JDK,
 *   also written to [Constants.Kit.CassandraPods.ARTIFACT_FILE]. It reports what it resolved as
 *   [Event.Cassandra.TarballResolved], so `start` names what it runs as `upgrade` names from and to. `--image` skips all of it.
 * - The config inputs: the workspace `cassandra.patch.yaml` (or the common patch where the
 *   workspace has none, as off AWS), the pods' placement patch and `cassandra-rackdc.properties`.
 * - The sizing defaults: `CASSANDRA_MEMORY` and `CASSANDRA_CPUS` when not given, from the smallest
 *   db node and whether pods are Guaranteed (design D8).
 */
class CassandraArtifactContributor(
    private val tarballs: CassandraTarballResolver,
    private val roleNodes: RoleNodes,
    private val eventBus: EventBus,
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
        eventBus.emit(
            Event.Cassandra.TarballResolved(
                build = artifact.build,
                sha256 = artifact.sha256.hex,
                source = artifact.source,
                runtimeTag = runtimeTag(artifact),
            ),
        )
        val artifactVariables = artifactVariables(artifact)
        val configFiles = configFiles(args, state, kitDir)
        return KitContribution(
            variables = artifactVariables + sizing + (CONFIG_HASH to configHash(artifactVariables, configFiles, kitDir)),
            files = configFiles + (Constants.Kit.CassandraPods.ARTIFACT_FILE to envFile(artifactVariables)),
        )
    }

    /**
     * The pod template's `easydblab/config-hash`: the ConfigMap's config inputs and scripts
     * (`config/` in the kit directory) and the artifact, so a change to any rolls the pods and a
     * start with none rolls nothing. The URLs are left out: a catalog build is signed again on
     * every start, and the sha256 already names the tarball.
     */
    private fun configHash(
        artifact: Map<String, String>,
        configFiles: Map<String, String>,
        kitDir: File,
    ): String {
        val scripts =
            File(kitDir, CONFIG_DIR)
                .listFiles()
                .orEmpty()
                .filter { it.isFile }
                .associate { "$CONFIG_DIR/${it.name}" to it.readText() }
        val hashed = artifact.filterKeys { it !in URL_VARIABLES } + configFiles + scripts
        val digest = MessageDigest.getInstance("SHA-256")
        hashed.toSortedMap().forEach { (key, value) ->
            digest.update("$key\u0000$value\u0000".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(CONFIG_HASH_LENGTH)
    }

    private fun artifactVariables(artifact: TarballArtifact): Map<String, String> =
        mapOf(
            "EDL_TARBALL_URL" to artifact.url,
            "EDL_TARBALL_FALLBACK_URL" to artifact.fallbackUrl.orEmpty(),
            "EDL_TARBALL_SHA256" to artifact.sha256.hex,
            "EDL_CASSANDRA_BUILD" to artifact.build,
            "EDL_RUNTIME_IMAGE" to "${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REPOSITORY}:${runtimeTag(artifact)}",
        )

    /** The `cassandra-runtime` tag of the artifact's JDK. */
    private fun runtimeTag(artifact: TarballArtifact): String = "${Constants.Kit.CassandraPods.RUNTIME_IMAGE_REVISION}-jre${artifact.java}"

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
        val requests = mapOf("cpu" to cpus, "memory" to memory)
        val cassandra =
            when (placement.guaranteedPods) {
                true -> mapOf("requests" to requests, "limits" to requests)
                false -> mapOf("requests" to requests, "limits" to mapOf("memory" to memory))
            }
        // A pod is Guaranteed only when every container, init containers included, sets equal requests and limits.
        val init =
            when (placement.guaranteedPods) {
                true -> mapOf("requests" to INIT_RESOURCES, "limits" to INIT_RESOURCES)
                false -> emptyMap()
            }
        return mapOf(
            MEMORY to memory,
            CPUS to cpus,
            "EDL_CASSANDRA_RESOURCES" to Json.encodeToString(cassandra),
            "EDL_INIT_RESOURCES" to Json.encodeToString(init),
        )
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
        const val CONFIG_HASH = "EDL_CONFIG_HASH"
        const val CONFIG_HASH_LENGTH = 16
        const val CONFIG_DIR = "config"
        val URL_VARIABLES = setOf("EDL_TARBALL_URL", "EDL_TARBALL_FALLBACK_URL")

        /** Each init container's request and limit on Guaranteed nodes: they copy files and exit. */
        val INIT_RESOURCES =
            mapOf(
                "cpu" to Constants.Kit.CassandraPods.INIT_CONTAINER_CPU,
                "memory" to Constants.Kit.CassandraPods.INIT_CONTAINER_MEMORY,
            )
    }
}
