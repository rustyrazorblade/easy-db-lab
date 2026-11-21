package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [K8sInstallNamespace], the first step `up` runs after bring-up, against a real API server: it
 * ensures the namespace its [com.rustyrazorblade.easydblab.configuration.tenancy.InstallScopeSource]
 * names and reports what it did as one `K8s.InstallNamespaceReady`, the event MCP and Redis
 * consumers see. Each test uses a namespace of its own on the shared K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sInstallNamespaceIntegrationTest {
    private companion object {
        const val CLUSTER_ID = "install-namespace-cluster"
    }

    private lateinit var client: KubernetesClient
    private lateinit var clientProvider: K8sClientProvider

    @BeforeAll
    fun setup() {
        client = SharedK3s.client()
        clientProvider = WorkspaceK8s.provider(SharedK3s.kubeConfigYaml())
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    /** Runs the step at [namespace] and returns the namespace events it emitted. */
    private fun ensure(namespace: String): List<Event.K8s.InstallNamespaceReady> {
        val emitted = mutableListOf<Event.K8s.InstallNamespaceReady>()
        val eventBus =
            EventBus().also {
                it.addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            (envelope.event as? Event.K8s.InstallNamespaceReady)?.let { event -> emitted += event }
                        }

                        override fun close() = Unit
                    },
                )
            }
        val scope = InstallScope(K8sNamespace(namespace), CLUSTER_ID)
        K8sInstallNamespace(clientProvider, { scope }, eventBus).ensure()
        return emitted
    }

    private fun labelsOf(namespace: String): Map<String, String> =
        client
            .namespaces()
            .withName(namespace)
            .get()
            .metadata.labels
            .orEmpty()

    @Test
    fun `an absent namespace is reported created, and unchanged on the next run`() {
        val namespace = "install-ns-absent"

        assertThat(ensure(namespace))
            .containsExactly(Event.K8s.InstallNamespaceReady(namespace, Event.K8s.NamespaceOutcome.CREATED))
        assertThat(labelsOf(namespace)).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
        assertThat(ensure(namespace))
            .containsExactly(Event.K8s.InstallNamespaceReady(namespace, Event.K8s.NamespaceOutcome.UNCHANGED))
    }

    @Test
    fun `an existing unlabelled namespace is reported adopted and carries the ownership labels`() {
        val namespace = "install-ns-existing"
        SharedK3s.createNamespace(namespace)

        assertThat(ensure(namespace))
            .containsExactly(Event.K8s.InstallNamespaceReady(namespace, Event.K8s.NamespaceOutcome.ADOPTED))
        assertThat(labelsOf(namespace)).containsAllEntriesOf(OwnershipLabels.of(CLUSTER_ID))
    }
}
