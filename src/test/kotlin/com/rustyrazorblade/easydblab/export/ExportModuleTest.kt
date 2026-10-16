package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.EasyDBLabCommand
import com.rustyrazorblade.easydblab.commands.observability.ObservabilityExport
import com.rustyrazorblade.easydblab.configuration.grafana.GrafanaDashboardCatalog
import com.rustyrazorblade.easydblab.localstack.localStackModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import picocli.CommandLine
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

/** `observability export` is reachable: the real [exportModule] builds the exporter, and the root command routes the command. */
class ExportModuleTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            localStackModule,
            exportModule,
            module {
                single { GrafanaDashboardCatalog.discover() }
                single<AwsCredentialsProvider> { mock() }
            },
        )

    @Test
    fun `the module builds the tenant exporter`() {
        assertThatCode { getKoin().get<TenantExporter>() }.doesNotThrowAnyException()
    }

    @Test
    fun `the root command routes observability export with an experiment and its tenant`() {
        val parsed = CommandLine(EasyDBLabCommand()).parseArgs("observability", "export", "out.zip", "--experiment", "e", "--tenant", "acme")

        val command = generateSequence(parsed) { it.subcommand() }.last().commandSpec().userObject() as ObservabilityExport
        assertThat(command.path.path).isEqualTo("out.zip")
        assertThat(command.selector.choice().experiment).isEqualTo("e")
        assertThat(command.selector.choice().tenant).isEqualTo("acme")
    }
}
