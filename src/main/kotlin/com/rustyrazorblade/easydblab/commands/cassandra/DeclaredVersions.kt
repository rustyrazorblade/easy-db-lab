package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import java.io.File

private const val DECLARED_VERSIONS_PATH = "cassandra/cassandra_versions.yaml"

/**
 * The Cassandra versions an AMI bake would resolve: the packaged cassandra_versions.yaml merged
 * with any yaml files in the user's profile `cassandra_versions` extras directory.
 *
 * @throws IllegalStateException when the packaged file is not on disk
 */
internal fun declaredCassandraVersions(context: Context): List<CassandraVersion> {
    val appHome =
        checkNotNull(context.appHome) {
            "The install directory is unknown: the easydblab.apphome system property is not set. " +
                "Start easy-db-lab through its bin/easy-db-lab script."
        }
    return declaredCassandraVersions(File(appHome, "packer"), context.cassandraVersionsExtra)
}

/**
 * Reads the packaged file under [packerHome] and merges the yaml files in [extrasDir].
 *
 * A missing packaged file means the install is incomplete (the `packer/` directory was not
 * installed), so this fails with the path it looked at instead of reporting no versions.
 *
 * @throws IllegalStateException when the packaged file is not on disk
 */
internal fun declaredCassandraVersions(
    packerHome: File,
    extrasDir: File,
): List<CassandraVersion> {
    val mainFile = File(packerHome, DECLARED_VERSIONS_PATH)
    check(mainFile.exists()) {
        "The packaged Cassandra versions file is missing: $mainFile. " +
            "The packer/ directory is not installed next to the application; reinstall easy-db-lab."
    }
    return CassandraVersion.loadFromMainAndExtras(mainFile.toPath(), extrasDir.toPath())
}
