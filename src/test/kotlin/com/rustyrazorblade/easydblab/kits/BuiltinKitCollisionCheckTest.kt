package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitRuntime
import com.rustyrazorblade.easydblab.services.KitSourcesProvider
import com.rustyrazorblade.easydblab.services.TemplateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get

/**
 * `start` refuses a collision-checked kit when the workload its `runtime` block declares is already
 * in the cluster, so that runtime must identify exactly this kit's own running workload. Checked
 * across every built-in kit, since any kit can turn on `collision-check`.
 */
class BuiltinKitCollisionCheckTest : BaseKoinTest() {
    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { TemplateService(get(), get()) }
                single { KitSourcesProvider(get()) }
                single { InstallTemplateResolver(get(), get()) }
            },
        )

    private fun collisionCheckedKits(): List<KitConfig> {
        val resolver = get<InstallTemplateResolver>()
        return resolver
            .listAvailableTemplates()
            .mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
            .filter { it.collisionCheck.guards(Constants.Kit.PHASE_START) }
            .also { assertThat(it).isNotEmpty() }
    }

    /** A runtime naming what `install` creates would refuse every `start` after a successful install. */
    @Test
    fun `no collision-checked kit guards start on a helm release its install phase creates`() {
        assertThat(collisionCheckedKits()).allSatisfy { kit ->
            val runtime = kit.runtime
            if (runtime != null && runtime.type == KitRuntime.RuntimeType.HELM) {
                val installReleases = kit.install.filterIsInstance<InstallStep.Helm>().map { it.release }
                assertThat(installReleases)
                    .describedAs("${kit.name}: runtime helm release")
                    .doesNotContain(runtime.release.ifBlank { kit.name })
            }
        }
    }

    /**
     * A kit installed as several instances (`postgres` and `postgres-duckdb`) runs them side by
     * side, so a runtime selector that matches every instance would refuse to start the second.
     * A kit with no runtime block is looked up by its own instance name and needs nothing.
     */
    @Test
    fun `a collision-checked kit that installs as several instances scopes its runtime to the instance`() {
        val multiInstance = collisionCheckedKits().filter { it.extensionArg != null || it.kitRefArg != null }

        assertThat(multiInstance).isNotEmpty()
        assertThat(multiInstance).allSatisfy { kit ->
            val runtime = kit.runtime
            if (runtime != null) {
                val identity = if (runtime.type == KitRuntime.RuntimeType.HELM) runtime.release else runtime.selector
                assertThat(identity).describedAs("${kit.name}: runtime").contains(KIT_NAME_PLACEHOLDER)
            }
        }
    }

    private companion object {
        const val KIT_NAME_PLACEHOLDER = "\${KIT_NAME}"
    }
}
