package com.rustyrazorblade.easydblab.configuration.kubestatemetrics

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRule
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder

/**
 * Builds the kube-state-metrics K8s resources as typed Fabric8 objects.
 *
 * kube-state-metrics turns the state of Kubernetes objects (pods, deployments, nodes, PVCs, jobs)
 * into Prometheus metrics. Nothing else in the stack reports that: hostmetrics sees processes, not
 * pods, and a kit's own exporter sees only itself. It runs on every cluster regardless of CNI so a
 * dashboard can show pod phase, restarts, and node conditions on Flannel and Cilium alike.
 *
 * One replica on the control node, in the pod network (not hostNetwork), fronted by a ClusterIP
 * Service on [Constants.KubeStateMetrics.METRICS_PORT]. The OTel collector finds the pod through
 * Kubernetes pod discovery filtered to its own node, so exactly one collector scrapes it. RBAC is
 * read-only: every rule grants `list` and `watch` and nothing else.
 */
class KubeStateMetricsManifestBuilder {
    companion object {
        private val NAMESPACE = Constants.K8s.NAMESPACE
        private val NAME = Constants.KubeStateMetrics.NAME
        private const val APP_NAME_LABEL = "app.kubernetes.io/name"
        private const val READINESS_INITIAL_DELAY = 5
        private const val LIVENESS_INITIAL_DELAY = 5
        private const val PROBE_TIMEOUT = 5

        /** The only verbs the ClusterRole grants. kube-state-metrics never writes. */
        val READ_ONLY_VERBS = listOf("list", "watch")

        /**
         * The resources kube-state-metrics collects by default, grouped by API group. This is the
         * upstream ClusterRole, so the default collector set starts without permission errors.
         */
        val WATCHED_RESOURCES: Map<String, List<String>> =
            mapOf(
                "" to
                    listOf(
                        "configmaps",
                        "secrets",
                        "nodes",
                        "pods",
                        "services",
                        "serviceaccounts",
                        "resourcequotas",
                        "replicationcontrollers",
                        "limitranges",
                        "persistentvolumeclaims",
                        "persistentvolumes",
                        "namespaces",
                        "endpoints",
                    ),
                "apps" to listOf("statefulsets", "daemonsets", "deployments", "replicasets"),
                "batch" to listOf("cronjobs", "jobs"),
                "autoscaling" to listOf("horizontalpodautoscalers"),
                "policy" to listOf("poddisruptionbudgets"),
                "certificates.k8s.io" to listOf("certificatesigningrequests"),
                "discovery.k8s.io" to listOf("endpointslices"),
                "storage.k8s.io" to listOf("storageclasses", "volumeattachments"),
                "admissionregistration.k8s.io" to
                    listOf("mutatingwebhookconfigurations", "validatingwebhookconfigurations"),
                "networking.k8s.io" to listOf("networkpolicies", "ingressclasses", "ingresses"),
                "coordination.k8s.io" to listOf("leases"),
                "rbac.authorization.k8s.io" to listOf("clusterrolebindings", "clusterroles", "rolebindings", "roles"),
            )
    }

    /**
     * Builds all kube-state-metrics resources in apply order.
     *
     * @return List of: ServiceAccount, ClusterRole, ClusterRoleBinding, Service, Deployment
     */
    fun buildAllResources(): List<HasMetadata> =
        listOf(
            buildServiceAccount(),
            buildClusterRole(),
            buildClusterRoleBinding(),
            buildService(),
            buildDeployment(),
        )

    /** Builds the ServiceAccount the Deployment runs as. */
    fun buildServiceAccount() =
        ServiceAccountBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .build()

    /** Builds the read-only ClusterRole: one `list`/`watch` rule per API group in [WATCHED_RESOURCES]. */
    fun buildClusterRole() =
        ClusterRoleBuilder()
            .withNewMetadata()
            .withName(NAME)
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .withRules(WATCHED_RESOURCES.map { (apiGroup, resources) -> readOnlyRule(apiGroup, resources) })
            .build()

