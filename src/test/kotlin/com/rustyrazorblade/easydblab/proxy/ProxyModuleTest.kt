package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import com.rustyrazorblade.easydblab.providers.ssh.SshRoute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

/**
 * [proxyModule] gives the `sshConfig` writer the same proxy service every command uses, so applying a
 * rewritten `sshConfig` shares that service's lock and its knowledge of the running tunnel.
 */
internal class ProxyModuleTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `the sshConfig reloader is the one proxy service instance`() {
        val leaves =
            module {
                single { Context.forCli(workspace).copy(workingDirectory = workspace) }
                single<SshRoute> { DirectSshRoute(22) }
            }
        val koin = koinApplication { modules(proxyModule, leaves) }.koin

        assertThat(koin.get<SshConfigReloader>()).isSameAs(koin.get<SocksProxyService>())
    }
}
