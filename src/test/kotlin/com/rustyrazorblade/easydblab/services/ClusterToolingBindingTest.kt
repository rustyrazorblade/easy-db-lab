package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.di.configurationModule
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock

/**
 * The production wiring of [ClusterTooling]. It starts Koin with the real [servicesModule] and
 * [configurationModule] and not with `BaseKoinTest`, whose core test modules replace production
 * modules and so could not tell a changed default binding from a test double.
 */
class ClusterToolingBindingTest {
    /** The leaf dependency [SshClusterTooling] needs that neither production module provides. */
    private val leafDependencies =
        module {
            single<RemoteOperationsService> { mock() }
        }

    @Test
    fun `the SSH form is the default binding`() {
        val app = koinApplication { modules(servicesModule, configurationModule, leafDependencies) }
        try {
            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(SshClusterTooling::class.java)
        } finally {
            app.close()
        }
    }
}
