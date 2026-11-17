package com.rustyrazorblade.easydblab.configuration.environment

import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** How `state.json` records the execution environment, and how a value it cannot read fails. */
class EnvironmentPersistenceTest {
    @ParameterizedTest
    @MethodSource("environments")
    fun `each environment round-trips through state json`(
        environment: Environment,
        @TempDir tempDir: File,
    ) {
        val manager = ClusterStateManager(File(tempDir, "state.json"))

        manager.save(ClusterState(name = "test", versions = mutableMapOf(), environment = environment))

        assertThat(manager.load().environment).isEqualTo(environment)
    }

    @Test
    fun `the environment is written with its type id`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")

        ClusterStateManager(stateFile).save(
            ClusterState(name = "test", versions = mutableMapOf(), environment = Environment.K3sDocker("/data")),
        )

        assertThat(stateFile.readText()).contains("\"type\" : \"k3s-docker\"").contains("\"dataDir\" : \"/data\"")
    }

    @Test
    fun `a state with no environment reads as EC2 plus K3s`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}}""")

        assertThat(ClusterStateManager(stateFile).load().environment).isEqualTo(Environment.Ec2K3s)
    }

    @Test
    fun `an unknown environment names the file, the value and the known values`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": {"type": "moon-base"}}""")

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasMessageContaining("moon-base")
            .hasMessageContaining("ec2-k3s, k3s-docker, kubeconfig")
    }

    /** The check calls an explicit null "no environment recorded", so binding must read it the same way. */
    @Test
    fun `an explicit null environment reads as EC2 plus K3s, like a missing one`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": null}""")

        assertThat(ClusterStateManager(stateFile).load().environment).isEqualTo(Environment.Ec2K3s)
    }

    @Test
    fun `an environment with no type is reported as missing its type, never as the value null`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": {}}""")

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasMessageContaining("no type")
            .hasMessageContaining("ec2-k3s, k3s-docker, kubeconfig")
            .hasMessageNotContaining("'null'")
    }

    @Test
    fun `a K3s in Docker environment with no data directory names the missing field`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": {"type": "k3s-docker"}}""")

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasMessageContaining("k3s-docker")
            .hasMessageContaining("dataDir")
    }

    @Test
    fun `a K3s in Docker environment with a blank data directory names the missing field`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": {"type": "k3s-docker", "dataDir": " "}}""")

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasMessageContaining("dataDir")
    }

    @Test
    fun `an environment written as a string instead of an object names the file and the problem`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": "ec2-k3s"}""")

        assertThatThrownBy { ClusterStateManager(stateFile).load() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stateFile.absolutePath)
            .hasMessageContaining("environment")
            .hasMessageContaining("object")
            .hasMessageNotContaining("'null'")
    }

    @Test
    fun `an unknown environment is reported as one line with no parser cause`(
        @TempDir tempDir: File,
    ) {
        val stateFile = File(tempDir, "state.json")
        stateFile.writeText("""{"name": "test", "versions": {}, "environment": {"type": "moon-base"}}""")
        val emitted = mutableListOf<Event>()
        val bus =
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
        val failure = runCatching { ClusterStateManager(stateFile).load() }.exceptionOrNull()!!

        CommandFailureReporter(bus, emptyList()).report(failure)

        val error = (emitted.single() as Event.Command.ExecutionError).error
        assertThat(error).contains("moon-base").doesNotContain("caused by").doesNotContain("\n")
    }

    companion object {
        @JvmStatic
        fun environments() =
            listOf(
                Environment.Ec2K3s,
                Environment.K3sDocker("/work/observability-data"),
                Environment.Kubeconfig("/home/me/.kube/config"),
            )
    }
}
