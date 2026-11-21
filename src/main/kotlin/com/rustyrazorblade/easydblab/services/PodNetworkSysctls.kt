package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants

/**
 * The per-namespace network sysctls every pod in the pod network runs with (pod-network-tuning).
 *
 * The values are the host's: they are read from the packaged node tuning sysctl file, the file node
 * setup installs on every host, so the host and the pods cannot drift. It is a classpath resource,
 * so it comes from the distribution, never from a workspace or a source checkout. Which keys pods
 * get is [Constants.NodeTuning.POD_NETWORK_SYSCTLS].
 */
object PodNetworkSysctls {
    private const val RESOURCE = "${Constants.NodeTuning.RESOURCE_DIR}/${Constants.NodeTuning.SYSCTL_FILE}"

    /**
     * The injected keys, in [Constants.NodeTuning.POD_NETWORK_SYSCTLS] order, with the packaged
     * file's values.
     *
     * @throws IllegalStateException when the file is not packaged, or lacks an injected key
     */
    fun packaged(): Map<String, String> = select(parse(packagedFile()), Constants.NodeTuning.POD_NETWORK_SYSCTLS)

    /** The packaged sysctl file's text. */
    internal fun packagedFile(): String =
        checkNotNull(
            PodNetworkSysctls::class.java.getResourceAsStream(RESOURCE),
        ) { "Node tuning file missing from the distribution: $RESOURCE" }
            .use { it.readBytes().decodeToString() }

    /** The `key = value` lines of a sysctl file, in file order; comments and blank lines are skipped. */
    internal fun parse(text: String): Map<String, String> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }

    /**
     * [keys] with their values in [file], in [keys] order.
     *
     * @throws IllegalStateException naming every key of [keys] that [file] lacks
     */
    internal fun select(
        file: Map<String, String>,
        keys: List<String>,
    ): Map<String, String> {
        val missing = keys.filterNot { it in file }
        check(missing.isEmpty()) { "The packaged sysctl file $RESOURCE lacks the pod network keys $missing" }
        return keys.associateWith { file.getValue(it) }
    }
}
