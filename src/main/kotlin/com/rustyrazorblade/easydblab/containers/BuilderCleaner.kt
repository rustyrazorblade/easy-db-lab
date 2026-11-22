package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.messageOrString
import com.rustyrazorblade.easydblab.runCatchingInterruptibly

/** One build instance of an AMI build: its id, and its public address when it has one. */
data class BuilderInstance(
    val id: String,
    val publicIp: String?,
)

/**
 * The build instances one AMI build left behind: the live instances in the build region's packer
 * VPC that carry this build's id. A port, so the cleanup decision is tested without a cloud; an
 * adapter is bound to one build's region and id (see [BuildSite.builders]).
 */
interface BuilderInstances {
    /** This build's live instances; empty when the packer VPC or such an instance does not exist. */
    fun list(): List<BuilderInstance>

    /** Terminates [instanceIds]; throws when the provider refuses. */
    fun terminate(instanceIds: List<String>)
}

/**
 * Tears down (or keeps) a failed AMI build attempt's builders and says what became of them.
 *
 * Its answer decides whether the build may run again (see [PackerBuildAttempts]): a failed lookup or
 * terminate is [BuilderCleanup.Unresolved], because a builder may still be running and a new
 * attempt must not launch beside it. Every instance of the build is terminated, not only the
 * first, and no instance of another build is touched: [instances] lists only this build's.
 */
class BuilderCleaner(
    private val instances: BuilderInstances,
) {
    /**
     * @param plan whether to inspect the builders first, and whether to keep them for
     *   --keep-on-error instead of terminating them
     * @param inspect called with the builders found, before they are kept or terminated
     *   (diagnostics), when the plan asks for it; a throw from it is passed to [inspectFailed]
     *   and the builders are still kept or terminated
     * @param inspectFailed told why the inspection failed
     */
    fun cleanUp(
        plan: CleanupPlan,
        inspect: (builders: List<BuilderInstance>) -> Unit,
        inspectFailed: (reason: String) -> Unit,
    ): BuilderCleanup {
        val builders =
            runCatchingInterruptibly { instances.list() }
                .getOrElse { return BuilderCleanup.LookupFailed(it.messageOrString()) }
        if (builders.isEmpty()) return BuilderCleanup.NotFound
        // Diagnostics never stop the teardown: a builder left up would block every retry.
        if (plan.inspect) {
            runCatchingInterruptibly { inspect(builders) }.onFailure { inspectFailed(it.messageOrString()) }
        }
        val instanceIds = builders.map { it.id }
        if (plan.keep) return BuilderCleanup.Kept(instanceIds)
        return runCatchingInterruptibly { instances.terminate(instanceIds) }.fold(
            onSuccess = { BuilderCleanup.Terminated(instanceIds) },
            onFailure = { BuilderCleanup.TerminateFailed(instanceIds, it.messageOrString()) },
        )
    }
}
