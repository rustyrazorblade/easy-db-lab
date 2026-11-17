package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [RolloutRevision] keeps only the pods of a workload's latest template. A rollout that replaces
 * crash-looping pods with a fixed template must not be failed by the old pods it is replacing, so
 * the rollout wait inspects only the new ones.
 */
class RolloutRevisionTest {
    private fun dsPod(
        name: String,
        generation: String?,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .apply { if (generation != null) addToLabels("pod-template-generation", generation) }
            .endMetadata()
            .build()

    private fun rsPod(
        name: String,
        replicaSetUid: String,
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .addNewOwnerReference()
            .withKind("ReplicaSet")
            .withUid(replicaSetUid)
            .endOwnerReference()
            .endMetadata()
            .build()

    private fun replicaSet(
        uid: String,
        revision: String,
        ownerUid: String = "deploy-uid",
    ): ReplicaSet =
        ReplicaSetBuilder()
            .withNewMetadata()
            .withUid(uid)
            .addToAnnotations("deployment.kubernetes.io/revision", revision)
            .addNewOwnerReference()
            .withKind("Deployment")
            .withUid(ownerUid)
            .endOwnerReference()
            .endMetadata()
            .build()

    @Test
    fun `a DaemonSet's current pods are those of its generation`() {
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withGeneration(3)
                .endMetadata()
                .build()
        val pods = listOf(dsPod("old", "2"), dsPod("new", "3"), dsPod("unlabelled", null))

        assertThat(RolloutRevision.currentPods(daemonSet, pods).map { it.metadata.name }).containsExactly("new")
    }

    @Test
    fun `a Deployment's current pods are those of the ReplicaSet at its revision`() {
        val deployment =
            DeploymentBuilder()
                .withNewMetadata()
                .withUid("deploy-uid")
                .addToAnnotations("deployment.kubernetes.io/revision", "2")
                .endMetadata()
                .build()
        val replicaSets =
            listOf(
                replicaSet("rs-1", "1"),
                replicaSet("rs-2", "2"),
                replicaSet("other-2", "2", ownerUid = "someone-else"),
            )
        val pods = listOf(rsPod("old", "rs-1"), rsPod("new", "rs-2"), rsPod("foreign", "other-2"))

        assertThat(RolloutRevision.currentPods(deployment, replicaSets, pods).map { it.metadata.name })
            .containsExactly("new")
    }

    @Test
    fun `a Deployment with no revision yet has no current pods`() {
        val deployment =
            DeploymentBuilder()
                .withNewMetadata()
                .withUid("deploy-uid")
                .endMetadata()
                .build()

        assertThat(RolloutRevision.currentPods(deployment, listOf(replicaSet("rs-1", "1")), listOf(rsPod("p", "rs-1"))))
            .isEmpty()
    }
}
