package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.commands.observability.LocalCreate
import com.rustyrazorblade.easydblab.commands.observability.LocalDashboards
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import picocli.CommandLine
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

/**
 * The `observability local` commands are reachable: the real [localStackModule] builds both
 * services from the application's own definitions, and the root command routes both commands.
 */
class LocalStackModuleTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            localStackModule,
            module {
                single { GrafanaDashboardCatalog.discover() }
                single<AwsCredentialsProvider> { mock() }
            },
        )

    @Test
    fun `the module builds the creator and the dashboard refresher`() {
        assertThatCode {
            getKoin().get<LocalStackCreator>()
            getKoin().get<LocalDashboardsRefresher>()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `the root command routes observability local create and dashboards`() {
        val root = CommandLine(EasyDBLabCommand())

        val create = root.parseArgs("observability", "local", "create", "lab-local", "--tenant", "acme")
        val dashboards = root.parseArgs("observability", "local", "dashboards", "lab-local", "--from", "work")

        val createCommand = generateSequence(create) { it.subcommand() }.last().commandSpec().userObject()
        assertThat(createCommand).isInstanceOf(LocalCreate::class.java)
        assertThat((createCommand as LocalCreate).tenant).isEqualTo("acme")
        val dashboardsCommand = generateSequence(dashboards) { it.subcommand() }.last().commandSpec().userObject()
        assertThat((dashboardsCommand as LocalDashboards).from?.path).isEqualTo("work")
    }
}
