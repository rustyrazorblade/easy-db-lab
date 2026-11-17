package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.TestContextFactory
import com.rustyrazorblade.easydblab.containers.PackerImage
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.events.RedisPublisher
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import org.assertj.core.api.Assertions.assertThat
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
 * Resolves every definition in the module list the CLI starts with ([KoinModules.getAllModules]).
 *
 * Koin builds a definition only when something asks for it, so a broken registration surfaces as a
 * crash in the one command that needs it, on a live cluster. The usual cause is `factoryOf(::X)` or
 * `singleOf(::X)` for a class whose constructor has a defaulted parameter: those resolve every
 * parameter from Koin and ignore Kotlin defaults, so a defaulted `StoreBlockRenderer`, function or
 * `Clock` fails with `NoDefinitionFoundException`. Koin's `verify()` cannot stand in for this: it
 * reads constructors, not the lambdas that call them, so it flags every defaulted parameter an
 * explicit factory leaves out. Only resolving the graph tells the two apart.
 *
 * Only what reaches outside the process is replaced: the [Context] (so nothing reads the real home
 * or working directory), the SSH connection provider (it loads a key file) and the Redis publisher
 * (it pings Redis when built).
 */
@OptIn(KoinInternalApi::class)
internal class ProductionGraphResolutionTest {
    @Test
    fun `every production definition resolves`(
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
                    runCatching { koin.resolve(definition, context) }
                        .exceptionOrNull()
                        ?.let { "${definition.primaryType.qualifiedName}: ${it.rootCause()}" }
                }

            assertThat(definitions).isNotEmpty
            assertThat(failures).isEmpty()
        } finally {
            app.close()
        }
    }

    private fun Koin.resolve(
        definition: BeanDefinition<*>,
        context: Context,
    ): Any = get(definition.primaryType, definition.qualifier, callParameters(definition, context))

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
