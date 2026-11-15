package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.aws.DiscoveredResources
import com.rustyrazorblade.easydblab.providers.aws.TeardownMode
import com.rustyrazorblade.easydblab.providers.aws.TeardownResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * [TeardownPlanner] picks the mode and takes every mode through the same preview, dry-run and
 * confirmation steps. The operator's answer is the only injected input.
 */
class TeardownPlannerTest {
    private val emitted = mutableListOf<Event>()
    private val eventBus =
        EventBus().apply {
            addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }
    private var answers = mutableListOf<String>()
    private val planner = TeardownPlanner(eventBus) { answers.removeAt(0) }

    private val found = TeardownResult.success(DiscoveredResources(vpcId = "vpc-1", vpcName = "lab"))

    @BeforeEach
    fun reset() {
        emitted.clear()
        answers = mutableListOf()
    }

    private fun review(
        options: TeardownOptions,
        preview: TeardownResult = found,
        beforeDryRunCheck: () -> Unit = {},
    ) = planner.review(options, preview, Event.Teardown.NoResourcesFound, planner::summarizeAll, beforeDryRunCheck)

    @Test
    fun `a VPC id selects that VPC even when other flags are set`() {
        val mode = planner.modeFor(TeardownOptions(vpcId = "vpc-9", all = true, packer = true))

        assertThat(mode).isEqualTo(TeardownMode.SpecificVpc("vpc-9"))
    }

    @Test
    fun `all beats packer, packer beats the current cluster`() {
        assertThat(planner.modeFor(TeardownOptions(all = true, packer = true))).isEqualTo(TeardownMode.AllTagged)
        assertThat(planner.modeFor(TeardownOptions(packer = true))).isEqualTo(TeardownMode.PackerInfrastructure)
        assertThat(planner.modeFor(TeardownOptions())).isEqualTo(TeardownMode.CurrentCluster)
    }

    @Test
    fun `a preview that found nothing is reported and returned`() {
        val empty = TeardownResult.success(emptyList())

        assertThat(review(TeardownOptions(), preview = empty)).isEqualTo(PlanReview.NothingFound(empty))
        assertThat(emitted).containsExactly(Event.Teardown.NoResourcesFound)
    }

    @Test
    fun `on a dry run a preview that found nothing stops there`() {
        val empty = TeardownResult.success(emptyList())

        assertThat(review(TeardownOptions(dryRun = true), preview = empty)).isEqualTo(PlanReview.DryRunReported)
        assertThat(emitted).containsExactly(Event.Teardown.NoResourcesFound)
    }

    @Test
    fun `a dry run shows the summary, runs the pre-check first, and never asks`() {
        var checked = false

        val review = review(TeardownOptions(dryRun = true), beforeDryRunCheck = { checked = true })

        assertThat(review).isEqualTo(PlanReview.DryRunReported)
        assertThat(checked).isTrue()
        assertThat(emitted.single()).isInstanceOf(Event.Teardown.DryRunPreview::class.java)
    }

    @Test
    fun `auto-approve goes ahead without asking`() {
        assertThat(review(TeardownOptions(autoApprove = true))).isEqualTo(PlanReview.Proceed)

        assertThat(emitted).isEmpty()
    }

    @ParameterizedTest
    @CsvSource("yes,true", "y,true", "' YES ',true", "no,false", "n,false", "maybe,false", "'',false")
    fun `only yes or y confirms`(
        answer: String,
        proceeds: Boolean,
    ) {
        answers.add(answer)

        val review = review(TeardownOptions())

        assertThat(review).isEqualTo(if (proceeds) PlanReview.Proceed else PlanReview.Declined)
        assertThat(emitted.first()).isInstanceOf(Event.Teardown.ConfirmationPrompt::class.java)
        assertThat(emitted.contains(Event.Teardown.CancelledByUser)).isEqualTo(!proceeds)
    }

    @Test
    fun `several VPC summaries are joined in order`() {
        val summary =
            planner.summarizeAll(
                listOf(DiscoveredResources(vpcId = "vpc-1", vpcName = "a"), DiscoveredResources(vpcId = "vpc-2", vpcName = "b")),
            )

        assertThat(summary.indexOf("vpc-1")).isLessThan(summary.indexOf("vpc-2")).isGreaterThanOrEqualTo(0)
    }
}
