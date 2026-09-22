package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.services.InstallStep
import com.rustyrazorblade.easydblab.services.InstallTemplateResolver
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
 * in the cluster. A runtime that names what the kit's own `install` phase creates would therefore
 * refuse every `start` after a successful install. Checked across every built-in kit, since any
 * kit can turn on `collision-check`.
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

    @Test
    fun `no collision-checked kit guards start on a helm release its install phase creates`() {
        val resolver = get<InstallTemplateResolver>()
        val kits =
            resolver
                .listAvailableTemplates()
                .mapNotNull { resolver.loadInstallConfig(resolver.resolve(it)) }
                .filter { it.collisionCheck }

        assertThat(kits).isNotEmpty()
        assertThat(kits).allSatisfy { kit ->
            val runtime = kit.runtime
            if (runtime != null && runtime.type == KitRuntime.RuntimeType.HELM) {
                val installReleases = kit.install.filterIsInstance<InstallStep.Helm>().map { it.release }
                assertThat(installReleases)
                    .describedAs("${kit.name}: runtime helm release")
                    .doesNotContain(runtime.release.ifBlank { kit.name })
            }
        }
    }
}
