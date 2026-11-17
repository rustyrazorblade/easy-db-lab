package com.rustyrazorblade.easydblab.services.cassandraversions

/**
 * The rule every Cassandra version name follows. A name becomes the directory
 * `/usr/local/cassandra/<name>` that the node scripts download into, move and delete as root, and
 * an argument of those scripts, so it must be one path segment that cannot read as an option. The
 * node scripts `install-cassandra-version` and `use-cassandra` check the same pattern.
 */
object VersionName {
    private val PATTERN = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]*""")

    /** Why [name] is refused, or null when it is a valid name. */
    fun problem(name: String): String? =
        if (PATTERN.matches(name)) {
            null
        } else {
            "'$name' is not a valid Cassandra version name: use letters, digits, '.', '_', '+' and '-', " +
                "starting with a letter or a digit (no '/', spaces or a leading '-')."
        }
}
