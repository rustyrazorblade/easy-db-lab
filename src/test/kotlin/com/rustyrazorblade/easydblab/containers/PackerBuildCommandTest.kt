package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestination
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsLocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.time.Duration

/**
 * Tests for [Packer.buildCommand]: the `packer build` arguments, in particular that the builder is
 * reached over Session Manager only under the `ssm` SSH transport, that everything a build launches
 * carries its build id, and that the packer-lint workflow validates the templates with every
 * variable the command passes (the templates themselves are checked by `packer validate` there).
 */
class PackerBuildCommandTest {
    private companion object {
        const val MIN_KEEPALIVES_PER_TIMEOUT = 6L
        const val BUILD_ID = "0123456789ab"
        val TEMPLATES = listOf("packer/base/base.pkr.hcl", "packer/cassandra/cassandra.pkr.hcl")
    }

    private fun destination(transport: SshTransport) =
        AwsDestination(
            location = AwsLocation("us-west-2"),
            credentials = AwsCredentials.AccessKey("AKIA", "secret"),
            keyName = "lab-key",
            accountBucket = "easy-db-lab-bucket",
            sshTransport = transport,
        )

    private val build = PackerBuild("base.pkr.hcl", BUILD_ID, "eu-west-1", maxAttempts = 3, keepOnError = false)

    private fun command(
        transport: SshTransport,
        releaseVersion: String? = null,
    ) = Packer.buildCommand(build, Arch.AMD64, destination(transport), "easy-db-lab-build-$BUILD_ID", releaseVersion)

    /** The variable names a command passes, from its `-var key=value` pairs. */
    private fun varNames(command: List<String>): Set<String> =
        command
            .zipWithNext()
            .filter { (flag, _) -> flag == "-var" }
            .map { (_, assignment) -> assignment.substringBefore('=') }
            .toSet()

    @Test
    fun `ssm builds reach the builder through Session Manager`() {
        assertThat(command(SshTransport.Ssm)).containsSequence("-var", "ssh_interface=session_manager")
    }

    @Test
    fun `direct builds leave Packer's interface at its default`() {
        assertThat(command(SshTransport.Direct)).noneMatch { it.startsWith("ssh_interface=") }
    }

    @Test
    fun `the build runs in its own region with the key pair its site gave it`() {
        val command = command(SshTransport.Direct)

        assertThat(command).containsSequence("-var", "region=eu-west-1")
        assertThat(command).containsSequence("-var", "ssh_keypair_name=easy-db-lab-build-$BUILD_ID")
    }

    @ParameterizedTest
    @EnumSource(SshTransport::class)
    fun `every build keeps its SSH session alive and bounds a silent stream`(transport: SshTransport) {
        val command = command(transport)

        assertThat(command).containsSequence("-var", "ssh_keep_alive_interval=${Constants.Packer.SSH_KEEP_ALIVE_INTERVAL}")
        assertThat(command).containsSequence("-var", "ssh_read_write_timeout=${Constants.Packer.SSH_READ_WRITE_TIMEOUT}")
    }

    @Test
    fun `the read-write timeout leaves room for many keepalive replies`() {
        val keepAlive = Duration.parse("PT${Constants.Packer.SSH_KEEP_ALIVE_INTERVAL}".uppercase())
        val timeout = Duration.parse("PT${Constants.Packer.SSH_READ_WRITE_TIMEOUT}".uppercase())

        // A healthy but quiet script sees a keepalive reply every interval, so it must never reach
        // the timeout; a live stream that misses a few replies still must not.
        assertThat(timeout).isGreaterThanOrEqualTo(keepAlive.multipliedBy(MIN_KEEPALIVES_PER_TIMEOUT))
    }

    @Test
    fun `the template comes last and a release build is stamped with its version`() {
        val command = command(SshTransport.Direct, releaseVersion = "17")

        assertThat(command).containsSequence("-var", "release_version=17")
        assertThat(command.last()).isEqualTo("base.pkr.hcl")
        assertThat(command(SshTransport.Direct)).noneMatch { it.startsWith("release_version=") }
    }

    @Test
    fun `everything the build launches is tagged with both marker keys and its build id`() {
        assertThat(command(SshTransport.Direct)).containsSequence(
            "-var",
            "marker_tags={\"easy_cass_lab\" = \"1\", \"easy_db_lab\" = \"1\", \"easy_db_lab_build_id\" = \"$BUILD_ID\"}",
        )
    }

    @Test
    fun `build ids are short hex and distinct`() {
        val ids = (1..1000).map { Packer.newBuildId() }

        assertThat(ids).allMatch { it.matches(Regex("[0-9a-f]{${Constants.Packer.BUILD_ID_LENGTH}}")) }
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `a quote or a backslash in a tag stays inside its HCL string`() {
        assertThat(Packer.hclMap(mapOf("k\"ey" to "a\\b\"c")))
            .isEqualTo("{\"k\\\"ey\" = \"a\\\\b\\\"c\"}")
    }

    @Test
    fun `packer-lint validates the templates with every variable a build passes`() {
        val workflow = File(".github/workflows/packer-lint.yml").readText()
        val linted =
            Regex("""-var\s+["']?([a-z0-9_]+)=""")
                .findAll(workflow)
                .map { it.groupValues[1] }
                .toSet()

        assertThat(linted).containsAll(varNames(command(SshTransport.Ssm, releaseVersion = "17")))
    }

    /**
     * `packer validate` fails on a variable a template does not declare, but not on one it declares
     * and never uses, so these two check the uses the build depends on: the cleanup finds a
     * builder only by the build id in `marker_tags`, and a silent stream fails only through the
     * keepalive and the read-write timeout.
     */
    @Test
    fun `both templates put the marker tags on the builder, its volumes, the AMI and its snapshots`() {
        TEMPLATES.forEach { path ->
            val template = File(path).readText()

            listOf("run_tags", "run_volume_tags", "tags", "snapshot_tags").forEach { attribute ->
                assertThat(template).`as`("$path $attribute").containsPattern("""(?m)^\s*$attribute\s*=\s*var\.marker_tags\s*$""")
            }
        }
    }

    @Test
    fun `both templates pass the keepalive and read-write timeout through to the builder`() {
        TEMPLATES.forEach { path ->
            val template = File(path).readText()

            listOf("ssh_keep_alive_interval", "ssh_read_write_timeout").forEach { attribute ->
                assertThat(template)
                    .`as`("$path source $attribute")
                    .containsPattern("""(?m)^\s*$attribute\s*=\s*var\.$attribute\s*$""")
            }
        }
    }
}
