package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder

/**
 * The Pods the capability probe submits with `dryRun=All` to learn what a namespace admits.
 *
 * The [baseline] Pod meets Pod Security "restricted", so any namespace that admits a Pod at all
 * admits it. Each of the [probes] changes only the fields its capability needs (one, except privileged,
 * which must also lift `allowPrivilegeEscalation`), so a rejection is attributable to one capability. They are Pods, never a workload controller: Pod
 * Security enforce applies to Pods, and a dry-run DaemonSet would pass with only a warning.
 */
object CapabilityProbePods {
    private const val CONTAINER = "probe"
    private const val HOST_VOLUME = "host"

    /** The restricted Pod every probe Pod is built from. */
    fun baseline(namespace: String): Pod = restricted(namespace, "baseline").build()

    /** One Pod per admission capability, each differing from [baseline] only in the field it tests. */
    fun probes(namespace: String): Map<NodeCapability, Pod> =
        linkedMapOf(
            NodeCapability.PRIVILEGED to privileged(namespace),
            NodeCapability.HOST_NETWORK to hostNetwork(namespace),
            NodeCapability.HOST_PATH to hostPath(namespace),
        )

    /**
     * Pod validation refuses `privileged: true` next to `allowPrivilegeEscalation: false` (422, even
     * under `dryRun=All`), so this probe necessarily lifts the escalation flag too; privileged implies
     * escalation, so the rejection still names only this capability.
     */
    private fun privileged(namespace: String): Pod =
        restricted(namespace, "privileged")
            .editSpec()
            .editFirstContainer()
            .editSecurityContext()
            .withPrivileged(true)
            .withAllowPrivilegeEscalation(true)
            .endSecurityContext()
            .endContainer()
            .endSpec()
            .build()

    private fun hostNetwork(namespace: String): Pod =
        restricted(namespace, "host-network")
            .editSpec()
            .withHostNetwork(true)
            .endSpec()
            .build()

    private fun hostPath(namespace: String): Pod =
        restricted(namespace, "host-path")
            .editSpec()
            .addNewVolume()
            .withName(HOST_VOLUME)
            .withNewHostPath()
            .withPath(Constants.Capabilities.PROBE_HOST_PATH)
            .endHostPath()
            .endVolume()
            .editFirstContainer()
            .addNewVolumeMount()
            .withName(HOST_VOLUME)
            .withMountPath(Constants.Capabilities.PROBE_HOST_PATH)
            .withReadOnly(true)
            .endVolumeMount()
            .endContainer()
            .endSpec()
            .build()

    private fun restricted(
        namespace: String,
        suffix: String,
    ): PodBuilder =
        PodBuilder()
            .withNewMetadata()
            .withName("${Constants.Capabilities.PROBE_POD_PREFIX}-$suffix")
            .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
            .withNewSecurityContext()
            .withRunAsNonRoot(true)
            .withRunAsUser(Constants.Capabilities.PROBE_RUN_AS_USER)
            .withNewSeccompProfile()
            .withType(Constants.Capabilities.PROBE_SECCOMP_PROFILE)
            .endSeccompProfile()
            .endSecurityContext()
            .addNewContainer()
            .withName(CONTAINER)
            .withImage(Constants.Capabilities.PROBE_IMAGE)
            .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities()
            .withDrop(Constants.Capabilities.PROBE_DROPPED_CAPABILITIES)
            .endCapabilities()
            .endSecurityContext()
            .endContainer()
            .endSpec()
}
