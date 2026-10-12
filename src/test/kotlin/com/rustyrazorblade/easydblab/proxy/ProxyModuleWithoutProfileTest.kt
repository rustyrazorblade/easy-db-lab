package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.ssh.sshModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Tests that the production proxy module builds [SocksProxyService] when no profile exists.
 *
 * Every command reaches [SocksProxyService] through the command executor, `profile setup`
 * included. If building it reads the profile, a new install cannot run `profile setup` to create
 * that profile. The SSH route (and so the profile) may be read only when a tunnel starts.
 */
internal class ProxyModuleWithoutProfileTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            sshModule,
            proxyModule,
            module {
                single<User> { error("User configuration file not found") }
            },
        )

    @Test
    fun `the proxy service is built without reading the profile`() {
        assertThat(getKoin().get<SocksProxyService>()).isInstanceOf(ProcessSocksProxyService::class.java)
    }
}
