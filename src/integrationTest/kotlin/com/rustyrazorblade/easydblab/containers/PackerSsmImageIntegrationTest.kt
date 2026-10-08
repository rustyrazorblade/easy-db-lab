package com.rustyrazorblade.easydblab.containers

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.providers.docker.DefaultDockerClientProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Builds the packaged SSM-capable Packer image against a real container engine.
 *
 * The Dockerfile's last step runs `session-manager-plugin --version`, so a successful build proves
 * the glibc-built plugin runs on the Alpine-based Packer image. Running `packer version` afterwards
 * proves the derived image still runs Packer as its entrypoint (a zero exit). Needs network access to the Alpine
 * mirror and to AWS's plugin download the first time it runs on a machine.
 */
class PackerSsmImageIntegrationTest : BaseKoinTest() {
    @Test
    fun `the packaged Dockerfile builds an image whose plugin runs and that still runs Packer`() {
        val docker = Docker(context, DefaultDockerClientProvider().getDockerClient())

        val image = PackerImage(docker).ensure(SshTransport.Ssm)
        val result = docker.runContainer(image, mutableListOf("version"), "")

        assertThat(result.isSuccess).isTrue()
    }
}
