package com.rustyrazorblade.easydblab.containers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * Tests that [BuilderCleaner] reports a builder as possibly still running whenever it cannot prove
 * otherwise (a failed listing or terminate), terminates every builder it finds, and keeps them only
 * when asked: the answer decides whether a second builder may launch.
 */
class BuilderCleanerTest {
    /** Packer VPC instances whose listing and terminate can be made to fail. */
    private class FakeInstances(
        private val listed: Result<List<String>>,
        private val terminateError: String? = null,
    ) : BuilderInstances {
        val terminated = mutableListOf<List<String>>()

        override fun list(): List<String> = listed.getOrThrow()

        override fun terminate(instanceIds: List<String>) {
            terminateError?.let { error(it) }
            terminated.add(instanceIds)
        }
    }

    /** One cleanup scenario and what it must answer. */
    data class Case(
        val name: String,
        val listed: Result<List<String>>,
        val terminateError: String?,
        val keepUp: Boolean,
        val expected: BuilderCleanup,
        val expectedTerminated: List<List<String>>,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val two = listOf("i-1", "i-2")

        @JvmStatic
        fun cases(): Stream<Case> =
            Stream.of(
                Case("no packer VPC, or an empty one", Result.success(emptyList()), null, false, BuilderCleanup.NotFound, emptyList()),
                Case(
                    "a failed listing may hide a running builder",
                    Result.failure(IllegalStateException("throttled")),
                    null,
                    false,
                    BuilderCleanup.LookupFailed("throttled"),
                    emptyList(),
                ),
                Case(
                    "one builder is terminated",
                    Result.success(listOf("i-1")),
                    null,
                    false,
                    BuilderCleanup.Terminated(listOf("i-1")),
                    listOf(listOf("i-1")),
                ),
                Case(
                    "every builder is terminated, not only the first",
                    Result.success(two),
                    null,
                    false,
                    BuilderCleanup.Terminated(two),
                    listOf(two),
                ),
                Case(
                    "a refused terminate names every builder",
                    Result.success(two),
                    "denied",
                    false,
                    BuilderCleanup.TerminateFailed(two, "denied"),
                    emptyList(),
                ),
                Case("keep-on-error keeps every builder", Result.success(two), null, true, BuilderCleanup.Kept(two), emptyList()),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a cleanup is resolved only when no builder can still be running`(case: Case) {
        val instances = FakeInstances(case.listed, case.terminateError)
        val inspected = mutableListOf<List<String>>()

        val result = BuilderCleaner(instances).cleanUp(case.keepUp) { inspected.add(it) }

        assertThat(result).isEqualTo(case.expected)
        assertThat(instances.terminated).`as`("terminate calls").isEqualTo(case.expectedTerminated)
        val found = case.listed.getOrDefault(emptyList())
        assertThat(inspected).`as`("builders inspected before teardown").isEqualTo(listOfNotNull(found.takeIf { it.isNotEmpty() }))
    }

    @ParameterizedTest(name = "keepUp={0}")
    @ValueSource(booleans = [false, true])
    fun `a failed inspection still keeps or terminates every builder`(keepUp: Boolean) {
        val two = listOf("i-1", "i-2")
        val instances = FakeInstances(Result.success(two))

        val result = BuilderCleaner(instances).cleanUp(keepUp) { error("ssh refused") }

        assertThat(result).isEqualTo(if (keepUp) BuilderCleanup.Kept(two) else BuilderCleanup.Terminated(two))
        assertThat(instances.terminated).isEqualTo(if (keepUp) emptyList() else listOf(two))
    }
}
