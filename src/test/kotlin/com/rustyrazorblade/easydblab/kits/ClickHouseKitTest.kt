package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ClickHouse `start` must not return until every replica is up. The Altinity operator creates the
 * replicas one at a time, so waiting for the pods that exist to be Ready returned after the first
 * replica while the ClickHouseInstallation was still InProgress (1/3 pods). The step now waits for
 * the installation to report Completed, then for every replica pod to be Ready. The step's script
 * runs here against a stub `kubectl` that records each call.
 */
class ClickHouseKitTest : BaseKoinTest() {
    private val kit by lazy {
        BuiltinKitFixture("clickhouse", TemplateService(ClusterStateManager(File(tempDir, "state.json")), getKoin().get()))
    }

    private val stubDir by lazy { File(tempDir, "stub").also { it.mkdirs() } }
    private val calls by lazy { File(stubDir, "kubectl.log") }

    /** The start step that follows applying the ClickHouseInstallation. */
    private fun installationWaitStep(): InstallStep.Shell {
        val start = kit.config.start
        val applied = start.indexOfFirst { it is InstallStep.Manifest && it.template == "clickhouseinstallation.yaml" }
        return start.drop(applied + 1).filterIsInstance<InstallStep.Shell>().first()
    }

    /** A `kubectl` that records its arguments and fails the calls whose arguments contain [failOn]. */
    private fun runAgainstStub(failOn: String = "<never>"): Int {
        File(stubDir, "kubectl").apply {
            writeText(
                """
                #!/bin/bash
                echo "$*" >> "${calls.absolutePath}"
                case "$*" in *"$failOn"*) exit 1 ;; esac
                exit 0
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val process =
            ProcessBuilder("bash", "-c", installationWaitStep().script)
                .directory(stubDir)
                .redirectErrorStream(true)
                .redirectOutput(File(stubDir, "script.out"))
                .also { it.environment()["PATH"] = "${stubDir.absolutePath}:${System.getenv("PATH")}" }
                .start()
        // A step that polls for pods to appear never sees any from the stub; fail instead of hanging.
        check(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "the step did not finish within ${SCRIPT_TIMEOUT_SECONDS}s; calls: ${calls.readLines()}"
        }
        return process.exitValue()
    }

    private companion object {
        const val SCRIPT_TIMEOUT_SECONDS = 20L
    }

    @Test
    fun `start waits for the installation to complete before waiting for the replica pods`() {
        assertThat(runAgainstStub()).isEqualTo(0)

        val invocations = calls.readLines()
        val completed = invocations.indexOfFirst { "wait" in it && "{.status.status}=Completed" in it && "chi/clickhouse" in it }
        val podsReady =
            invocations.indexOfFirst {
                "wait" in it && "condition=Ready" in it && "clickhouse.altinity.com/chi=clickhouse" in it
            }
        assertThat(completed).isNotNegative()
        assertThat(podsReady).isGreaterThan(completed)
    }

    @Test
    fun `start fails without waiting on pods when the installation does not complete`() {
        assertThat(runAgainstStub(failOn = "{.status.status}=Completed")).isNotEqualTo(0)

        assertThat(calls.readLines()).noneSatisfy { assertThat(it).contains("condition=Ready") }
    }
}
