package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.access.PodTarget
import java.io.File

/**
 * The Cassandra pod a single-endpoint CQL session forwards to, and its datacenter.
 *
 * On a cluster reached by port-forward, Cassandra runs as the `cassandra-pods` kit's StatefulSet.
 * Its first ordinal always exists while the StatefulSet runs (pods start in order), so the session
 * forwards to it, in the namespace the installed kit's `runtime` declares. The datacenter is the one
 * the kit was installed with, read from the resolved args the install recorded in the workspace.
 *
 * @property pod the pod and native port to forward to
 * @property datacenter the pod's datacenter, the driver's local datacenter
 */
data class CassandraPodTarget(
    val pod: PodTarget,
    val datacenter: String,
) {
    companion object {
        /** The kit that runs Cassandra as a StatefulSet. */
        const val KIT = "cassandra-pods"
        private const val FIRST_ORDINAL = 0
        private const val DC_VARIABLE = "CASSANDRA_DC"
        private const val DEFAULT_DC = "dc1"

        /**
         * The target of the `cassandra-pods` kit installed in [workspace].
         *
         * @throws IllegalStateException when the kit is not installed there
         */
        fun inWorkspace(workspace: File): CassandraPodTarget {
            val kitDir = File(workspace, KIT)
            check(kitDir.isDirectory) {
                "No '$KIT' kit is installed in ${workspace.absolutePath}; CQL on a port-forwarded cluster talks to its pods"
            }
            val datacenter = resolvedArgs(File(kitDir, Constants.Kit.RESOLVED_ARGS_FILE))[DC_VARIABLE]?.ifBlank { null }
            return CassandraPodTarget(
                pod = PodTarget(namespace(kitDir), "$KIT-$FIRST_ORDINAL", Constants.Kit.CassandraPods.CQL_NODE_PORT),
                datacenter = datacenter ?: DEFAULT_DC,
            )
        }

        /**
         * The namespace the installed kit's `runtime` declares; [KitRuntime.namespace]'s default when
         * its `kit.yaml` declares no runtime or is absent.
         */
        private fun namespace(kitDir: File): String {
            val config = File(kitDir, Constants.Kit.CONFIG_FILE)
            val runtime =
                if (config.isFile) installConfigYaml.decodeFromString(KitConfig.serializer(), config.readText()).runtime else null
            return runtime?.namespace ?: KitRuntime(KitRuntime.RuntimeType.PODS).namespace
        }

        private fun resolvedArgs(file: File): Map<String, String> =
            if (!file.isFile) {
                emptyMap()
            } else {
                file
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            }
    }
}
