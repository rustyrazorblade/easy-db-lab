package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.ssm.SsmProxyWrapper
import com.rustyrazorblade.easydblab.providers.ssm.SsmSshRoute
import com.rustyrazorblade.easydblab.providers.ssm.ssmModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.io.File
import java.time.Duration

/**
 * Tests that the production SSH and SSM Koin modules bind the [SshRoute] the profile's SSH
 * transport selects. That binding is the one place the transport is decided, so a wrong branch
 * there would send every SSH connection down the wrong path.
 */
internal class SshRouteBindingTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            ssmModule,
            sshModule,
            module {
                single<AwsCredentialsProvider> {
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "test-secret"))
                }
            },
        )

    private val host =
        Host(public = "54.1.2.3", private = "10.0.0.7", alias = "db0", availabilityZone = "us-west-2a", instanceId = "i-0abc")

    @Test
    fun `the ssm transport binds the Session Manager route`() {
        useTransport(SshTransport.Ssm)

        getKoin().get<SshRoute>().use { route ->
            assertThat(route).isInstanceOf(SsmSshRoute::class.java)
            assertThat(route.proxyCommand(host)).contains("AWS-StartSSHSession", "--target i-0abc", "--region us-west-2")
            assertThat(route.tunnelVerifyAttempts).isEqualTo(Constants.Proxy.SSM_TUNNEL_VERIFY_ATTEMPTS)
            assertThat(route.authTimeout).isEqualTo(Duration.ofSeconds(30))
            // The ProxyCommand runs through the packaged wrapper, installed into the profile dir.
            val wrapper = File(getKoin().get<Context>().profileDir, SsmProxyWrapper.FILE_NAME)
            assertThat(route.proxyCommand(host)).contains(" ${wrapper.absolutePath} aws ssm start-session")
            assertThat(wrapper).isFile()
            assertThat(wrapper.canExecute()).isTrue()
        }
    }

    @Test
    fun `the direct transport binds the public-IP route`() {
        useTransport(SshTransport.Direct)

        val route = getKoin().get<SshRoute>()

        assertThat(route).isInstanceOf(DirectSshRoute::class.java)
        assertThat(route.proxyCommand(host)).isNull()
        assertThat(route.endpoint(host)).isEqualTo(SshEndpoint("54.1.2.3", 22))
        assertThat(route.tunnelVerifyAttempts).isEqualTo(Constants.Proxy.DIRECT_TUNNEL_VERIFY_ATTEMPTS)
        // null keeps MINA's own authentication timeout (120s) for direct connections.
        assertThat(route.authTimeout).isNull()
        route.invalidate(host)
        assertThat(route.endpoint(host)).isEqualTo(SshEndpoint("54.1.2.3", 22))
    }

    private fun useTransport(transport: SshTransport) {
        val user = getKoin().get<User>().copy(sshTransport = transport)
        getKoin().loadModules(listOf(module { single<User> { user } }), allowOverride = true)
    }
}
