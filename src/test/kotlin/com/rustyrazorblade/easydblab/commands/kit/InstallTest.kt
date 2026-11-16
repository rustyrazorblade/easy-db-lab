package com.rustyrazorblade.easydblab.commands.kit

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * `kit install --kit/--size` only mean something together with `--from`. Given alone they used to
 * parse, print the usage text and exit 0, so a typo or a wrong command group looked like success.
 */
class InstallTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
                single { TemplateService(get(), get()) }
                single<ClusterStateManager> {
                    mock<ClusterStateManager>().also {
                        whenever(it.load()).thenReturn(ClusterState(name = "test-cluster", versions = mutableMapOf()))
                    }
                }
            },
        )

    @Test
    fun `--kit without --from fails and names --from`() {
        assertThatThrownBy { Install().apply { kit = "mykit" }.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--from")
    }

    @Test
    fun `--size without --from fails and names --from`() {
        assertThatThrownBy { Install().apply { size = "100Gi" }.execute() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--from")
    }

    @Test
    fun `a valid --from, --kit and --size still scaffolds the kit directory`() {
        val template = File(context.workingDirectory, "tpl").apply { mkdirs() }
        File(template, "kit.yaml").writeText("name: tpl\n")

        Install()
            .apply {
                from = template.toPath()
                kit = "my-kit"
                size = "10Gi"
            }.execute()

        assertThat(File(File(context.workingDirectory, "my-kit"), "kit.yaml")).exists()
    }

    @ParameterizedTest
    @ValueSource(strings = ["../escape", "a/b", "-lead", "has space", ""])
    fun `a kit name that is not a plain identifier is rejected before anything is written`(name: String) {
        val template = File(context.workingDirectory, "tpl").apply { mkdirs() }
        File(template, "kit.yaml").writeText("name: tpl\n")

        assertThatThrownBy {
            Install()
                .apply {
                    from = template.toPath()
                    kit = name
                    size = "10Gi"
                }.execute()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--kit")
    }
}
