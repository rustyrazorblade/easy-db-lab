package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.di.KoinCommandFactory
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.services.ProfileSetupCommandProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lifecycle-level guards for the `profile` command group.
 *
 * `ProfileShow.buildReport` is a pure function of three plain values, so it cannot observe the
 * shape of the picocli tree, the annotations a command carries, or which collaborators the command
 * lifecycle touched. Every assertion here is invisible at that surface and would stay green
 * through exactly the regressions it guards against.
 */
class ProfileCommandGroupTest : BaseKoinTest() {
    private val stdout = ByteArrayOutputStream()
    private val originalOut = System.out

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                // A real manager over a state.json that does not exist: any read of clusterState
                // throws FileNotFoundException, which is precisely the regression being guarded.
                single { ClusterStateManager(File(get<Context>().workingDirectory, "state.json")) }
                // Rebuilt on resolution rather than at module construction, so a test can point the
                // context at another profile first.
                single { UserConfigProvider(get<Context>().profileDir) }
            },
        )

    @BeforeEach
    fun captureStdout() {
        System.setOut(PrintStream(stdout))
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
        stdout.reset()
    }

    private fun rootCommandLine() = CommandLine(EasyDBLabCommand::class.java, KoinCommandFactory())

    private fun settingsFile(profileDir: File) = File(profileDir, Constants.ConfigPaths.PROFILE_SETTINGS_FILE)

    @Test
    fun `profile group exposes show and setup`() {
        val profileGroup = rootCommandLine().subcommands["profile"]

        assertThat(profileGroup).isNotNull
        assertThat(profileGroup!!.subcommands.keys).contains("show", "setup")
        // The names alone would stay green with either subcommand bound to the wrong class, which
        // is the one thing the rename could plausibly get wrong.
        assertThat(
            profileGroup.subcommands
                .getValue("show")
                .commandSpec
                .userObject(),
        ).isInstanceOf(ProfileShow::class.java)
        assertThat(
            profileGroup.subcommands
                .getValue("setup")
                .commandSpec
                .userObject(),
        ).isInstanceOf(SetupProfile::class.java)
    }

    @Test
    fun `the production graph binds the profile setup provider to SetupProfile`() {
        // checkRequirements() runs whatever this provider yields. Bound to the wrong command, a
        // first-run user gets a report printed instead of interactive setup, and then exit 0.
        assertThat(getKoin().get<ProfileSetupCommandProvider>().create()).isInstanceOf(SetupProfile::class.java)
    }

    @Test
    fun `former top-level setup names no longer resolve`() {
        val topLevel = rootCommandLine().subcommands.keys

        assertThat(topLevel).contains("profile")
        assertThat(topLevel).doesNotContain("setup", "setup-profile")
    }

    @Test
    fun `bare profile prints usage listing its subcommands and exits zero`() {
        val commandLine = rootCommandLine()
        val usage = StringWriter()
        commandLine.out = PrintWriter(usage)

        val exitCode = commandLine.execute("profile")

        assertThat(exitCode).isZero()
        assertThat(usage.toString()).contains("show")
        assertThat(usage.toString()).contains("setup")
    }

    @Test
    fun `profile group is not a PicoCommand so the executor never routes it`() {
        // CommandLineParser's execution strategy routes a matched command through CommandExecutor
        // only when it is a PicoCommand; anything else falls through to RunLast, which is what
        // makes a bare `profile` print usage and exit 0 instead of running a command lifecycle.
        val profileGroup = rootCommandLine().subcommands.getValue("profile")

        assertThat(profileGroup.commandSpec.userObject()).isNotInstanceOf(PicoCommand::class.java)
        assertThat(profileGroup.commandSpec.userObject()).isInstanceOf(Runnable::class.java)
    }

    @Test
    fun `ProfileShow carries no requirement annotation`() {
        // @RequireProfileSetup would make checkRequirements() run interactive setup and exit,
        // replacing the report the command exists to print. The other three would refuse to run
        // outside a provisioned cluster.
        val annotations = ProfileShow::class.annotations

        assertThat(annotations.filterIsInstance<RequireProfileSetup>()).isEmpty()
        assertThat(annotations.filterIsInstance<RequireSSHKey>()).isEmpty()
        assertThat(annotations.filterIsInstance<RequireDocker>()).isEmpty()
        assertThat(annotations.filterIsInstance<RequiresProxy>()).isEmpty()
    }

    @Test
    fun `profile show reports without loading cluster state`() {
        // The working directory holds no state.json, so ClusterStateManager.load() throws. Reaching
        // the assertions proves the command never touched clusterState.
        assertThat(File(context.workingDirectory, "state.json")).doesNotExist()

        ProfileShow().execute()

        assertThat(stdout.toString()).contains("Profile:")
    }

    @Test
    fun `profile show emits no event for the report it prints`() {
        // The test EventBus forwards every emitted event to the BufferedOutputHandler, so an empty
        // buffer alongside a printed report is direct proof the report went to stdout only. An
        // event here would put profile settings on the MCP and Redis subscriber streams.
        ProfileShow().execute()

        val outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
        assertThat(stdout.toString()).contains("Profile:")
        assertThat(outputHandler.messages).isEmpty()
        assertThat(outputHandler.errors).isEmpty()
    }

    @Test
    fun `profile show reports the profile named by the context rather than the default`() {
        // EASY_DB_LAB_PROFILE is resolved into Context.profile/profileDir at Context.kt:49-51;
        // this is that value's path through the command.
        context.profile = "staging"
        context.profileDir = File(context.profilesDir, "staging").apply { mkdirs() }
        context.yaml.writeValue(
            settingsFile(context.profileDir),
            userWith(email = "staging-user@example.com"),
        )

        getKoin().get<ProfileShow>().execute()

        val output = stdout.toString()
        assertThat(output).contains("staging")
        assertThat(output).contains(context.profileDir.absolutePath)
        assertThat(output).contains("staging-user@example.com")
    }

    @Test
    fun `profile show reports a truncated settings file instead of throwing`() {
        // User has six constructor parameters with no defaults, so a file missing one of them
        // fails deserialization. The command must report that, not surface the Jackson error.
        settingsFile(context.profileDir).writeText("email: partial@example.com\n")

        assertThatCode { getKoin().get<ProfileShow>().execute() }.doesNotThrowAnyException()

        val output = stdout.toString()
        assertThat(output).contains("could not be read")
        assertThat(output).contains("easy-db-lab profile setup")
        assertThat(output).doesNotContain("Exception")
        assertThat(output).doesNotContain("com.fasterxml.jackson")
    }

    private fun userWith(email: String) =
        User(
            email = email,
            region = "eu-west-1",
            keyName = "staging-key",
            awsProfile = "staging-aws",
            awsAccessKey = "",
            awsSecret = "",
        )
}
