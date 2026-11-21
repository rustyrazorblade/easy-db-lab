package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresCapability
import com.rustyrazorblade.easydblab.commands.cassandra.UseCassandra
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.NodeCapability
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

    @RequiresCapability(NodeCapability.NODE_SSH, NodeCapability.AWS_ACCOUNT, alternative = "the kit")
    private class SshAndAwsCommand : PicoCommand {
        override fun execute() = Unit
    }

    private fun record(
        environment: Environment,
        capabilities: Set<NodeCapability>? = null,
    ) = states.replace(ClusterState(name = "t", environment = environment, capabilities = capabilities))

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

    /** The capabilities are ANDed: the command runs only where every one is present. */
    @Test
    fun `a command needing several capabilities runs where all are present`() {
        record(Environment.Ec2K3s)

        assertThat(requirements.unavailable(SshAndAwsCommand())).isNull()
    }

    @Test
    fun `a command needing several capabilities is unavailable when only one is missing, naming that one`() {
        // An EC2 account with no SSH to its nodes: AWS_ACCOUNT is present, NODE_SSH is not.
        record(Environment.Ec2K3s, capabilities = setOf(NodeCapability.S3))

        val event = requirements.unavailable(SshAndAwsCommand(), "ssh-and-aws")

        assertThat(event).isNotNull()
        assertThat(event?.missing).containsExactly(NodeCapability.NODE_SSH)
        assertThat(event?.alternative).isEqualTo("the kit")
    }

    @Test
    fun `a command needing several capabilities lists every missing one, in enum order`() {
        record(Environment.K3sDocker("/data"))

        assertThat(requirements.unavailable(SshAndAwsCommand())?.missing)
            .containsExactly(NodeCapability.NODE_SSH, NodeCapability.AWS_ACCOUNT)
    }

    /** `cassandra use` also requires NODE_SSH (design): an EC2 workspace recorded without it cannot run it. */
    @Test
    fun `cassandra use needs node SSH as well as the AWS account`() {
        record(Environment.Ec2K3s, capabilities = setOf(NodeCapability.S3))

        assertThat(requirements.unavailable(UseCassandra())?.missing).containsExactly(NodeCapability.NODE_SSH)
    }
}
