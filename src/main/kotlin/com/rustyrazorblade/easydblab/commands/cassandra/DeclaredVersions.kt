package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CassandraVersion
import java.io.File

private const val DECLARED_VERSIONS_PATH = "cassandra/cassandra_versions.yaml"

/**
 * The Cassandra versions an AMI bake would resolve: the packaged cassandra_versions.yaml merged
 * with any yaml files in the user's profile `cassandra_versions` extras directory.
 *
 * Empty when the packaged file is not on disk — without it there is no declared set, only whatever
 * a caller names explicitly.
 */
internal fun declaredCassandraVersions(context: Context): List<CassandraVersion> {
    val mainFile = File(context.packerHome, DECLARED_VERSIONS_PATH)
    if (!mainFile.exists()) {
        return emptyList()
    }
    return CassandraVersion.loadFromMainAndExtras(
        mainFile.toPath(),
        context.cassandraVersionsExtra.toPath(),
    )
}
