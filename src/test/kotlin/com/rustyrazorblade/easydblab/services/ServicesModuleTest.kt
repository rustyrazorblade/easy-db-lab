package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock
import java.io.File

/**
 * Verifies the Koin wiring declared in [servicesModule] can actually construct services whose
 * constructors carry defaulted parameters of types that are not registered as Koin definitions.
 *
 * Koin's `singleOf`/`factoryOf` reflectively resolve every constructor parameter via `get()` and
 * do NOT honour Kotlin default values. Any registration that uses them for a class with a defaulted
 * parameter of an un-injectable type (e.g. [java.time.Duration]) fails at runtime with
 * `NoDefinitionFoundException`. [DefaultStressJobService.jobPollInterval] is exactly such a case, so
 * its registration must use an explicit lambda that omits the defaulted argument. This test guards
 * that wiring against regressing back to `singleOf`.
 */
class ServicesModuleTest {
    /**
     * Supplies the leaf dependencies of [DefaultStressJobService] so the real [servicesModule]
     * registration can be resolved in isolation without pulling in the full infrastructure graph.
     * These override the corresponding definitions in [servicesModule] (K8sService, TemplateService
     * deps) while leaving the StressJobService binding under test untouched.
     */
    private val leafDependenciesModule =
        module {
            single<K8sService> { mock() }
            single<ClusterStateManager> { mock() }
            single { EventBus() }
            single<User> { mock() }
            single<ImagePullSecretService> { mock() }
        }

    @Test
    fun `servicesModule resolves StressJobService without NoDefinitionFoundException`() {
        val app = koinApplication { modules(servicesModule, leafDependenciesModule) }
        try {
            val koin = app.koin
            assertThatCode { koin.get<StressJobService>() }.doesNotThrowAnyException()
            assertThat(koin.get<StressJobService>()).isInstanceOf(DefaultStressJobService::class.java)
        } finally {
            app.close()
        }
    }

    /**
     * The kit step executor from the production module list, with the [Context] replaced, so nothing
     * reads the real home or working directory, and the SSH connection provider stubbed, as it loads
     * a key file when it is built. Its environment takes the cluster state from
     * [com.rustyrazorblade.easydblab.di.configurationModule], so a module that stops providing it
     * fails here rather than on a user's first kit step.
     */
    @Test
    fun `the production modules resolve WorkloadStepExecutor`(
        @TempDir tempDir: File,
    ) {
        val context = TestContextFactory.createTestContext(tempDir, workingDirectory = tempDir)
        val app =
            koinApplication {
                allowOverride(true)
                modules(
                    KoinModules.getAllModules() +
                        module {
                            single<Context> { context }
                            single<SSHConnectionProvider> { mock() }
                        },
                )
            }
        try {
            assertThat(app.koin.get<WorkloadStepExecutor>()).isNotNull
        } finally {
            app.close()
        }
    }
}
