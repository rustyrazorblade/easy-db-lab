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
import org.junit.jupiter.api.Assumptions.assumeFalse
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
        assertThat(output).contains("file could not be used")
        assertThat(output).contains("easy-db-lab profile setup")
        assertThat(output).doesNotContain("Exception")
        assertThat(output).doesNotContain("kaml")
        assertFailureBodySaysPathAndHintOnce(output, profileFile(context.profileDir).absolutePath, "'destination' is required but missing")
    }

    /**
     * Setup stops at an unknown key, so the report tells the user to fix the key instead of naming setup.
     *
     * Control: print the setup hint for every remedy in the report; the setup hint is printed and the test fails.
     */
    @Test
    fun `profile show tells the user to fix an unknown key instead of running setup`() {
        TestProfiles.storeWith(context.profileDir, TestProfiles.profile(accountBucket = "a-bucket"))
        val file = profileFile(context.profileDir)
        file.writeText(file.readText().replace("accountBucket:", "accountBuket:"))
        check("accountBuket:" in file.readText()) { "the fixture should hold the misspelled key" }

        getKoin().get<ProfileShow>().execute()

        val body = stdout.toString().substringAfter("could not be used:")
        assertThat(body.split(file.absolutePath)).describedAs("path occurrences").hasSize(2)
        assertThat(body.split("Fix or remove that key in the file")).describedAs("hint occurrences").hasSize(2)
        assertThat(body).contains("unknown key 'accountBuket'").doesNotContain("profile setup")
    }

    /**
     * Setup parses `profile.yaml` too, so for a file that is not valid YAML it would fail the same way:
     * the report tells the user to fix the file and does not name setup.
     *
     * Control: report a parse failure as one setup repairs; the setup hint is printed and the test fails.
     */
    @Test
    fun `profile show tells the user to fix a malformed profile file instead of running setup`() {
        val file = profileFile(context.profileDir)
        file.writeText("email: [broken\n")

        assertThatCode { getKoin().get<ProfileShow>().execute() }.doesNotThrowAnyException()

        val body = stdout.toString().substringAfter("could not be used:")
        assertThat(body.split(file.absolutePath)).describedAs("path occurrences").hasSize(2)
        assertThat(body.split("Fix the file")).describedAs("hint occurrences").hasSize(2)
        assertThat(body).contains("not valid YAML").doesNotContain("profile setup").doesNotContain("broken")
    }

    /**
     * The failure body (the header names `profile.yaml` on its own line) names the failing path once,
     * gives the reason, and gives the remedy's hint once.
     */
    private fun assertFailureBodySaysPathAndHintOnce(
        output: String,
        path: String,
        reason: String,
        hint: String = "profile setup",
    ) {
        val body = output.substringAfter("could not be used:")
        assertThat(body.split(path)).describedAs("path occurrences").hasSize(2)
        assertThat(body.split(hint)).describedAs("hint occurrences").hasSize(2)
        assertThat(body).contains(reason)
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
        assertThat(output).contains("file could not be used:\n  ${store.legacyFile.absolutePath}")
        assertThat(output).doesNotContain("file could not be used:\n  ${store.file.absolutePath}")
    }

    /**
     * A profile file the user cannot read is reported like an undecodable one.
     *
     * Control: drop the IOException redaction in the store; the FileNotFoundException escapes.
     */
    @Test
    fun `profile show reports a profile file it has no permission to read`() {
        val store = getKoin().get<ProfileStore>()
        Files.setPosixFilePermissions(store.file.toPath(), PosixFilePermissions.fromString("---------"))
        // Root reads any file whatever its mode, so there is nothing to test there.
        assumeFalse(Files.isReadable(store.file.toPath()))

        assertThatCode { getKoin().get<ProfileShow>().execute() }.doesNotThrowAnyException()

        assertThat(stdout.toString()).contains("file could not be used:\n  ${store.file.absolutePath}")
    }

    /**
     * With a readable `settings.yaml` and a directory that cannot be written, the conversion fails
     * on the write. The report names `profile.yaml` and the write reason, not "could not be read".
     *
     * Control: report every failure as "could not be read"; the reason check fails.
     */
    @Test
    fun `profile show reports the write reason when the conversion cannot write profile yaml`() {
        val store = getKoin().get<ProfileStore>()
        store.file.delete()
        val resource = "/com/rustyrazorblade/easydblab/configuration/profile/legacy/aws-profile.yaml"
        store.legacyFile.writeBytes(checkNotNull(javaClass.getResource(resource)).readBytes())
        File(context.profileDir, Constants.ConfigPaths.PROFILE_LOCK_FILE).createNewFile()
        val dir = context.profileDir.toPath()
        val original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            // Root can write anywhere, so the failure cannot be triggered.
            assumeFalse(Files.isWritable(dir))

            getKoin().get<ProfileShow>().execute()

            val output = stdout.toString()
            assertThat(output).contains("its file could not be used:\n  ${store.file.absolutePath}")
            assertThat(output).doesNotContain("could not be read")
            // A write failure is not repaired by setup, so the report says to fix the file.
            assertFailureBodySaysPathAndHintOnce(output, store.file.absolutePath, "while writing", hint = "Fix the file")
            assertThat(output).doesNotContain("profile setup")
        } finally {
            Files.setPosixFilePermissions(dir, original)
        }
    }
}
