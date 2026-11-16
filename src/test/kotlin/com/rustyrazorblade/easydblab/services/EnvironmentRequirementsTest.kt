package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import com.rustyrazorblade.easydblab.kernel.DeclaresEnvironment
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** When the AWS profile check applies: only where the target environment has an AWS account. */
class EnvironmentRequirementsTest {
    @TempDir
    lateinit var workspace: File

    private val states by lazy { ClusterStateManager(File(workspace, "state.json")) }
    private val requirements by lazy { EnvironmentRequirements(StateClusterContractSource(states)) }

    @RequireProfileSetup
    private class ProfileCommand : PicoCommand {
        override fun execute() = Unit
    }

    @RequireProfileSetup
    private class DeclaringCommand(
        private val environment: Environment,
    ) : PicoCommand,
        DeclaresEnvironment {
        override fun declaredEnvironment(): Environment = environment

        override fun execute() = Unit
    }

    private class PlainCommand : PicoCommand {
        override fun execute() = Unit
    }

    private fun record(environment: Environment) =
        states.save(ClusterState(name = "t", versions = mutableMapOf(), environment = environment))

    @Test
    fun `an EC2 workspace still requires the profile`() {
        record(Environment.Ec2K3s)

        assertThat(requirements.needsProfile(ProfileCommand())).isTrue()
    }

    @Test
    fun `outside a workspace the target is EC2 and the profile is required`() {
        assertThat(requirements.needsProfile(ProfileCommand())).isTrue()
    }

    @Test
    fun `an EC2 init in a local workspace requires the profile for the environment it will record`() {
        record(Environment.K3sDocker("/data"))

        assertThat(requirements.needsProfile(DeclaringCommand(Environment.Ec2K3s))).isTrue()
        assertThat(requirements.needsProfile(DeclaringCommand(Environment.K3sDocker("/data")))).isFalse()
    }

    @Test
    fun `a command without the annotation never needs the profile`() {
        record(Environment.Ec2K3s)

        assertThat(requirements.needsProfile(PlainCommand())).isFalse()
    }
}
