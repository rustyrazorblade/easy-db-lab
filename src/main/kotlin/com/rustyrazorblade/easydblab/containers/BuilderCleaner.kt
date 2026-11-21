package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingException

/**
 * The build instances an AMI build may have left behind: every instance in the packer VPC, which
 * holds nothing but builders. A port, so the cleanup decision is tested without a cloud.
 */
interface BuilderInstances {
    /** Every instance in the packer VPC; empty when that VPC does not exist. */
    fun list(): List<String>

    /** Terminates [instanceIds]; throws when the provider refuses. */
    fun terminate(instanceIds: List<String>)
}

/**
 * Tears down (or keeps) a failed AMI build attempt's builders and says what became of them.
 *
 * Its answer decides whether the build may run again (see [PackerBuildAttempts]): a failed lookup or
 * terminate is [BuilderCleanup.Unresolved], because a builder may still be running and a new
 * attempt must not launch beside it. Every instance found is terminated, not only the first, so a
 * builder left by an earlier run cannot hide the current one.
 */
class BuilderCleaner(
    private val instances: BuilderInstances,
) {
    /**
     * @param keepUp leave the builders running for --keep-on-error instead of terminating them
     * @param inspect called with the builders found, before they are kept or terminated (diagnostics)
     */
    fun cleanUp(
        keepUp: Boolean,
        inspect: (instanceIds: List<String>) -> Unit,
    ): BuilderCleanup {
        val instanceIds =
            runCatchingException { instances.list() }
                .getOrElse { return BuilderCleanup.LookupFailed(it.messageOrString()) }
        if (instanceIds.isEmpty()) return BuilderCleanup.NotFound
        inspect(instanceIds)
        if (keepUp) return BuilderCleanup.Kept(instanceIds)
        return runCatchingException { instances.terminate(instanceIds) }.fold(
            onSuccess = { BuilderCleanup.Terminated(instanceIds) },
            onFailure = { BuilderCleanup.TerminateFailed(instanceIds, it.messageOrString()) },
        )
    }
}
