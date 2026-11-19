package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** What [ProvisionerGuard] lets through in each recorded environment, and what it reports when it stops a command. */
class ProvisionerGuardTest {
    private val events = mutableListOf<Event>()
    private val bus =
        EventBus().also {
            it.addListener(
                object : EventListener {
                    override fun onEvent(envelope: EventEnvelope) {
                        events.add(envelope.event)
                    }

                    override fun close() = Unit
                },
            )
        }

    private fun stateIn(environment: Environment) = ClusterState(name = "test", environment = environment)

    @Test
    fun `EC2 plus K3s has a provisioner, so the command goes ahead and nothing is reported`() {
        ProvisionerGuard.require("up", stateIn(Environment.Ec2K3s), bus)

        assertThat(events).isEmpty()
    }

    @Test
    fun `a workspace with no state is left to the command`() {
        ProvisionerGuard.require("down", null, bus)

        assertThat(events).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unprovisioned")
    fun `an environment with no provisioner fails the command and names the environment`(
        environment: Environment,
        displayName: String,
    ) {
        assertThatThrownBy { ProvisionerGuard.require("up", stateIn(environment), bus) }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("'up'")
            .hasMessageContaining(displayName)

        assertThat(events).containsExactly(Event.Environment.ProvisionerUnavailable("up", displayName))
    }

    companion object {
        @JvmStatic
        fun unprovisioned(): List<Arguments> =
            listOf(
                Arguments.of(Environment.K3sDocker("/data"), "K3s in Docker"),
                Arguments.of(Environment.Kubeconfig("/home/me/.kube/config"), "your Kubernetes cluster"),
            )
    }
}