    private fun readOnlyRule(
        apiGroup: String,
        resources: List<String>,
    ): PolicyRule =
        PolicyRuleBuilder()
            .withApiGroups(apiGroup)
            .withResources(resources)
            .withVerbs(READ_ONLY_VERBS)
            .build()

    /** Builds the ClusterRoleBinding linking the ServiceAccount to the ClusterRole. */
    fun buildClusterRoleBinding() =
        ClusterRoleBindingBuilder()
            .withNewMetadata()
            .withName(NAME)
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .withNewRoleRef()
            .withApiGroup("rbac.authorization.k8s.io")
            .withKind("ClusterRole")
            .withName(NAME)
            .endRoleRef()
            .addNewSubject()
            .withKind("ServiceAccount")
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .endSubject()
            .build()

    /**
     * Builds the ClusterIP Service on the metrics port, so any pod can reach the exporter at
     * `kube-state-metrics.default.svc:8080` without knowing the pod IP.
     */
    fun buildService() =
        ServiceBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .withNewSpec()
            .withType("ClusterIP")
            .addToSelector(APP_NAME_LABEL, NAME)
            .addNewPort()
            .withName("http-metrics")
            .withPort(Constants.KubeStateMetrics.METRICS_PORT)
            .withNewTargetPort(Constants.KubeStateMetrics.METRICS_PORT)
            .withProtocol("TCP")
            .endPort()
            .endSpec()
            .build()

    /**
     * Builds the single-replica Deployment pinned to the control node.
     *
     * Pod network, not hostNetwork: the exporter has no reason to bind a node port, and keeping it
     * off the host stack means it cannot collide with anything the control node already listens on.
     */
    fun buildDeployment() =
        DeploymentBuilder()
            .withNewMetadata()
            .withName(NAME)
            .withNamespace(NAMESPACE)
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewStrategy()
            .withType("Recreate")
            .endStrategy()
            .withNewSelector()
            .addToMatchLabels(APP_NAME_LABEL, NAME)
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels(APP_NAME_LABEL, NAME)
            .endMetadata()
            .withNewSpec()
            .withServiceAccountName(NAME)
            .addToNodeSelector("node-role.kubernetes.io/control-plane", "true")
            .addNewToleration()
            .withKey("node-role.kubernetes.io/control-plane")
            .withOperator("Exists")
            .withEffect("NoSchedule")
            .endToleration()
            .addToContainers(buildContainer())
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

    /**
     * The exporter container. `/livez` answers on the metrics port and `/readyz` on the telemetry
     * port, as upstream wires them; no hostPort on either, since the pod is not on the host stack.
     */
    private fun buildContainer(): Container =
        ContainerBuilder()
            .withName(NAME)
            .withImage(Constants.KubeStateMetrics.IMAGE)
            .addNewPort()
            .withContainerPort(Constants.KubeStateMetrics.METRICS_PORT)
            .withProtocol("TCP")
            .withName("http-metrics")
            .endPort()
            .addNewPort()
            .withContainerPort(Constants.KubeStateMetrics.TELEMETRY_PORT)
            .withProtocol("TCP")
            .withName("telemetry")
            .endPort()
            .withNewLivenessProbe()
            .withNewHttpGet()
            .withPath("/livez")
            .withNewPort(Constants.KubeStateMetrics.METRICS_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(LIVENESS_INITIAL_DELAY)
            .withTimeoutSeconds(PROBE_TIMEOUT)
            .endLivenessProbe()
            .withNewReadinessProbe()
            .withNewHttpGet()
            .withPath("/readyz")
            .withNewPort(Constants.KubeStateMetrics.TELEMETRY_PORT)
            .endHttpGet()
            .withInitialDelaySeconds(READINESS_INITIAL_DELAY)
            .withTimeoutSeconds(PROBE_TIMEOUT)
            .endReadinessProbe()
            .build()
}
