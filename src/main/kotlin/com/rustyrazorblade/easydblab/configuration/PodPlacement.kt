package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.PodSpecFluent
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder

/**
 * Pins a pod to the control node: the control-plane node selector and a toleration for the
 * `NoSchedule` taint that keeps other workloads off it.
 *
 * Every control-plane workload places itself through this one function, so a change to where they
 * run (a different label, a second taint, an environment where the control plane is not tainted)
 * is a change here and nowhere else.
 */
fun <A : PodSpecFluent<A>> PodSpecFluent<A>.onControlPlane(): A =
    addToNodeSelector(Constants.K8s.CONTROL_PLANE_LABEL, "true")
        .addNewToleration()
        .withKey(Constants.K8s.CONTROL_PLANE_LABEL)
        .withOperator("Exists")
        .withEffect("NoSchedule")
        .endToleration()

/**
 * Puts a pod on the node's network with the DNS policy that still resolves in-cluster names,
 * so a workload that must bind a node port (or reach the node's IAM role) is written one way.
 */
fun <A : PodSpecFluent<A>> PodSpecFluent<A>.hostNetworked(): A =
    withHostNetwork(true)
        .withDnsPolicy(Constants.K8s.HOST_NETWORK_DNS_POLICY)

/**
 * A single-replica Deployment for a control-plane workload: the name labels on the Deployment,
 * its selector and its pod template, and the `Recreate` strategy, so a replacement never runs
 * beside the pod it replaces (these workloads own node ports and host paths a second copy
 * would fight over).
 *
 * The pod spec starts already placed with [onControlPlane]; [podSpec] adds the rest.
 *
 * @param name The Deployment's `metadata.name`
 * @param namespace The Deployment's namespace
 * @param appLabel The value of the `app.kubernetes.io/name` label; defaults to [name]
 * @param labelKey The label key the selector matches on
 * @param podSpec Fills in the pod: containers, volumes, host networking and the rest
 */
fun controlPlaneDeployment(
    name: String,
    namespace: String,
    appLabel: String = name,
    labelKey: String = Constants.K8s.APP_NAME_LABEL,
    podSpec: PodSpecBuilder.() -> Unit,
): Deployment =
    DeploymentBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace(namespace)
        .addToLabels(labelKey, appLabel)
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewSelector()
        .addToMatchLabels(labelKey, appLabel)
        .endSelector()
        .withNewStrategy()
        .withType("Recreate")
        .endStrategy()
        .withNewTemplate()
        .withNewMetadata()
        .addToLabels(labelKey, appLabel)
        .endMetadata()
        .withSpec(PodSpecBuilder().apply { onControlPlane() }.apply(podSpec).build())
        .endTemplate()
        .endSpec()
        .build()
