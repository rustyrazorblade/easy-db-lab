package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.providers.ssh.DirectSshRoute
import com.rustyrazorblade.easydblab.proxy.DefaultProxyAvailability
import com.rustyrazorblade.easydblab.proxy.EnvScriptWriter
import com.rustyrazorblade.easydblab.proxy.ProxyPreflight
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.proxy.WorkspaceShellTools
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import picocli.CommandLine.Command
import java.io.File

/**
 * A `state.json` this easy-db-lab cannot read reaches the user through the executor, not only from
 * [ClusterStateManager.load]: the proxy preflight that runs before every command ignores a state it
 * cannot read (it only records reachability), so the command's own load, or the profile check's, must
 * still report it. Each case runs a real command against a real file and expects exactly one error
 * naming the file, a non-zero exit, and the command body never run.
 */
class UnreadableStateExecutionTest : BaseKoinTest() {
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
    private var ran = false

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
                    WorkspaceShellTools(ToolWrapperInstaller(), EnvScriptWriter(DirectSshRoute(22))),
                    context.workingDirectory,
                ),
            proxyAvailability = DefaultProxyAvailability(),
            profileSetupProvider = ProfileSetupCommandProvider { error("profile setup must not run") },
        )
    }

    /** A plain command whose body reads the cluster state, as most commands do. */
    @Command(name = "reads-state")
    inner class ReadsState : PicoBaseCommand() {
        override fun execute() {
            clusterState.name
            ran = true
        }
    }

    /** A command with a profile check, which resolves the environment before the body runs. */
    @RequireProfileSetup
    @Command(name = "profile-command")
    inner class ProfileCommand : PicoBaseCommand() {
        override fun execute() {
            ran = true
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun `an unreadable state fails the command with one error naming the file and the problem`(
        command: String,
        environment: String,
        expected: String,
    ) {
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": $environment}""")

        val exitCode = executor().executeTopLevel(if (command == "reads-state") ReadsState() else ProfileCommand())

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(ran).isFalse()
        val error = emitted.filterIsInstance<Event.Command.ExecutionError>().single().error
        assertThat(error).contains(stateFile.absolutePath).contains(expected).doesNotContain("caused by")
    }

    /** A command whose body asks the datacenter of the state it loaded, with no profile region to fall back on. */
    @Command(name = "reads-datacenter")
    inner class ReadsDatacenter : PicoBaseCommand() {
        override fun execute() {
            ClusterContract.of(clusterState).datacenter(ProfileRegion.NONE)
            ran = true
        }
    }

    /**
     * An EC2 state that records no region, with no profile region to fall back on, reaches the user as one
     * error that says what is missing and how to fix it, never as a blank datacenter or a stack trace.
     */
    @Test
    fun `an EC2 state with no region and no profile region fails the command with one clear error`() {
        stateFile.writeText("""{"name": "test", "versions": {}}""")

        val exitCode = executor().executeTopLevel(ReadsDatacenter())

        assertThat(exitCode).isEqualTo(Constants.ExitCodes.ERROR)
        assertThat(ran).isFalse()
        val error = emitted.filterIsInstance<Event.Command.ExecutionError>().single().error
        assertThat(error)
            .contains("records no region")
            .contains("profile setup")
            .doesNotContain("caused by")
            .doesNotContain("\tat ")
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> =
            listOf("reads-state", "profile-command").flatMap { command ->
                listOf(
                    Arguments.of(command, """{"type": "moon-base"}""", "moon-base"),
                    Arguments.of(command, """"ec2-k3s"""", "must be an object"),
                    Arguments.of(command, """{"type": "k3s-docker"}""", "dataDir"),
                )
            }
    }
}
