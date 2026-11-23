package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.Arch
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.withAws
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A [Packer] made before profile setup finished must build with what setup saved afterwards:
 * setup creates the key pair and bucket, then runs the image build in the same process. The command
 * names the build site's key pair, not the profile's, so the bucket is what it carries from the profile.
 */
class PackerTest : BaseKoinTest() {
    private val build = PackerBuild("base.pkr.hcl", "0123456789ab", "us-west-2", maxAttempts = 1, keepOnError = false)

    /**
     * Control: capture `awsDestination.current()` in a Packer field at construction; the command
     * carries the empty bucket and the test fails.
     */
    @Test
    fun `build command reads the bucket saved after the packer was created`() {
        val store = getKoin().get<ProfileStore>()
        store.update { profile -> profile.withAws { it.copy(accountBucket = "") } }
        val packer = Packer(context, "base")

        store.update { profile -> profile.withAws { it.copy(accountBucket = "easy-db-lab-late") } }
        val command = packer.buildCommand(build, Arch.AMD64, keyPairName = "build-site-key", releaseVersion = null)

        assertThat(command).containsSequence("-var", "s3_bucket=easy-db-lab-late")
    }
}
