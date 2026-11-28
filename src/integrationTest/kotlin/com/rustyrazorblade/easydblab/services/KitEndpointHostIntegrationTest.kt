package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.DirectReadWait
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.readiness.Readiness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * Runs [KitEndpointHost] over the real pod listing on a K3s cluster: an endpoint with a
 * `pod-selector` resolves to the IP of the node that runs its ready pod, not to the first node of its
 * node type, and an endpoint whose pod is gone, or runs but is not ready, is not served instead of
 * guessing a node.
 *
 * The cluster state holds the K3s node's InternalIP after a decoy address no node has, so the test
 * fails if the resolver falls back to the first host. `DefaultK8sService` opens and closes a client
 * for each query, so only the test's own [client] needs closing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitEndpointHostIntegrationTest {
    companion object {
        private const val NAMESPACE = "kit-endpoint-host"
        private const val KIT = "querykit"
        private val WAIT: Duration = Duration.ofSeconds(60)
        private const val DECOY_IP = "192.0.2.1"
        private const val SELECTOR = "app.kubernetes.io/name=$KIT,app.kubernetes.io/component=coordinator"
    }

    private lateinit var client: KubernetesClient
    private lateinit var k8s: DefaultK8sService
    private lateinit var endpointHost: KitEndpointHost

    private val endpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "app",
            port = 8080,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "presto",
            servingPodSelector = "app.kubernetes.io/name=\${KIT_NAME},app.kubernetes.io/component=coordinator",
        )

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        k8s = DefaultK8sService(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), EventBus())
        endpointHost = KitEndpointHost.of { k8s }
    }

    @AfterEach
    fun deleteCoordinators() {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withLabelSelector(SELECTOR)
            .withGracePeriod(0L)
            .delete()
        DirectReadWait.awaitPods(client, DirectReadWait.PodSelection(NAMESPACE, SELECTOR), "are all gone", WAIT) { it.isEmpty() }
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    private fun node() =
        client
            .nodes()
            .list()
            .items
            .single()

    private fun nodeInternalIp(): String =
        node()
            .status
            .addresses
            .first { it.type == "InternalIP" }
            .address

    /** The cluster state: a decoy app node first, then the K3s node. */
    private fun hosts() =
        mapOf(
            ServerType.Stress to
                listOf(
                    ClusterHost(publicIp = "", privateIp = DECOY_IP, alias = "app0", availabilityZone = "a"),
                    ClusterHost(publicIp = "", privateIp = nodeInternalIp(), alias = "app1", availabilityZone = "a"),
                ),
        )

    /** A coordinator pod with a timestamp name; [ready] false gives it a readiness probe that never passes. */
    private fun coordinator(ready: Boolean): Pod {
        val name = "coordinator-${System.nanoTime()}"
        return PodBuilder()
            .withNewMetadata()
            .withName(name)
            .addToLabels("app.kubernetes.io/name", KIT)
            .addToLabels("app.kubernetes.io/component", "coordinator")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("main")
            .withImage(SharedK3s.BUSYBOX_IMAGE)
            .withImagePullPolicy("Never")
            .withCommand("sleep", "3600")
            .withNewReadinessProbe()
            .withNewExec()
            .withCommand(if (ready) "true" else "false")
            .endExec()
            .withPeriodSeconds(1)
            .endReadinessProbe()
            .endContainer()
            .withTerminationGracePeriodSeconds(0L)
            .endSpec()
            .build()
            .let {
                client
                    .pods()
                    .inNamespace(NAMESPACE)
                    .resource(it)
                    .create()
            }
    }

    @Test
    fun `resolves to the node running the ready pod, and is not served once the pod is gone`() {
        val pod = coordinator(ready = true)
        DirectReadWait.awaitPod(client, DirectReadWait.PodRef(NAMESPACE, pod.metadata.name), "is Ready", WAIT) {
            it != null &&
                Readiness.isPodReady(it)
        }

        val listed = k8s.listPodsByLabel(SELECTOR, NAMESPACE).getOrThrow().single()
        assertThat(listed.nodeName).isEqualTo(node().metadata.name)
        assertThat(listed.hostIp).isEqualTo(nodeInternalIp())

        assertThat(endpointHost.resolve(endpoint, KIT, NAMESPACE, hosts()).getOrThrow())
            .isEqualTo(nodeInternalIp())
            .isNotEqualTo(DECOY_IP)

        deleteCoordinators()

        assertThat(endpointHost.resolve(endpoint, KIT, NAMESPACE, hosts()).exceptionOrNull())
            .isInstanceOf(EndpointNotServedException::class.java)
            .hasMessageContaining("Endpoint 'JDBC' is not served: no running, ready pod matches $SELECTOR in namespace $NAMESPACE")
    }

    @Test
    fun `a pod that runs but is not ready does not serve`() {
        val pod = coordinator(ready = false)
        DirectReadWait.awaitPod(client, DirectReadWait.PodRef(NAMESPACE, pod.metadata.name), "is Running", WAIT) {
            it?.status?.phase ==
                "Running"
        }

        val listed = k8s.listPodsByLabel(SELECTOR, NAMESPACE).getOrThrow().single()
        assertThat(listed.status).isEqualTo("Running")
        assertThat(listed.isReady()).isFalse()

        assertThat(endpointHost.lookup(endpoint, KIT, NAMESPACE, hosts()))
            .isEqualTo(KitEndpointHost.Lookup.Unserved("no running, ready pod matches $SELECTOR in namespace $NAMESPACE"))
    }
}
