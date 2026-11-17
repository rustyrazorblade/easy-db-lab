package com.rustyrazorblade.easydblab.services.cassandraversions

/**
 * Reads the properties of a Cassandra `build.xml` that decide which JDKs a tree runs on. The tree
 * states them exactly, so `cassandra build` and the fork drift check read them instead of guessing.
 */
object BuildXml {
    private val JAVA_SUPPORTED = Regex("""property\s+name="java\.supported"\s+value="([^"]*)"""")

    /** The JDKs `java.supported` lists, in order; empty when the tree does not declare it (4.x). */
    fun javaSupported(buildXml: String): List<String> =
        JAVA_SUPPORTED
            .find(buildXml)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}
