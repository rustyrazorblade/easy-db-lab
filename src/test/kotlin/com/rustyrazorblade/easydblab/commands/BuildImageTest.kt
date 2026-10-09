package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.services.CommandExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * `build-image` runs the base and the Cassandra image builds as nested commands. A phase that
 * fails must fail `build-image` and stop it before the next phase; the executor that runs each
 * phase answers with the exit code [exitCodes] gives that phase's command class.
 */
class BuildImageTest : BaseKoinTest() {
    private val ran = mutableListOf<KClass<*>>()
    private val exitCodes = mutableMapOf<KClass<*>, Int>()
    private val events = mutableListOf<Event>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<CommandExecutor> {
                    object : CommandExecutor {
                        override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                            val command = commandFactory()
                            ran.add(command::class)
                            return exitCodes[command::class] ?: 0
                        }

                        override fun <T : PicoCommand> schedule(commandFactory: () -> T) = Unit
                    }
                }
            },
        )

    @BeforeEach
    fun captureEvents() {
        getKoin().get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events.add(envelope.event)
                }

                override fun close() = Unit
            },
        )
    }

    @Test
    fun `both phases succeed, base first`() {
        assertThatCode { BuildImage().execute() }.doesNotThrowAnyException()

        assertThat(ran).containsExactly(BuildBaseImage::class, BuildCassandraImage::class)
        assertThat(events.filterIsInstance<Event.Ami.BuildPhaseFailed>()).isEmpty()
    }

    @Test
    fun `a failed base build fails build-image, names the phase, and does not build the Cassandra image`() {
        exitCodes[BuildBaseImage::class] = Constants.ExitCodes.ERROR

        assertThatThrownBy { BuildImage().execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(ran).containsExactly(BuildBaseImage::class)
        assertThat(events.filterIsInstance<Event.Ami.BuildPhaseFailed>().single())
            .isEqualTo(Event.Ami.BuildPhaseFailed(phase = "build-base", exitCode = Constants.ExitCodes.ERROR))
    }

    @Test
    fun `a failed Cassandra build fails build-image and names the phase`() {
        exitCodes[BuildCassandraImage::class] = Constants.ExitCodes.ERROR

        assertThatThrownBy { BuildImage().execute() }.isInstanceOf(CommandFailedException::class.java)

        assertThat(ran).containsExactly(BuildBaseImage::class, BuildCassandraImage::class)
        val failed = events.filterIsInstance<Event.Ami.BuildPhaseFailed>().single()
        assertThat(failed.phase).isEqualTo("build-cassandra")
        assertThat(failed.isError()).isTrue()
    }
}
