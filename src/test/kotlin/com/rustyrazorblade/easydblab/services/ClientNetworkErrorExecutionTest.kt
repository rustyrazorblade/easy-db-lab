package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ProbedNode
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.proxy.DefaultProxyAvailability
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import picocli.CommandLine.Command
import java.io.File

/**
 * A bad client network reaches the user through the executor as one error that says what is wrong
 * and how to fix it, once, with no stack trace: a hand-edited `state.json` network (read whenever a
 * kit template is rendered) and a node value the `init` probe cannot read.
 */
class ClientNetworkErrorExecutionTest : BaseKoinTest() {
    private val stateFile: File get() = context.workspaceFile("state.json")
    private val emitted = mutableListOf<Event>()
    private val bus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        emitted.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { ClusterStateManager(stateFile) }
                single { CommandFailureReporter(bus, emptyList()) }
            },
        )

    private fun executor(): DefaultCommandExecutor {
        val states = ClusterStateManager(stateFile)
        return DefaultCommandExecutor(
            context = context,
            clusterStateManager = states,
            requirementCheckDeps =
                RequirementCheckDeps(
                    userConfigProvider = mock<UserConfigProvider>(defaultAnswer = { error("the profile must not be read") }),
                    dockerClientProvider = mock<DockerClientProvider>(),
                    environmentRequirements = EnvironmentRequirements(StateClusterContractSource(states)),
                ),
            resourceManager = mock<ResourceManager>(),
            eventBus = bus,
            proxyPreflight =
                ProxyPreflight(
                    states,
                    mock<SocksProxyService>(defaultAnswer = { error("no tunnel") }),
                    ProxyEnvFile(context.workingDirectory),
                ),
            proxyAvailability = DefaultProxyAvailability(),
            profileSetupProvider = ProfileSetupCommandProvider { error("profile setup must not run") },
        )
    }

    /** Renders a kit's variables from the workspace state, as every kit install and phase does. */
    @Command(name = "renders-kit")
    inner class RendersKit : PicoBaseCommand() {
        override fun execute() {
            TemplateVariables.from(clusterState, kitName = "postgres", storageSize = "10Gi")
        }
    }

    /** Reads the client networks of a cluster whose node reports an address that is not an IP. */
    @Command(name = "probes")
    inner class Probes : PicoBaseCommand() {
        override fun execute() {
            ClientNetworks.probed(listOf(ProbedNode(podCidrs = emptyList(), internalIps = listOf("10.0.0.300"))), emptyList())
        }
    }

    private fun singleError(): String = emitted.filterIsInstance<Event.Command.ExecutionError>().single().error

    @Test
    fun `a hand-edited client network in state json is one error naming the value, the file and the option`() {
        ClusterStateManager(stateFile).save(
            ClusterState(name = "test", clientNetworks = listOf("10.0.0.5/8")),
        )

        val exitCode = executor().executeTopLevel(RendersKit())

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(singleError())
            .contains("10.0.0.5/8")
            .contains("state.json")
            .contains(ClientNetworks.OPTION)
            .doesNotContain("caused by")
            .doesNotContain("\tat ")
    }

    @Test
    fun `a probed value that is not an IP is one error naming the value and the option`() {
        val exitCode = executor().executeTopLevel(Probes())

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(singleError())
            .contains("10.0.0.300")
            .contains(ClientNetworks.OPTION)
            .doesNotContain("caused by")
            .doesNotContain("\tat ")
    }
}
