package com.rustyrazorblade.easydblab.configuration.inspektorgadget

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.HostPathVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder

/**
 * Builds all inspektor-gadget K8s resources as typed Fabric8 objects.
 *
 * inspektor-gadget provides eBPF-based kernel-level tracing and inspection gadgets
 * for Kubernetes pods and nodes. The `ig` daemon runs on every node and exposes
 * gadget functionality via the K8s API using gadget.kinvolk.io CRDs.
 *
 * Requires privileged mode, hostPID, and hostNetwork for eBPF access.
 */
class InspektorGadgetManifestBuilder {
    companion object {
        private const val NAMESPACE = "default"
        private const val APP_LABEL = "inspektor-gadget"
        private const val IMAGE = "ghcr.io/inspektor-gadget/inspektor-gadget:v0.35.0"
        private const val SERVICE_ACCOUNT_NAME = "inspektor-gadget"
        private const val CLUSTER_ROLE_NAME = "inspektor-gadget"
    }

    /**
     * Builds all inspektor-gadget K8s resources in apply order.
     *
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, DaemonSet
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildDaemonSet(),
        )

    /**
     * Builds the ServiceAccount for inspektor-gadget pods.
     */
    fun buildServiceAccount() =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(SERVICE_ACCOUNT_NAME)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .build()

    /**
     * Builds the ClusterRole granting inspektor-gadget access to pods, nodes,
     * namespaces, and gadget-related custom resources.
     */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .addNewRule()
            .withApiGroups("")
            .withResources("pods", "nodes", "namespaces", "services", "endpoints")
            .withVerbs("get", "watch", "list")
            .endRule()
            .addNewRule()
            .withApiGroups("")
            .withResources("events")
            .withVerbs("get", "list", "watch", "create", "patch", "update")
            .endRule()
            .addNewRule()
            .withApiGroups("gadget.kinvolk.io")
            .withResources("*")
            .withVerbs("get", "list", "watch", "create", "patch", "update", "delete")
            .endRule()
            .build()

    /**
     * Builds the ClusterRoleBinding linking the ServiceAccount to the ClusterRole.
     */
    fun buildClusterRoleBinding() =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(CLUSTER_ROLE_NAME)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewRoleRef()
            .withApiGroup("rbac.authorization.k8s.io")
            .withKind("ClusterRole")
            .withName(CLUSTER_ROLE_NAME)
            .endRoleRef()
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName(SERVICE_ACCOUNT_NAME)
            .withNamespace(NAMESPACE)
            .endSubject()
            .build()

    /**
     * Builds the inspektor-gadget DaemonSet running the `ig` daemon.
     *
     * Runs on all nodes with hostNetwork, hostPID, and privileged mode for eBPF access.
     * Mounts the BPF filesystem, container runtime socket, kernel debug, and proc directories.
     */
    @Suppress("LongMethod")
    fun buildDaemonSet() =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName(APP_LABEL)
            .withNamespace(NAMESPACE)
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withNewSelector()
            .addToMatchLabels("app.kubernetes.io/name", APP_LABEL)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("app.kubernetes.io/name", APP_LABEL)
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(SERVICE_ACCOUNT_NAME)
            .withHostNetwork(true)
            .withHostPID(true)
            .withDnsPolicy("ClusterFirstWithHostNet")
            .addNewToleration()
            .withOperator("Exists")
            .endToleration()
            .addNewContainer()
            .withName(APP_LABEL)
            .withImage(IMAGE)
            .withArgs("daemon", "--host", "--runtimes=containerd")
            .withSecurityContext(
                SecurityContextBuilder()
                    .withPrivileged(true)
                    .withRunAsUser(0L)
                    .withRunAsGroup(0L)
                    .build(),
            ).addToVolumeMounts(
                VolumeMountBuilder()
                    .withName("sys-fs-bpf")
                    .withMountPath("/sys/fs/bpf")
                    .build(),
                VolumeMountBuilder()
                    .withName("sys-kernel-debug")
                    .withMountPath("/sys/kernel/debug")
                    .build(),
                VolumeMountBuilder()
                    .withName("proc")
                    .withMountPath("/proc")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("modules")
                    .withMountPath("/lib/modules")
                    .withReadOnly(true)
                    .build(),
                VolumeMountBuilder()
                    .withName("containerd-sock")
                    .withMountPath("/run/containerd/containerd.sock")
                    .build(),
                VolumeMountBuilder()
                    .withName("run")
                    .withMountPath("/run")
                    .build(),
            ).endContainer()
            .addNewVolume()
            .withName("sys-fs-bpf")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/sys/fs/bpf")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("sys-kernel-debug")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/sys/kernel/debug")
                    .withType("DirectoryOrCreate")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("proc")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/proc")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("modules")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/lib/modules")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("containerd-sock")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/run/containerd/containerd.sock")
                    .withType("Socket")
                    .build(),
            ).endVolume()
            .addNewVolume()
            .withName("run")
            .withHostPath(
                HostPathVolumeSourceBuilder()
                    .withPath("/run")
                    .build(),
            ).endVolume()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
}
