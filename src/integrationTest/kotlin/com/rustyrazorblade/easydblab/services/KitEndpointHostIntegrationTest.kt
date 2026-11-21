package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.EventBus
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * Runs [KitEndpointHost] over the real pod listing on a K3s cluster: an endpoint with a
 * `pod-selector` resolves to the IP of the node that runs its ready pod, not to the first node of its
 * node type, and an endpoint whose pod is gone fails instead of guessing a node.
 *
 * The first host in the cluster state is a decoy address no node has, so the test fails if the
 * resolver falls back to it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitEndpointHostIntegrationTest {
    companion object {
        private const val NAMESPACE = "kit-endpoint-host"
        private const val KIT = "querykit"
        private const val WAIT_SECONDS = 60L
        private const val DECOY_IP = "192.0.2.1"
    }

    private lateinit var client: KubernetesClient
    private lateinit var endpointHost: KitEndpointHost

    private val endpoint =
        KitEndpoint(
            name = "JDBC",
            nodeType = "app",
            port = 8080,
            type = KitEndpoint.EndpointType.JDBC,
            scheme = "presto",
            podSelector = "app.kubernetes.io/name=\${KIT_NAME},app.kubernetes.io/component=coordinator",
        )

    private val hosts =
        mapOf(
            ServerType.Stress to listOf(ClusterHost(publicIp = "", privateIp = DECOY_IP, alias = "app0", availabilityZone = "a")),
        )

    @BeforeAll
    fun setup() {
        SharedK3s.createNamespace(NAMESPACE)
        client = SharedK3s.client()
        val k8s = DefaultK8sService(WorkspaceK8s.provider(SharedK3s.kubeConfigYaml()), EventBus())
        endpointHost = KitEndpointHost(k8s::listPodsByLabel)
    }

    @AfterAll
    fun tearDown() {
        client.close()
    }

    private fun nodeInternalIp(): String =
        client
            .nodes()
            .list()
            .items
            .single()
            .status
            .addresses
            .first { it.type == "InternalIP" }
            .address

    private fun startCoordinator(name: String) {
        client
            .pods()
            .inNamespace(NAMESPACE)
            .resource(
                PodBuilder()
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
                    .endContainer()
                    .withTerminationGracePeriodSeconds(0L)
                    .endSpec()
                    .build(),
            ).create()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName(name)
            .waitUntilReady(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `resolves to the node running the ready pod, and fails once the pod is gone`() {
        startCoordinator("coordinator-a")

        assertThat(endpointHost.resolve(endpoint, KIT, NAMESPACE, hosts).getOrThrow())
            .isEqualTo(nodeInternalIp())
            .isNotEqualTo(DECOY_IP)

        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName("coordinator-a")
            .withGracePeriod(0L)
            .delete()
        client
            .pods()
            .inNamespace(NAMESPACE)
            .withName("coordinator-a")
            .waitUntilCondition({ it == null }, WAIT_SECONDS, TimeUnit.SECONDS)

        assertThat(endpointHost.resolve(endpoint, KIT, NAMESPACE, hosts).exceptionOrNull())
            .hasMessageContaining("No running, ready pod serves endpoint 'JDBC'")
    }
}
