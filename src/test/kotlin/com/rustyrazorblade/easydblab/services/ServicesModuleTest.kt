package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.mock

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
}
