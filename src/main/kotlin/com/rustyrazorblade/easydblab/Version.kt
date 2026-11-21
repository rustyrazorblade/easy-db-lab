package com.rustyrazorblade.easydblab

/**
 * Represents a Cassandra version
 *
 * @param path The full path to the Cassandra installation
 */
data class Version(
    val path: String,
) {
    /**
     * Gets just the version component from the path
     */
    val versionString: String = path.substringAfterLast("/")
    val conf: String = "$path/conf"

    companion object {
        /**
         * Create a Version from a version string (e.g. "5.0")
         *
         * @param version The version string (e.g. "5.0")
         * @return A Version instance with the appropriate path
         */
        fun fromString(version: String): Version = Version("/usr/local/cassandra/$version")

        fun fromRemotePath(path: String): Version = Version(path)
    }
}
