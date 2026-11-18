package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.containers.PackerImage
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.events.RedisPublisher
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.doReturn
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
            single<ProfileRegion> { ProfileRegion.NONE }
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

    /**
     * Every definition in the module list the CLI starts with. Koin builds a definition only when
     * something asks for it, so a broken registration otherwise surfaces as a crash in the one
     * command that needs it, on a live cluster. The usual cause is `factoryOf(::X)` for a class with
     * a defaulted constructor parameter Koin cannot provide (a `StoreBlockRenderer`, a function, a
     * `Clock`). Koin's `verify()` cannot stand in for this: it reads constructors, not the lambdas
     * that call them, so it flags every default an explicit factory leaves out.
     *
     * Nothing is excluded. Besides the two overrides above, the Redis publisher is a mock with a real
     * endpoint (built for real it pings Redis), so the three Redis definitions resolve too; [Docker]
     * and [PackerImage] get the parameters their callers hand in.
     *
     * The definitions are listed through `instanceRegistry`, a [KoinInternalApi]. If this test stops
     * compiling there, or fails before it resolves anything (`definitions` empty), Koin changed that
     * API after an upgrade: that is not a wiring bug, so find the new way to list the definitions.
     * A wiring bug shows up only as an entry in `failures`.
     */
    @OptIn(KoinInternalApi::class)
    @Test
    fun `the production modules resolve every definition`(
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
                            single<RedisPublisher> {
                                mock { on { endpoint } doReturn RedisEndpoint.parse("redis://127.0.0.1:6379/events") }
                            }
                        },
                )
            }
        try {
            val koin = app.koin
            val definitions =
                koin.instanceRegistry.instances.values
                    .map { it.beanDefinition }
                    .distinct()

            val failures =
                definitions.mapNotNull { definition ->
                    runCatching { koin.get<Any>(definition.primaryType, definition.qualifier, koin.callParameters(definition, context)) }
                        .exceptionOrNull()
                        ?.let { "${definition.primaryType.qualifiedName}: ${it.rootCause()}" }
                }

            assertThat(definitions).isNotEmpty
            assertThat(failures).isEmpty()
        } finally {
            app.close()
        }
    }

    /** The parameters a caller hands in at resolution time, for the definitions that take them. */
    private fun Koin.callParameters(
        definition: BeanDefinition<*>,
        context: Context,
    ): ParametersDefinition? =
        when (definition.primaryType) {
            Docker::class -> parametersFor(context)
            PackerImage::class -> parametersFor(get<Docker> { parametersOf(context) })
            else -> null
        }

    private fun parametersFor(value: Any): ParametersDefinition = { parametersOf(value) }

    private fun Throwable.rootCause(): String {
        val root = generateSequence(this) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }
}
