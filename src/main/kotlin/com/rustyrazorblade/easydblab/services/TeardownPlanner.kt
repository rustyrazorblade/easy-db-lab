package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownMode
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import java.util.Scanner

/**
 * What the operator asked `down` to do: the arguments and flags that choose the teardown mode and
 * whether it previews or asks first.
 */
data class TeardownOptions(
    val vpcId: String? = null,
    val all: Boolean = false,
    val packer: Boolean = false,
    val dryRun: Boolean = false,
    val autoApprove: Boolean = false,
)

/**
 * What the preview and confirmation decided about a teardown.
 */
sealed interface PlanReview {
    /** The teardown goes ahead. */
    data object Proceed : PlanReview

    /** The preview found nothing to remove; [preview] is that empty result. */
    data class NothingFound(
        val preview: TeardownResult,
    ) : PlanReview

    /** `--dry-run`: the preview was reported and nothing is to change. */
    data object DryRunReported : PlanReview

    /** The operator declined at the confirmation prompt. */
    data object Declined : PlanReview
}

/**
 * Plans a teardown for `down`: picks the [TeardownMode] from the options, and takes every mode
 * through the same preview, dry-run and confirmation steps before anything is removed.
 *
 * [readAnswer] reads the operator's answer to the confirmation prompt.
 */
class TeardownPlanner(
    private val eventBus: EventBus,
    private val readAnswer: () -> String = { Scanner(System.`in`).use { it.nextLine() } },
) {
    /** The teardown mode [options] select. */
    fun modeFor(options: TeardownOptions): TeardownMode =
        when {
            options.vpcId != null -> TeardownMode.SpecificVpc(options.vpcId)
            options.all -> TeardownMode.AllTagged
            options.packer -> TeardownMode.PackerInfrastructure
            else -> TeardownMode.CurrentCluster
        }

    /**
     * The steps every teardown mode takes on its [preview] before it removes anything: report
     * [noneFound] when the preview found nothing, run [beforeDryRunCheck], show the summary and
     * stop on `--dry-run`, and ask for confirmation unless auto-approved.
     */
    fun review(
        options: TeardownOptions,
        preview: TeardownResult,
        noneFound: Event.Teardown,
        summarize: (List<DiscoveredResources>) -> String,
        beforeDryRunCheck: () -> Unit = {},
    ): PlanReview {
        if (preview.resourcesDeleted.isEmpty()) {
            eventBus.emit(noneFound)
            return if (options.dryRun) PlanReview.DryRunReported else PlanReview.NothingFound(preview)
        }
        val summary = summarize(preview.resourcesDeleted)
        beforeDryRunCheck()
        return when {
            options.dryRun -> {
                eventBus.emit(Event.Teardown.DryRunPreview(summary))
                PlanReview.DryRunReported
            }
            !options.autoApprove && !confirm(summary) -> {
                eventBus.emit(Event.Teardown.CancelledByUser)
                PlanReview.Declined
            }
            else -> PlanReview.Proceed
        }
    }

    /** One summary per discovered VPC, one line each. */
    fun summarizeAll(resources: List<DiscoveredResources>): String = resources.joinToString("\n") { it.summary() }

    private fun confirm(summary: String): Boolean {
        eventBus.emit(Event.Teardown.ConfirmationPrompt(summary))
        val response = readAnswer().trim().lowercase()
        return response == "yes" || response == "y"
    }
}
