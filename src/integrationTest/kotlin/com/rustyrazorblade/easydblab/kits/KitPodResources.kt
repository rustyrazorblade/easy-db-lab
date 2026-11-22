package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.Constants

/**
 * The resources the `cassandra-artifact` contributor gives the cassandra-pods templates on a
 * cluster whose pods are not Guaranteed, such as a K3s container: the default `--memory`, one
 * CPU requested and no CPU limit, and nothing on the init containers. Integration tests fill the
 * templates with these, as `lib/apply-statefulset.sh` does at start.
 */
object KitPodResources {
    private const val MEMORY = Constants.Kit.CassandraPods.DEFAULT_MEMORY

    val LOCAL: Map<String, String> =
        mapOf(
            "EDL_CASSANDRA_RESOURCES" to """{"requests":{"cpu":"1","memory":"$MEMORY"},"limits":{"memory":"$MEMORY"}}""",
            "EDL_INIT_RESOURCES" to "{}",
        )
}
