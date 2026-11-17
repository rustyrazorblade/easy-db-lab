package com.rustyrazorblade.easydblab.configuration.agents

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JavaAgentDeliveryTest {
    private val spec: PodSpec =
        PodSpecBuilder()
            .withContainers(
                ContainerBuilder().withName("jvm").withImage("app:1").build(),
                ContainerBuilder().withName("sidecar").withImage("side:1").build(),
            ).build()

    private fun delivered(vararg agents: JavaAgent): PodSpec = JavaAgentDelivery.applyTo(spec, "jvm", agents.toList())

    private fun PodSpec.container(name: String): Container = containers.single { it.name == name }

    /** The file an init container writes: the last argument of its `cp`, the target. */
    private fun Container.copyTarget(): String = command.last()

    @Test
    fun `every agent flag names a file one init container copies into the mounted emptyDir`() {
        val result = delivered(JavaAgent.OTEL, JavaAgent.PYROSCOPE)
        val mount = result.container("jvm").volumeMounts.single()
        val volume = result.volumes.single { it.name == mount.name }

        assertThat(volume.emptyDir).isNotNull()
        assertThat(volume.hostPath).isNull()
        assertThat(mount.mountPath).isEqualTo(Constants.Kit.AGENTS_MOUNT)
        assertThat(mount.readOnly).isTrue()
        for (agent in JavaAgent.entries) {
            val flag = JavaAgentDelivery.flag(agent)
            assertThat(flag).startsWith("-javaagent:${Constants.Kit.AGENTS_MOUNT}/")
            val path = flag.removePrefix("-javaagent:")
            val writer =
                result.initContainers.single { init ->
                    val initMount = init.volumeMounts.single { it.name == volume.name }
                    init.copyTarget() == path.replaceFirst(Constants.Kit.AGENTS_MOUNT, initMount.mountPath)
                }
            assertThat(writer.image).isEqualTo(agent.image)
            assertThat(writer.command).contains(agent.pathInImage)
        }
    }

    @Test
    fun `one init container per agent, each meeting Pod Security restricted`() {
        val inits = delivered(JavaAgent.OTEL, JavaAgent.PYROSCOPE).initContainers

        assertThat(inits.map { it.image }).containsExactly(JavaAgent.OTEL.image, JavaAgent.PYROSCOPE.image)
        for (init in inits) {
            val security = init.securityContext
            assertThat(security.runAsNonRoot).isTrue()
            assertThat(security.allowPrivilegeEscalation).isFalse()
            assertThat(security.capabilities.drop).containsExactly("ALL")
            assertThat(security.seccompProfile.type).isEqualTo("RuntimeDefault")
            assertThat(init.volumeMounts.single().readOnly == true).describedAs("init container writes the volume").isFalse()
        }
    }

    @Test
    fun `only the named container gets the mount, and existing init containers run first`() {
        val existing = ContainerBuilder().withName("first").withImage("first:1").build()
        val withInit = PodSpecBuilder(spec).withInitContainers(existing).build()

        val result = JavaAgentDelivery.applyTo(withInit, "jvm", listOf(JavaAgent.PYROSCOPE))

        assertThat(result.container("sidecar").volumeMounts).isEmpty()
        assertThat(result.initContainers.map { it.name }.first()).isEqualTo("first")
        assertThat(result.initContainers).hasSize(2)
        assertThat(spec.initContainers).isEmpty()
    }

    @Test
    fun `a container the pod does not have is refused, naming it`() {
        assertThatThrownBy { JavaAgentDelivery.applyTo(spec, "missing", listOf(JavaAgent.OTEL)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing")
    }

    @Test
    fun `the OTel agent image is the catalog's agent version, the version the AMI ships`() {
        assertThat(JavaAgent.OTEL.image)
            .isEqualTo("${Constants.Kit.Agents.OTEL_IMAGE_REPOSITORY}:${System.getProperty("easydblab.otelAgentVersion")}")
        assertThat(System.getProperty("easydblab.otelAgentVersion")).isEqualTo(Constants.OtelJavaAgent.VERSION)
        assertThat(JavaAgent.PYROSCOPE.image).endsWith(":${Constants.PyroscopeJavaAgent.VERSION}")
    }
}
