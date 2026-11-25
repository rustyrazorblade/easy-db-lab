package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.withAws
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A [Packer] made before profile setup finished must build with what setup saved afterwards:
 * setup creates the key pair and bucket, then runs the image build in the same process. The build
 * reads the destination once, when it starts, and every attempt's command is built from that read.
 */
class PackerTest : BaseKoinTest() {
    /**
     * Control: capture `awsDestination.current()` in a Packer field at construction; the build
     * starts with the empty key name and bucket and the test fails.
     */
    @Test
    fun `a build reads the key pair and bucket saved after the packer was created`() {
        val store = getKoin().get<ProfileStore>()
        store.update { profile -> profile.withAws { it.copy(keyName = "", accountBucket = "") } }
        val packer = Packer(context, "base")

        File(store.sshKeyPath).writeText("key")
        store.update { profile -> profile.withAws { it.copy(keyName = "easy-db-lab-late", accountBucket = "easy-db-lab-late") } }
        val destination = packer.destinationForBuild()

        assertThat(destination.keyName).isEqualTo("easy-db-lab-late")
        val build = PackerBuild("base.pkr.hcl", "0123456789ab", "us-west-2", maxAttempts = 1, keepOnError = false)
        assertThat(Packer.buildCommand(build, Arch.AMD64, destination, "build-site-key", releaseVersion = null))
            .containsSequence("-var", "s3_bucket=easy-db-lab-late")
    }

    /** Control: drop the key name check; a build with no key pair starts and the test fails. */
    @Test
    fun `a build with no key pair saved stops before it starts`() {
        val store = getKoin().get<ProfileStore>()
        store.update { profile -> profile.withAws { it.copy(keyName = "") } }
        File(store.sshKeyPath).writeText("key")

        assertThatThrownBy { Packer(context, "base").destinationForBuild() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Run profile setup first")
    }
}
