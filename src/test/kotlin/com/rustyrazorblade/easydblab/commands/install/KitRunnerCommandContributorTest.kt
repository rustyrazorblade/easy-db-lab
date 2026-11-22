package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.services.KitContribution
import com.rustyrazorblade.easydblab.services.KitVariableContributor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

/**
 * Kit variable contributors: Kotlin that a kit names in `kit.yaml` `contributors:` and the runner
 * calls before `start` and before every declared command, never before `stop` or `uninstall`.
 */
class KitRunnerCommandContributorTest : KitRunnerCommandTestBase() {
    private val contributor = FakeContributor()

    override fun additionalTestModules(): List<Module> =
        super.additionalTestModules() + module { single { contributor } bind KitVariableContributor::class }

    /** Records each call; returns [contribution], or throws [failure] when it is set. */
    class FakeContributor : KitVariableContributor {
        override val name = CONTRIBUTOR

        var contribution = KitContribution(variables = mapOf("EDL_TARBALL_SHA256" to "ab".repeat(32)))
        var failure: RuntimeException? = null
        val calls = mutableListOf<Map<String, String>>()

        override fun contribute(
            args: Map<String, String>,
            state: ClusterState,
            kitDir: File,
        ): KitContribution {
            calls += args
            failure?.let { throw it }
            return contribution
        }
    }

    private fun contributingKit(extra: String = "") {
        writeKitYaml(
            "pods",
            """
            name: pods
            contributors: [$CONTRIBUTOR]
            args:
              - flag: --version
                variable: CASSANDRA_VERSION
                default: "5.0"
              - flag: --build
                variable: CASSANDRA_BUILD
                default: ""
            """.trimIndent() + "\n" + extra,
        )
    }

    private fun recordingScript(
        phase: String,
        line: String,
    ): File {
        val out = File(workingDir, "$phase.out")
        writeScript("pods", phase, """echo "$line" > "${out.absolutePath}"""")
        return out
    }

    @Test
    fun `a contributed variable reaches the start step's environment`() {
        contributingKit()
        val out = recordingScript("start", "\$EDL_TARBALL_SHA256")

        val exit = command("pods", "start").call()

        assertThat(exit).isZero()
        assertThat(out.readText().trim()).isEqualTo("ab".repeat(32))
    }

    @Test
    fun `the contributor gets the kit's resolved args`() {
        contributingKit()
        writeResolvedArgs("pods", mapOf("CASSANDRA_VERSION" to "4.1"))
        recordingScript("start", "x")

        command("pods", "start").call()

        assertThat(contributor.calls.single()).containsEntry("CASSANDRA_VERSION", "4.1")
    }

    @Test
    fun `a contributed file is in the kit directory when the first step runs`() {
        contributor.contribution = KitContribution(files = mapOf("resolved-artifact.env" to "EDL_TARBALL_URL=https://x\n"))
        contributingKit()
        val out = File(workingDir, "start.out")
        writeScript("pods", "start", """cat "${File(workingDir, "pods").absolutePath}/resolved-artifact.env" > "${out.absolutePath}"""")

        command("pods", "start").call()

        assertThat(out.readText().trim()).isEqualTo("EDL_TARBALL_URL=https://x")
    }

    @Test
    fun `a contributor that fails stops the command before any step, with its message`() {
        contributor.failure = IllegalStateException("cassandra-builds has no build named 'nope'")
        contributingKit()
        val out = recordingScript("start", "ran")

        val events =
            captureEvents {
                assertThatThrownBy { command("pods", "start").call() }
                    .isInstanceOf(CommandFailedException::class.java)
                    .hasMessageContaining("cassandra-builds has no build named 'nope'")
            }

        assertThat(out).doesNotExist()
        val failed = events.filterIsInstance<Event.Kit.ContributorFailed>().single()
        assertThat(failed.contributor).isEqualTo(CONTRIBUTOR)
        assertThat(failed.reason).contains("no build named 'nope'")
    }

    @Test
    fun `cluster state variables win over contributed ones`() {
        contributor.contribution = KitContribution(variables = mapOf("DB_NODE_COUNT" to "99"))
        contributingKit()
        val out = recordingScript("start", "\$DB_NODE_COUNT")

        command("pods", "start").call()

        assertThat(out.readText().trim()).isEqualTo("1")
    }

    @Test
    fun `contributors run before a declared command, and not before stop`() {
        contributingKit(
            """
            commands:
              upgrade:
                args:
                  - flag: --to
                    variable: TARGET_VERSION
            """.trimIndent(),
        )
        val upgraded = recordingScript("upgrade", "\$EDL_TARBALL_SHA256")
        val stopped = recordingScript("stop", "\${EDL_TARBALL_SHA256:-none}")

        command("pods", "upgrade").call()
        command("pods", "stop").call()

        assertThat(upgraded.readText().trim()).isEqualTo("ab".repeat(32))
        assertThat(stopped.readText().trim()).isEqualTo("none")
        assertThat(contributor.calls).hasSize(1)
    }

    @Test
    fun `a declared command records its recorded args only when it succeeds`() {
        contributingKit(
            """
            commands:
              upgrade:
                records: [CASSANDRA_VERSION]
                args:
                  - flag: --version
                    variable: CASSANDRA_VERSION
            """.trimIndent(),
        )
        writeResolvedArgs("pods", mapOf("CASSANDRA_VERSION" to "5.0", "STORAGE_SIZE" to "10Gi"))
        writeScript("pods", "upgrade", "exit 3")
        val failing = command("pods", "upgrade").also { it.runtimeArgValues["CASSANDRA_VERSION"] = "4.1" }

        assertThat(failing.call()).isEqualTo(3)
        assertThat(resolvedArgs()).containsEntry("CASSANDRA_VERSION", "5.0")

        writeScript("pods", "upgrade", "exit 0")
        val succeeding = command("pods", "upgrade").also { it.runtimeArgValues["CASSANDRA_VERSION"] = "5.0.4" }

        assertThat(succeeding.call()).isZero()
        assertThat(resolvedArgs()).containsExactlyInAnyOrderEntriesOf(mapOf("CASSANDRA_VERSION" to "5.0.4", "STORAGE_SIZE" to "10Gi"))
    }

    /** An upgrade from a `--build` to a `--version` must not leave the build recorded, or the next start runs it again. */
    @Test
    fun `a command that gives one recorded arg clears the recorded args it left out`() {
        contributingKit(
            """
            commands:
              upgrade:
                records: [CASSANDRA_VERSION, CASSANDRA_BUILD]
                args:
                  - flag: --version
                    variable: CASSANDRA_VERSION
                  - flag: --build
                    variable: CASSANDRA_BUILD
            """.trimIndent(),
        )
        writeResolvedArgs("pods", mapOf("CASSANDRA_VERSION" to "5.0", "CASSANDRA_BUILD" to "5.0-HEAD"))
        writeScript("pods", "upgrade", "exit 0")
        val upgrade =
            command("pods", "upgrade").also {
                it.runtimeArgValues["CASSANDRA_VERSION"] = "5.0.4"
                it.runtimeArgValues["CASSANDRA_BUILD"] = ""
            }

        assertThat(upgrade.call()).isZero()
        assertThat(resolvedArgs()).containsEntry("CASSANDRA_VERSION", "5.0.4").containsEntry("CASSANDRA_BUILD", "")
    }

    private fun resolvedArgs(): Map<String, String> =
        File(workingDir, "pods/${Constants.Kit.RESOLVED_ARGS_FILE}")
            .readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private companion object {
        val CONTRIBUTOR = Constants.Kit.Contributors.CASSANDRA_ARTIFACT
    }
}
