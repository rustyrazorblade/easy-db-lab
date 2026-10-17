package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.TemplateVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import java.io.File

/**
 * `kit install` renders every `kit.yaml` through the `__VAR__` substitution before it writes the
 * installed copy. A `kit.yaml` that declares no placeholder must come out byte for byte as it went
 * in: the substitution also treats `$__` as an escape and `:-` as a default, so a shell script in a
 * `kit.yaml` could be rewritten by accident. Checked across every built-in kit.
 */
class BuiltinKitYamlRenderTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    @Test
    fun `every built-in kit yaml without a placeholder renders unchanged`() {
        val resolver = get<InstallTemplateResolver>()
        val templateService = TemplateService(ClusterStateManager(File(tempDir, "state.json")), get())
        val kits =
            resolver.listAvailableTemplates().mapNotNull { name ->
                val source = resolver.resolve(name)
                val yaml = resolver.readInstallYamlContent(source) ?: return@mapNotNull null
                val config = resolver.loadInstallConfig(source) ?: return@mapNotNull null
                Triple(name, yaml, config.args.associate { it.variable to it.default })
            }
        val withoutPlaceholders = kits.filterNot { (_, yaml, _) -> PLACEHOLDER.containsMatchIn(yaml) }
        assertThat(withoutPlaceholders).isNotEmpty()

        val vars = TemplateVariables.from(state = ClusterState(name = "c", versions = mutableMapOf()), kitName = "k", storageSize = "1Gi")
        assertThat(withoutPlaceholders).allSatisfy { (name, yaml, defaults) ->
            assertThat(templateService.renderKitTemplate(yaml, vars, defaults))
                .describedAs("rendered $name/kit.yaml")
                .isEqualTo(yaml)
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""__[A-Z][A-Z0-9_]*__""")
    }
}
