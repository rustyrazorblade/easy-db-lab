package com.rustyrazorblade.easydblab.configuration.agents

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder

/**
 * Delivers Java agents to a JVM container without touching the host.
 *
 * A `java-agents` `emptyDir` is added to the pod; one init container per agent copies its jar from
 * the agent's image into it; the JVM container mounts it read-only at [Constants.Kit.AGENTS_MOUNT]
 * and loads each agent with [flag]. The init containers meet Pod Security "restricted", so the pod
 * runs where host paths and privileged containers are refused. Pure: it returns a new spec.
 */
object JavaAgentDelivery {
    /** The pod volume the agents are copied into. */
    const val VOLUME = "java-agents"

    /** Where each init container mounts [VOLUME] to write its jar. */
    private const val INIT_MOUNT = "/agents"

    /** The JVM option that loads [agent] from the mounted volume. */
    fun flag(agent: JavaAgent): String = "-javaagent:${Constants.Kit.AGENTS_MOUNT}/${agent.fileName}"

    /**
     * Returns [spec] with [agents] delivered to the container named [container]: the volume, one
     * init container per agent after any the pod already has, and a read-only mount.
     *
     * @throws IllegalArgumentException if [spec] has no container named [container].
     */
    fun applyTo(
        spec: PodSpec,
        container: String,
        agents: List<JavaAgent>,
    ): PodSpec {
        require(spec.containers.any { it.name == container }) {
            "Pod has no container named $container to deliver Java agents to"
        }
        return PodSpecBuilder(spec)
            .addToVolumes(
                VolumeBuilder()
                    .withName(VOLUME)
                    .withNewEmptyDir()
                    .endEmptyDir()
                    .build(),
            ).addAllToInitContainers(agents.map(::copier))
            .withContainers(spec.containers.map { if (it.name == container) withAgentMount(it) else it })
            .build()
    }

    private fun withAgentMount(container: Container): Container =
        ContainerBuilder(container)
            .addToVolumeMounts(
                VolumeMountBuilder()
                    .withName(VOLUME)
                    .withMountPath(Constants.Kit.AGENTS_MOUNT)
                    .withReadOnly(true)
                    .build(),
            ).build()

    private fun copier(agent: JavaAgent): Container =
        ContainerBuilder()
            .withName("${agent.name.lowercase()}-agent")
            .withImage(agent.image)
            .withImagePullPolicy("IfNotPresent")
            .withCommand("cp", agent.pathInImage, "$INIT_MOUNT/${agent.fileName}")
            .withNewSecurityContext()
            .withRunAsNonRoot(true)
            .withRunAsUser(Constants.Kit.Agents.RUN_AS_USER)
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities()
            .withDrop(Constants.PodSecurity.RESTRICTED_DROPPED_CAPABILITIES)
            .endCapabilities()
            .withNewSeccompProfile()
            .withType(Constants.PodSecurity.RESTRICTED_SECCOMP_PROFILE)
            .endSeccompProfile()
            .endSecurityContext()
            .withVolumeMounts(VolumeMountBuilder().withName(VOLUME).withMountPath(INIT_MOUNT).build())
            .build()
}
