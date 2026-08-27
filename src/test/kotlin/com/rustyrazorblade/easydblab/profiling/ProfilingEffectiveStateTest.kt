package com.rustyrazorblade.easydblab.profiling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Kotlin half of the effective-state field contract.
 *
 * The reconciler writes this document in bash on a node and this class reads it in Kotlin on a
 * developer's machine; the two tiers never share a process, so nothing forces the names to agree.
 * The failure that leaves is silent — `profilingJson` sets `ignoreUnknownKeys = true` and every
 * field carries a default, so a renamed key simply yields the default. Renaming `updatedAt` alone
 * makes every node report `Unknown` freshness, which turns off the staleness banner and the
 * `Event.Profiling.StateStale` event entirely, leaving a dead reconciler rendering as a healthy one.
 *
 * The pin is [PROFILING_EFFECTIVE_STATE_KEYS], asserted here against the serializer and asserted in
 * `edl-profiling-reconcile.test.sh` against the document the reconciler actually writes. A rename
 * therefore has to break a test on one side or the other.
 */
class ProfilingEffectiveStateTest {
    @Test
    fun `the declared key list is exactly what the parser reads`() {
        val descriptor = ProfilingEffectiveState.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

        assertThat(fields)
            .describedAs("the shared key list and the data class must not drift apart")
            .isEqualTo(PROFILING_EFFECTIVE_STATE_KEYS)
    }

    @Test
    fun `every declared key reaches its field rather than falling back to a default`() {
        // Built from the shared list, so a field renamed on the Kotlin side without updating the
        // list fails here, and one renamed in both fails in the bash tier instead.
        val document = PROFILING_EFFECTIVE_STATE_KEYS.joinToString(",", "{", "}") { key -> "\"$key\": ${sampleValueFor(key)}" }

        val state = parseProfilingEffectiveState(document)

        requireNotNull(state) { "the document built from the declared key list must parse" }
        assertThat(state.running).isTrue()
        assertThat(state.desiredEnabled).isTrue()
        assertThat(state.pid).isEqualTo(4242)
        assertThat(state.args).containsExactly("-e", "cpu")
        assertThat(state.loopInterval).isEqualTo("30s")
        assertThat(state.retentionMinutes).isEqualTo(90)
        assertThat(state.maxBytes).isEqualTo(777)
        assertThat(state.pyroscopeUrl).isEqualTo("http://10.0.1.5:4040")
        assertThat(state.clusterName).isEqualTo("test-cluster")
        assertThat(state.startedAt).isEqualTo(1_755_999_000)
        assertThat(state.chunksPending).isEqualTo(3)
        assertThat(state.chunksShipped).isEqualTo(11)
        assertThat(state.chunksRejected).isEqualTo(2)
        assertThat(state.shipFailures).isEqualTo(5)
        assertThat(state.prunedForAge).isEqualTo(13)
        assertThat(state.prunedForSize).isEqualTo(7)
        assertThat(state.prunedUnshipped).isEqualTo(4)
        assertThat(state.bytesOnDisk).isEqualTo(999)
        assertThat(state.lastError).isEqualTo("http_500")
        assertThat(state.attachFailures).isEqualTo(6)
        assertThat(state.lastAttachError).isEqualTo("Could not open /tmp/.java_pid4242")
        assertThat(state.attachDeferred).isTrue()
        assertThat(state.configError).isEqualTo("config_unreadable")
        assertThat(state.updatedAt).isEqualTo(1_756_000_000)
    }

    @Test
    fun `a node reporting a corrupt configuration is distinguishable from one reporting nothing`() {
        assertThat(ProfilingEffectiveState(configError = "config_unreadable").configUnreadable).isTrue()
        assertThat(ProfilingEffectiveState().configUnreadable).isFalse()
    }

    @Test
    fun `a node waiting for its database is not reported as one that cannot attach`() {
        // Both have profiling enabled and nothing running, which is why they used to be one state.
        // They are not: a deferred attach is the reconciler declining to signal a database that is
        // still starting, and it clears itself; a failed attach does not.
        val waiting = ProfilingEffectiveState(desiredEnabled = true, running = false, attachDeferred = true)
        val failing = ProfilingEffectiveState(desiredEnabled = true, running = false)

        assertThat(waiting.attachFailed).isFalse()
        assertThat(failing.attachFailed).isTrue()
    }

    /** A distinct, non-default JSON value per key, so a field that failed to bind is visible. */
    private val samples =
        mapOf(
            "running" to "true",
            "desiredEnabled" to "true",
            "pid" to "4242",
            "args" to """["-e","cpu"]""",
            "loopInterval" to """"30s"""",
            "retentionMinutes" to "90",
            "maxBytes" to "777",
            "pyroscopeUrl" to """"http://10.0.1.5:4040"""",
            "clusterName" to """"test-cluster"""",
            "startedAt" to "1755999000",
            "chunksPending" to "3",
            "chunksShipped" to "11",
            "chunksRejected" to "2",
            "shipFailures" to "5",
            "prunedForAge" to "13",
            "prunedForSize" to "7",
            "prunedUnshipped" to "4",
            "bytesOnDisk" to "999",
            "lastError" to """"http_500"""",
            "attachFailures" to "6",
            "lastAttachError" to """"Could not open /tmp/.java_pid4242"""",
            "attachDeferred" to "true",
            "configError" to """"config_unreadable"""",
            "updatedAt" to "1756000000",
        )

    private fun sampleValueFor(key: String): String =
        samples[key] ?: error("no sample value for the new key '$key'; add one here and assert it above")
}
