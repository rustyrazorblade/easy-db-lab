package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.ControllerRevision
import io.fabric8.kubernetes.api.model.apps.ControllerRevisionBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [RolloutRevision] keeps only the pods of a workload's latest template. A rollout that replaces
 * crash-looping pods with a fixed template must not be failed by the old pods it is replacing, so
 * the rollout wait inspects only the new ones, and none at all until the controller has observed
 * the latest generation.
 */
class RolloutRevisionTest {
    private fun dsPod(
        name: String,
        generation: String?,
        node: String? = null,
        terminating: Boolean = false,
        hash: String? = null,
        ownerUid: String = "ds-uid",
    ): Pod =
        PodBuilder()
            .withNewMetadata()
            .withName(name)
            .apply { if (generation != null) addToLabels("pod-template-generation", generation) }
            .apply { if (hash != null) addToLabels("controller-revision-hash", hash) }
            .apply { if (terminating) withDeletionTimestamp("2026-11-17T00:00:00Z") }
            .addNewOwnerReference()
            .withKind("DaemonSet")
            .withUid(ownerUid)
            .endOwnerReference()
            .endMetadata()
            .withNewSpec()
            .withNodeName(node)
            .endSpec()
            .build()

    @Test
    fun `a DaemonSet's stranded pods are terminating old pods on nodes with no current pod`() {
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withUid("ds-uid")
                .withGeneration(3)
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(3)
                .endStatus()
                .build()
        val pods =
            listOf(
                dsPod("stranded", "2", node = "db7", terminating = true),
                dsPod("replaced", "2", node = "db0", terminating = true),
                dsPod("current", "3", node = "db0"),
                dsPod("old-not-deleted", "2", node = "db8"),
                dsPod("unscheduled", "2", node = null, terminating = true),
            )

        assertThat(RolloutRevision.strandedPods(daemonSet, emptyList(), pods).map { it.metadata.name }).containsExactly("stranded")
    }

    @Test
    fun `a DaemonSet whose controller has not observed its latest generation has no stranded pods`() {
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withUid("ds-uid")
                .withGeneration(4)
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(3)
                .endStatus()
                .build()

        assertThat(
            RolloutRevision.strandedPods(daemonSet, emptyList(), listOf(dsPod("old", "3", node = "db7", terminating = true))),
        ).isEmpty()
    }

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
                .withUid("ds-uid")
                .withGeneration(3)
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(3)
                .endStatus()
                .build()
        val pods = listOf(dsPod("old", "2"), dsPod("new", "3"), dsPod("unlabelled", null))

        assertThat(RolloutRevision.currentPods(daemonSet, emptyList(), pods).map { it.metadata.name }).containsExactly("new")
    }

    @Test
    fun `a DaemonSet's current pods follow its template generation, not a spec-only generation bump`() {
        // An update-strategy change bumps metadata.generation to 4 but leaves the template at 3.
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withUid("ds-uid")
                .withGeneration(4)
                .addToAnnotations("deprecated.daemonset.template.generation", "3")
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(4)
                .endStatus()
                .build()
        val pods = listOf(dsPod("old", "2"), dsPod("current", "3"))

        assertThat(RolloutRevision.currentPods(daemonSet, emptyList(), pods).map { it.metadata.name }).containsExactly("current")
    }

    /** A DaemonSet at generation [generation], observed by its controller. */
    private fun observedDaemonSet(generation: Long) =
        DaemonSetBuilder()
            .withNewMetadata()
            .withUid("ds-uid")
            .withGeneration(generation)
            .addToAnnotations("deprecated.daemonset.template.generation", generation.toString())
            .endMetadata()
            .withNewStatus()
            .withObservedGeneration(generation)
            .endStatus()
            .build()

    private fun revision(
        hash: String,
        revision: Long,
        ownerUid: String = "ds-uid",
    ): ControllerRevision =
        ControllerRevisionBuilder()
            .withNewMetadata()
            .withName("ds-$hash")
            .addToLabels("controller-revision-hash", hash)
            .addNewOwnerReference()
            .withKind("DaemonSet")
            .withUid(ownerUid)
            .endOwnerReference()
            .endMetadata()
            .withRevision(revision)
            .build()

    @Test
    fun `a DaemonSet's pods owned by another controller are neither current nor stranded`() {
        val daemonSet = observedDaemonSet(3)
        val pods =
            listOf(
                dsPod("own", "3", node = "db0"),
                dsPod("foreign", "3", node = "db1", ownerUid = "other-ds"),
                dsPod("foreign-old", "2", node = "db7", terminating = true, ownerUid = "other-ds"),
            )

        assertThat(RolloutRevision.currentPods(daemonSet, emptyList(), pods).map { it.metadata.name }).containsExactly("own")
        assertThat(RolloutRevision.strandedPods(daemonSet, emptyList(), pods)).isEmpty()
    }

    @Test
    fun `a DaemonSet's template set back from B to A counts a kept pod of A as current, by its revision hash`() {
        // A (revision 1) -> B (revision 2) -> A again: the controller moves A's ControllerRevision to
        // revision 3, and a node whose A pod was never replaced keeps it, generation label and all.
        val daemonSet = observedDaemonSet(3)
        val revisions = listOf(revision("hash-a", 3), revision("hash-b", 2), revision("hash-x", 9, ownerUid = "other-ds"))
        val pods =
            listOf(
                dsPod("kept-a", "1", node = "db0", hash = "hash-a"),
                dsPod("b", "2", node = "db1", hash = "hash-b"),
                dsPod("new-a", "3", node = "db2", hash = "hash-a"),
                dsPod("foreign-hash", "1", node = "db3", hash = "hash-x"),
                dsPod("old-b-terminating", "2", node = "db0", terminating = true, hash = "hash-b"),
            )

        assertThat(RolloutRevision.currentPods(daemonSet, revisions, pods).map { it.metadata.name })
            .containsExactly("kept-a", "new-a")
        // db0 holds the kept A pod, so its terminating B pod is not stranded.
        assertThat(RolloutRevision.strandedPods(daemonSet, revisions, pods)).isEmpty()
    }

    @Test
    fun `a DaemonSet whose controller has not observed its latest generation has no current pods`() {
        // Just applied: the template is at 4, but the controller still acts on 3.
        val daemonSet =
            DaemonSetBuilder()
                .withNewMetadata()
                .withUid("ds-uid")
                .withGeneration(4)
                .addToAnnotations("deprecated.daemonset.template.generation", "4")
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(3)
                .endStatus()
                .build()

        assertThat(RolloutRevision.currentPods(daemonSet, emptyList(), listOf(dsPod("old", "3"), dsPod("new", "4")))).isEmpty()
    }

    @Test
    fun `a Deployment whose controller has not observed its latest generation has no current pods`() {
        // Just applied: the revision annotation still names the old ReplicaSet, whose pods crash.
        val deployment =
            DeploymentBuilder()
                .withNewMetadata()
                .withUid("deploy-uid")
                .withGeneration(2)
                .addToAnnotations("deployment.kubernetes.io/revision", "1")
                .endMetadata()
                .withNewStatus()
                .withObservedGeneration(1)
                .endStatus()
                .build()

        assertThat(RolloutRevision.currentPods(deployment, listOf(replicaSet("rs-1", "1")), listOf(rsPod("old", "rs-1"))))
            .isEmpty()
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
