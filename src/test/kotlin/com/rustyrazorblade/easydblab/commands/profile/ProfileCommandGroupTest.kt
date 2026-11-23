package com.rustyrazorblade.easydblab.commands.profile

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.TestProfiles
import com.rustyrazorblade.easydblab.annotations.RequireDocker
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequireSSHKey
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
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
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

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

    private fun profileFile(profileDir: File) = File(profileDir, Constants.ConfigPaths.PROFILE_FILE)

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
        assertThat(context.workspaceFile(CLUSTER_STATE)).doesNotExist()

        ProfileShow().execute()

        assertThat(stdout.toString()).contains("Profile:")
    }

    @Test
    fun `profile show emits no event for the report it prints`() {
        // The test EventBus forwards every emitted event to the BufferedOutputHandler, so an empty
        // buffer alongside a printed report is direct proof the report went to stdout only. An
        // event here would put profile settings on the Redis subscriber stream.
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
        // The core store binding is resolved on first use, so it is built over the staging directory.
        TestProfiles.storeWith(context.profileDir, TestProfiles.profile(email = "staging-user@example.com"))

        getKoin().get<ProfileShow>().execute()

        val output = stdout.toString()
        assertThat(output).contains("staging")
        assertThat(output).contains(context.profileDir.absolutePath)
        assertThat(output).contains("staging-user@example.com")
    }

    @Test
    fun `profile show reports a truncated profile file instead of throwing`() {
        // A profile needs a destination, so a file holding only the email fails to decode. The
        // command must report that, not surface the decoder's exception.
        profileFile(context.profileDir).writeText("email: partial@example.com\n")

        assertThatCode { getKoin().get<ProfileShow>().execute() }.doesNotThrowAnyException()

        val output = stdout.toString()
        assertThat(output).contains("could not be read")
        assertThat(output).contains("easy-db-lab profile setup")
        assertThat(output).doesNotContain("Exception")
        assertThat(output).doesNotContain("kaml")
    }

    /**
     * With only an earlier release's file, and that file broken, the report names the file that
     * failed, not a `profile.yaml` that does not exist.
     *
     * Control: name `profile.yaml` in every unreadable report; the path check fails.
     */
    @Test
    fun `profile show names the earlier file when it is the one that cannot be read`() {
        val store = getKoin().get<ProfileStore>()
        store.file.delete()
        store.legacyFile.writeText("awsProfile: [broken\n")

        getKoin().get<ProfileShow>().execute()

        val output = stdout.toString()
        assertThat(output).contains("could not be read:\n  ${store.legacyFile.absolutePath}")
        assertThat(output).doesNotContain("could not be read:\n  ${store.file.absolutePath}")
    }

    /**
     * A profile file the user cannot read is reported like an undecodable one.
     *
     * Control: catch only IllegalStateException in the command; the AccessDeniedException escapes.
     */
    @Test
    fun `profile show reports a profile file it has no permission to read`() {
        val store = getKoin().get<ProfileStore>()
        Files.setPosixFilePermissions(store.file.toPath(), PosixFilePermissions.fromString("---------"))

        assertThatCode { getKoin().get<ProfileShow>().execute() }.doesNotThrowAnyException()

        assertThat(stdout.toString()).contains("could not be read:\n  ${store.file.absolutePath}")
    }
}
