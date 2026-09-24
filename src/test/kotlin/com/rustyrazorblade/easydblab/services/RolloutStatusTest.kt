package com.rustyrazorblade.easydblab.services

import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests the rollout-completion decision for Deployments and DaemonSets, which follows
 * `kubectl rollout status`: a rollout is done only once the controller has observed the latest
 * spec, every desired pod runs the new template, no old pod remains, and every new pod is available.
 * A rollout-restart leaves the old pods Ready while the new ones start, so a namespace-wide
 * pod-readiness check alone reports "ready" too early.
 */
class RolloutStatusTest {
    @Suppress("LongParameterList")
    private fun deployment(
        generation: Long = 2,
        observedGeneration: Long? = 2,
        specReplicas: Int? = 1,
        replicas: Int? = 1,
        updated: Int? = 1,
        available: Int? = 1,
    ): Deployment =
        DeploymentBuilder()
            .withNewMetadata()
            .withName("tempo")
            .withGeneration(generation)
            .endMetadata()
            .withNewSpec()
            .withReplicas(specReplicas)
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(observedGeneration)
            .withReplicas(replicas)
            .withUpdatedReplicas(updated)
            .withAvailableReplicas(available)
            .endStatus()
            .build()

    @Suppress("LongParameterList")
    private fun daemonSet(
        generation: Long = 3,
        observedGeneration: Long? = 3,
        desired: Int? = 2,
        updated: Int? = 2,
        available: Int? = 2,
    ): DaemonSet =
        DaemonSetBuilder()
            .withNewMetadata()
            .withName("otel-collector")
            .withGeneration(generation)
            .endMetadata()
            .withNewStatus()
            .withObservedGeneration(observedGeneration)
            .withDesiredNumberScheduled(desired)
            .withUpdatedNumberScheduled(updated)
            .withNumberAvailable(available)
            .endStatus()
            .build()

    @Test
    fun `a deployment whose every desired replica is updated and available is complete`() {
        assertThat(RolloutStatus.of(deployment())).isEqualTo(RolloutProgress.Complete)
    }

    @Test
    fun `a deployment whose controller has not observed the restart is pending`() {
        // Immediately after the restart annotation lands, status still describes the old spec and
        // can look fully rolled out.
        val progress = RolloutStatus.of(deployment(generation = 3, observedGeneration = 2))

        assertThat(progress).isInstanceOf(RolloutProgress.Pending::class.java)
        assertThat((progress as RolloutProgress.Pending).reason).contains("not yet observed")
    }

    @Test
    fun `a deployment with fewer updated replicas than desired is pending`() {
        val progress = RolloutStatus.of(deployment(updated = 0, available = 0, replicas = 0))

        assertThat((progress as RolloutProgress.Pending).reason).contains("0 of 1 updated replicas")
    }

    @Test
    fun `a deployment with an old replica still present is pending`() {
        val progress = RolloutStatus.of(deployment(replicas = 2, updated = 1, available = 1))

        assertThat((progress as RolloutProgress.Pending).reason).contains("old replicas are pending termination")
    }

    @Test
    fun `a deployment whose updated replica is not yet available is pending`() {
        // The observed live failure: the new pod exists at 0/1 while `update-config` reported ready.
        val progress = RolloutStatus.of(deployment(available = 0))

        assertThat((progress as RolloutProgress.Pending).reason).contains("0 of 1 updated replicas are available")
    }

    @Test
    fun `a deployment with no replica count set defaults to one desired replica`() {
        val progress = RolloutStatus.of(deployment(specReplicas = null, updated = 0, replicas = 0, available = 0))

        assertThat((progress as RolloutProgress.Pending).reason).contains("0 of 1 updated replicas")
    }

    @Test
    fun `a deployment with no status yet is pending`() {
        val fresh = deployment().apply { status = null }

        assertThat(RolloutStatus.of(fresh)).isInstanceOf(RolloutProgress.Pending::class.java)
    }

    @Test
    fun `a daemonset whose every scheduled pod is updated and available is complete`() {
        assertThat(RolloutStatus.of(daemonSet())).isEqualTo(RolloutProgress.Complete)
    }

    @Test
    fun `a daemonset whose controller has not observed the restart is pending`() {
        val progress = RolloutStatus.of(daemonSet(generation = 4, observedGeneration = 3))

        assertThat((progress as RolloutProgress.Pending).reason).contains("not yet observed")
    }

    @Test
    fun `a daemonset with fewer updated pods than desired is pending`() {
        val progress = RolloutStatus.of(daemonSet(updated = 1, available = 2))

        assertThat((progress as RolloutProgress.Pending).reason).contains("1 of 2 updated pods")
    }

    @Test
    fun `a daemonset whose updated pod is not yet available is pending`() {
        // The observed live failure: a restarted fluent-bit pod sat at 0/1 after "ready".
        val progress = RolloutStatus.of(daemonSet(available = 1))

        assertThat((progress as RolloutProgress.Pending).reason).contains("1 of 2 updated pods are available")
    }

    @Test
    fun `a daemonset scheduled on no nodes is complete`() {
        val progress = RolloutStatus.of(daemonSet(desired = 0, updated = 0, available = 0))

        assertThat(progress).isEqualTo(RolloutProgress.Complete)
    }
}
