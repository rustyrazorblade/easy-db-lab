package com.rustyrazorblade.easydblab.configuration.kubestatemetrics

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceAccount
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.rbac.ClusterRole
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Shape tests for [KubeStateMetricsManifestBuilder]. The apply-to-K3s, image-pull, and
 * no-resource-limits checks live in `K8sServiceIntegrationTest`; these cover the decisions the
 * builder makes that a real apply would accept silently: read-only RBAC, the RBAC wiring, control
 * node placement, and the port the collector's scrape job expects.
 */
class KubeStateMetricsManifestBuilderTest {
    private val builder = KubeStateMetricsManifestBuilder()

    @Test
    fun `buildAllResources yields the five resources in apply order`() {
        val kinds = builder.buildAllResources().map { it.kind }

        assertThat(kinds).containsExactly("ServiceAccount", "ClusterRole", "ClusterRoleBinding", "Service", "Deployment")
    }

    @Test
    fun `every ClusterRole rule grants list and watch and nothing else`() {
        val role = builder.buildClusterRole()

        assertThat(role.rules).isNotEmpty
        assertThat(role.rules).allSatisfy { rule ->
            assertThat(rule.verbs).containsExactlyInAnyOrder("list", "watch")
        }
        assertThat(role.rules.flatMap { it.verbs }).doesNotContain("get", "create", "update", "patch", "delete", "*")
    }

    @Test
    fun `ClusterRole covers the objects the default collectors read`() {
        val byGroup = builder.buildClusterRole().rules.associate { it.apiGroups.single() to it.resources }

        assertThat(byGroup[""]).contains("pods", "nodes", "persistentvolumeclaims", "persistentvolumes")
        assertThat(byGroup["apps"]).contains("deployments", "daemonsets", "statefulsets")
        assertThat(byGroup["batch"]).contains("jobs")
        assertThat(byGroup["storage.k8s.io"]).contains("storageclasses")
    }

    @Test
    fun `ClusterRoleBinding binds the ServiceAccount the Deployment runs as to the ClusterRole`() {
        val resources = builder.buildAllResources()
        val serviceAccount = resources.filterIsInstance<ServiceAccount>().single()
        val role = resources.filterIsInstance<ClusterRole>().single()
        val binding = resources.filterIsInstance<ClusterRoleBinding>().single()
        val deployment = resources.filterIsInstance<Deployment>().single()

        assertThat(binding.roleRef.kind).isEqualTo("ClusterRole")
        assertThat(binding.roleRef.name).isEqualTo(role.metadata.name)
        val subject = binding.subjects.single()
        assertThat(subject.kind).isEqualTo("ServiceAccount")
        assertThat(subject.name).isEqualTo(serviceAccount.metadata.name)
        assertThat(subject.namespace).isEqualTo(serviceAccount.metadata.namespace)
        assertThat(deployment.spec.template.spec.serviceAccountName).isEqualTo(serviceAccount.metadata.name)
    }

    @Test
    fun `Deployment is one replica on the control node in the pod network`() {
        val spec = builder.buildDeployment().spec
        val pod = spec.template.spec

        assertThat(spec.replicas).isEqualTo(1)
        assertThat(spec.strategy.type).isEqualTo("Recreate")
        assertThat(pod.nodeSelector).containsEntry("node-role.kubernetes.io/control-plane", "true")
        assertThat(pod.tolerations).anySatisfy { it.key == "node-role.kubernetes.io/control-plane" }
        assertThat(pod.hostNetwork ?: false).isFalse()
        assertThat(pod.containers.single().image).isEqualTo(Constants.KubeStateMetrics.IMAGE)
    }

    @Test
    fun `the collector's scrape job and the Service both target the container's metrics port`() {
        val resources = builder.buildAllResources()
        val container =
            resources
                .filterIsInstance<Deployment>()
                .single()
                .spec.template.spec.containers
                .single()
        val service = resources.filterIsInstance<Service>().single()

        val metricsPort = container.ports.single { it.name == "http-metrics" }
        assertThat(metricsPort.containerPort).isEqualTo(Constants.KubeStateMetrics.METRICS_PORT)
        // hostPort is not set: the pod is not on the host stack and nothing scrapes it there.
        assertThat(metricsPort.hostPort ?: 0).isZero()

        val servicePort = service.spec.ports.single()
        assertThat(servicePort.port).isEqualTo(Constants.KubeStateMetrics.METRICS_PORT)
        assertThat(servicePort.targetPort.intVal).isEqualTo(Constants.KubeStateMetrics.METRICS_PORT)
        assertThat(service.spec.selector).isEqualTo(
            resources
                .filterIsInstance<Deployment>()
                .single()
                .spec.selector.matchLabels,
        )
    }
}
