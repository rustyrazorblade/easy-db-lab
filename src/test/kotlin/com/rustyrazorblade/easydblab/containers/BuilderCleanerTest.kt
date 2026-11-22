package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests that [BuilderCleaner] reports a builder as possibly still running whenever it cannot prove
 * otherwise (a failed listing or terminate), inspects only when the plan asks and always before the
 * teardown, terminates every builder it finds, and keeps them only when the plan says so: the answer
 * decides whether a second builder may launch.
 */
class BuilderCleanerTest {
    /** Packer VPC instances whose listing and terminate can be made to fail; every call goes to [calls]. */
    private class FakeInstances(
        private val listed: Result<List<BuilderInstance>>,
        private val terminateError: String?,
        private val calls: MutableList<String>,
    ) : BuilderInstances {
        override fun list(): List<BuilderInstance> {
            calls.add("list")
            return listed.getOrThrow()
        }

        override fun terminate(instanceIds: List<String>) {
            calls.add("terminate ${instanceIds.joinToString(",")}")
            terminateError?.let { error(it) }
        }
    }

    /** One cleanup scenario: what the cloud does, the plan, the answer and the ordered calls. */
    data class Case(
        val name: String,
        val listed: Result<List<BuilderInstance>>,
        val plan: CleanupPlan,
        val expected: BuilderCleanup,
        val expectedCalls: List<String>,
        val terminateError: String? = null,
        val inspectError: String? = null,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val one = listOf(BuilderInstance("i-1", "1.2.3.4"))
        private val two = listOf(BuilderInstance("i-1", "1.2.3.4"), BuilderInstance("i-2", null))
        private val twoIds = listOf("i-1", "i-2")
        private val retry = CleanupPlan(keep = false, inspect = false)
        private val final = CleanupPlan(keep = false, inspect = true)
        private val keep = CleanupPlan(keep = true, inspect = true)

        @JvmStatic
        fun cases(): Stream<Case> =
            Stream.of(
                Case(
                    "no packer VPC, or no builder of this build",
                    Result.success(emptyList()),
                    final,
                    BuilderCleanup.NotFound,
                    listOf("list"),
                ),
                Case(
                    "a failed listing may hide a running builder",
                    Result.failure(IllegalStateException("throttled")),
                    final,
                    BuilderCleanup.LookupFailed("throttled"),
                    listOf("list"),
                ),
                Case(
                    "a retry tears down without inspecting",
                    Result.success(one),
                    retry,
                    BuilderCleanup.Terminated(listOf("i-1")),
                    listOf("list", "terminate i-1"),
                ),
                Case(
                    "the final failure is inspected before every builder is terminated",
                    Result.success(two),
                    final,
                    BuilderCleanup.Terminated(twoIds),
                    listOf("list", "inspect i-1,i-2", "terminate i-1,i-2"),
                ),
                Case(
                    "a refused terminate names every builder",
                    Result.success(two),
                    retry,
                    BuilderCleanup.TerminateFailed(twoIds, "denied"),
                    listOf("list", "terminate i-1,i-2"),
                    terminateError = "denied",
                ),
            )

        /** Rows where the inspection is asked for: the last failed attempt, kept or not. */
        @JvmStatic
        fun inspectedCases(): Stream<Case> =
            Stream.of(
                Case(
                    "keep-on-error inspects and keeps every builder",
                    Result.success(two),
                    keep,
                    BuilderCleanup.Kept(twoIds),
                    listOf("list", "inspect i-1,i-2"),
                ),
                Case(
                    "a failed inspection is reported and the builders still terminated",
                    Result.success(two),
                    final,
                    BuilderCleanup.Terminated(twoIds),
                    listOf("list", "inspect i-1,i-2", "inspect failed: ssh refused", "terminate i-1,i-2"),
                    inspectError = "ssh refused",
                ),
                Case(
                    "a failed inspection still keeps the builders",
                    Result.success(two),
                    keep,
                    BuilderCleanup.Kept(twoIds),
                    listOf("list", "inspect i-1,i-2", "inspect failed: ssh refused"),
                    inspectError = "ssh refused",
                ),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases", "inspectedCases")
    fun `a cleanup is resolved only when no builder can still be running`(case: Case) {
        val calls = mutableListOf<String>()
        val instances = FakeInstances(case.listed, case.terminateError, calls)

        val result =
            BuilderCleaner(instances).cleanUp(
                case.plan,
                inspect = { builders ->
                    calls.add("inspect ${builders.joinToString(",") { it.id }}")
                    case.inspectError?.let { error(it) }
                },
                inspectFailed = { reason -> calls.add("inspect failed: $reason") },
            )

        assertThat(result).isEqualTo(case.expected)
        assertThat(calls).`as`("ordered calls").isEqualTo(case.expectedCalls)
    }
}
