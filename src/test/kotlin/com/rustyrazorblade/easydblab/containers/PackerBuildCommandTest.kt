package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [Packer.buildCommand]: the `packer build` arguments, in particular that the builder is
 * reached over Session Manager only under the `ssm` SSH transport.
 */
class PackerBuildCommandTest {
    private fun user(transport: SshTransport) =
        User(
            email = "test@example.com",
            region = "us-west-2",
            keyName = "lab-key",
            awsProfile = "",
            awsAccessKey = "AKIA",
            awsSecret = "secret",
            s3Bucket = "easy-db-lab-bucket",
            sshTransport = transport,
        )

    private fun command(
        transport: SshTransport,
        releaseVersion: String? = null,
    ) = Packer.buildCommand("base.pkr.hcl", "eu-west-1", Arch.AMD64, user(transport), releaseVersion)

    @Test
    fun `ssm builds reach the builder through Session Manager`() {
        assertThat(command(SshTransport.Ssm)).containsSequence("-var", "ssh_interface=session_manager")
    }

    @Test
    fun `direct builds leave Packer's interface at its default`() {
        assertThat(command(SshTransport.Direct)).noneMatch { it.startsWith("ssh_interface=") }
    }

    @Test
    fun `the template comes last and a release build is stamped with its version`() {
        val command = command(SshTransport.Direct, releaseVersion = "17")

        assertThat(command).containsSequence("-var", "release_version=17")
        assertThat(command.last()).isEqualTo("base.pkr.hcl")
        assertThat(command(SshTransport.Direct)).noneMatch { it.startsWith("release_version=") }
    }

    @Test
    fun `the builder and the AMI are tagged with both marker keys`() {
        val command = command(SshTransport.Direct)

        assertThat(command).containsSequence("-var", "marker_tags={\"easy_cass_lab\" = \"1\", \"easy_db_lab\" = \"1\"}")
    }

    @Test
    fun `a quote or a backslash in a tag stays inside its HCL string`() {
        assertThat(Packer.hclMap(mapOf("k\"ey" to "a\\b\"c")))
            .isEqualTo("{\"k\\\"ey\" = \"a\\\\b\\\"c\"}")
    }

    @Test
    fun `both templates put the marker tags on the builder, its volumes, the AMI and its snapshots`() {
        listOf("packer/base/base.pkr.hcl", "packer/cassandra/cassandra.pkr.hcl").forEach { path ->
            val template = File(path).readText()

            listOf("run_tags", "run_volume_tags", "tags", "snapshot_tags").forEach { attribute ->
                assertThat(template).`as`("$path $attribute").containsPattern("""(?m)^\s*$attribute\s*=\s*var\.marker_tags\s*$""")
            }
            assertThat(template).`as`(path).doesNotContainPattern("""easy_cass_lab\s*=\s*"1"""")
        }
    }
}
