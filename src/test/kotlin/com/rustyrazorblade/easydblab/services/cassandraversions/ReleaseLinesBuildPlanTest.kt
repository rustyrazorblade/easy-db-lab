package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The cassandra-builds nightlies are compiled on the JDK that `resolve-build-plan.sh` maps their
 * version to, and the CLI runs a nightly with no manifest only on that JDK or newer, from the
 * table's `build_java`. The two are written down separately, so this test runs the script for each
 * nightly label and compares: a nightly started below its build JDK dies with
 * `UnsupportedClassVersionError`.
 */
class ReleaseLinesBuildPlanTest {
    private val table = ReleaseLines.load()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["5.0-HEAD", "5.0-rustyrazorblade-HEAD", "6.0-HEAD", "6.0-rustyrazorblade-HEAD", "trunk"])
    fun `the table's build JDK of each nightly is the JDK the build plan compiles it on`(
        label: String,
        @TempDir dir: File,
    ) {
        val entry = table.entryFor(label)

        assertThat(entry?.buildJava).describedAs("build_java of the entry for $label").isEqualTo(buildPlanJdk(label, dir))
        assertThat(entry?.nightlyJdks()?.first()).isEqualTo(entry?.buildJava)
    }

    private fun buildPlanJdk(
        version: String,
        dir: File,
    ): String {
        val output = File(dir, "plan.out")
        val script = "set -e; . \"$PLAN\"; compute_build_plan"
        val process =
            ProcessBuilder("bash", "-c", script)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .also { builder ->
                    val env = builder.environment()
                    env["VERSION"] = version
                    env["SHORT_SHA"] = "deadbeef0000"
                    env["SOURCE_REF"] = "nightly"
                    env["JDK_OVERRIDE"] = ""
                    env["BASE_IMAGE_OVERRIDE"] = ""
                    env["REPO_OWNER"] = "o"
                    env["REPO_NAME"] = "r"
                }.start()
        check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "compute_build_plan did not finish" }
        val text = output.readText()
        check(process.exitValue() == 0) { "compute_build_plan exited ${process.exitValue()}: $text" }
        return text.lines().single { it.startsWith("build_jdk=") }.substringAfter("=")
    }

    private companion object {
        const val PLAN = ".github/cassandra-image/resolve-build-plan.sh"
        const val TIMEOUT_SECONDS = 30L
    }
}
