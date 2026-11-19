package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.di.configurationModule
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock
import java.io.File

/**
 * The production wiring of [ClusterTooling]. It starts Koin with the real [servicesModule] and
 * [configurationModule] and not with `BaseKoinTest`, whose core test modules replace production
 * modules and so could not tell a changed default binding from a test double.
 */
class ClusterToolingBindingTest {
    @TempDir
    lateinit var tempDir: File

    /** The leaf dependencies the two forms need that neither production module provides. */
    private val leafDependencies
        get() =
            module {
                single<RemoteOperationsService> { mock() }
                single<Context> { TestContextFactory.createTestContext(tempDir) }
                single { ToolWrapperInstaller() }
            }

    @Test
    fun `the SSH form is the binding outside a workspace`() {
        val app = koinApplication { modules(servicesModule, configurationModule, leafDependencies) }
        try {
            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(SshClusterTooling::class.java)
        } finally {
            app.close()
        }
    }

    @Test
    fun `a K3s in Docker workspace resolves the local form with no override`() {
        val app = koinApplication { modules(servicesModule, configurationModule, leafDependencies) }
        try {
            app.koin.get<ClusterStateManager>().save(stateIn(Environment.K3sDocker("/data")))

            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(LocalClusterTooling::class.java)
        } finally {
            app.close()
        }
    }

    /** An `init --local` inside the REPL rewrites state.json; the next command must see the new form. */
    @Test
    fun `an init to a local environment switches the form on the next resolution in the same graph`() {
        val app = koinApplication { modules(servicesModule, configurationModule, leafDependencies) }
        try {
            val states = app.koin.get<ClusterStateManager>()
            states.save(stateIn(Environment.Ec2K3s))
            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(SshClusterTooling::class.java)
            assertThat(app.koin.get<HelmService>()).isNotNull()

            states.save(stateIn(Environment.K3sDocker("/data")))

            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(LocalClusterTooling::class.java)
        } finally {
            app.close()
        }
    }

    /** The other way round: an `init --local` and then an AWS `init` in one REPL; no local form is left. */
    @Test
    fun `an init back to EC2 after a local one switches to the SSH form in the same graph`() {
        val app = koinApplication { modules(servicesModule, configurationModule, leafDependencies) }
        try {
            val states = app.koin.get<ClusterStateManager>()
            states.save(stateIn(Environment.K3sDocker("/data")))
            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(LocalClusterTooling::class.java)

            states.save(stateIn(Environment.DEFAULT))

            assertThat(app.koin.get<ClusterTooling>()).isInstanceOf(SshClusterTooling::class.java)
        } finally {
            app.close()
        }
    }

    private fun stateIn(environment: Environment) = ClusterState(name = "test", environment = environment)
}
