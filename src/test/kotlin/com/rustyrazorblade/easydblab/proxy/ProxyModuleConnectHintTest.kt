package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import com.rustyrazorblade.easydblab.providers.ssh.sshModule
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
 * Tests that the production [proxyModule] gives the tunnel service the security-group hint only when
 * it is true: on the `direct` transport, for a cluster whose SSH rule admits one public IP. An
 * `init --open` cluster admits every address, and an `ssm` tunnel never dials port 22 from here.
 */
internal class ProxyModuleConnectHintTest : BaseKoinTest() {
    private val stateFile by lazy { File(tempDir, "state.json") }

    override fun additionalTestModules(): List<Module> =
        listOf(
            ssmModule,
            sshModule,
            proxyModule,
            module {
                single { ClusterStateManager(stateFile) }
                single<AwsCredentialsProvider> {
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "test-secret"))
                }
            },
        )

    @Test
    fun `a direct cluster that admits one IP names the security group rule`() {
        val message = stalledConnectMessage(SshTransport.Direct, open = false)

        assertThat(message)
            .contains("ssh could not connect to control0 (54.1.2.3) port 22 within 15s.")
            .contains("security group allows SSH only from the IPs it lists")
            .contains("easy-db-lab ingress allow")
            .doesNotContain("recorded when `up` ran")
    }

    @Test
    fun `an open direct cluster names the stage but not the security group rule`() {
        val message = stalledConnectMessage(SshTransport.Direct, open = true)

        assertThat(message)
            .contains("ssh could not connect to control0 (54.1.2.3) port 22 within 15s.")
            .doesNotContain("security group")
    }

    @Test
    fun `an ssm cluster never names the security group rule`() {
        val message = stalledConnectMessage(SshTransport.Ssm, open = false)

        assertThat(message)
            .contains("ssh could not connect to control0 (54.1.2.3) port 22 within 15s.")
            .doesNotContain("security group")
    }

    @Test
    fun `a corrupt cluster state gives the stage without a hint`() {
        val message = stalledConnectMessage(SshTransport.Direct, open = false) { stateFile.writeText("{ not json") }

        assertThat(message)
            .contains("ssh could not connect to control0 (54.1.2.3) port 22 within 15s.")
            .doesNotContain("security group")
    }

    /**
     * The failure message of a tunnel whose ssh was still dialing after 15s, from the service the
     * production module builds for [transport] and a cluster saved with [open]. The cluster state is
     * written after the service is resolved, so the hint must read it when the message is built;
     * [afterSave] can then change the file.
     */
    private fun stalledConnectMessage(
        transport: SshTransport,
        open: Boolean,
        afterSave: () -> Unit = {},
    ): String {
        val user = getKoin().get<User>().copy(sshTransport = transport)
        getKoin().loadModules(listOf(module { single<User> { user } }), allowOverride = true)
        val service = getKoin().get<SocksProxyService>() as ProcessSocksProxyService
        getKoin().get<ClusterStateManager>().save(
            ClusterState(name = "test-cluster", versions = mutableMapOf(), initConfig = InitConfig(open = open)),
        )
        afterSave()
        val log = File(tempDir, "socks5-proxy.log").apply { writeText("debug1: Connecting to 54.1.2.3 [54.1.2.3] port 22.\n") }
        try {
            return service.verificationFailureMessage(log, exitCode = null, gatewayAlias = "control0", stalledFor = Duration.ofSeconds(15))
        } finally {
            getKoin().get<SshRoute>().close()
        }
    }
}
