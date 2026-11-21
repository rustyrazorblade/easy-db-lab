package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.WorkspaceK8s
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kernel.causeChain
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.runCatchingException
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A 403 is the same answer on every look, so a Kubernetes poll must fail on the first one instead of
 * retrying until its deadline. Each poll runs its real Fabric8 calls against a mock API server that
 * answers the polled request with a real RBAC denial, and counts how often it was asked.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesAuthRejectionPollIntegrationTest {
    private companion object {
        const val NAMESPACE = "polls"
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }

    lateinit var server: KubernetesMockServer

    private lateinit var clientProvider: K8sClientProvider

    private val asked = AtomicInteger()

    @BeforeEach
    fun setup() {
        val factory =
            object : KubernetesClientFactory {
                override fun createClient(api: KubeApi): KubernetesClient = server.createClient()
            }
        clientProvider = WorkspaceK8s.provider("placeholder", factory)
    }

    private fun forbidden(resource: String): Status =
        StatusBuilder()
            .withStatus("Failure")
            .withCode(Constants.HttpStatus.FORBIDDEN)
            .withReason("Forbidden")
            .withMessage(
                "$resource is forbidden: User \"alice\" cannot list resource \"$resource\" in API group \"\" " +
                    "in the namespace \"$NAMESPACE\"",
            ).build()

    /** Answers every GET of [path] with a 403, counting each. */
    private fun deny(
        path: String,
        resource: String,
    ) {
        server
            .expect()
            .get()
            .withPath(path)
            .andReply(Constants.HttpStatus.FORBIDDEN) { _ ->
                asked.incrementAndGet()
                forbidden(resource)
            }.always()
    }

    private fun assertFailedOnFirst403(failure: Throwable?) {
        assertThat(asked.get()).isEqualTo(1)
        assertThat(
            failure
                ?.causeChain()
                .orEmpty()
                .filterIsInstance<KubernetesClientException>()
                .map { it.code },
        ).contains(Constants.HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the rollout wait fails on the first 403`() {
        deny("/apis/apps/v1/namespaces/$NAMESPACE/deployments/grafana", "deployments")
        val operations = DefaultK8sNamespaceOperations(clientProvider, EventBus(), POLL_INTERVAL)

        val result =
            operations.waitForRollouts(listOf(WorkloadRef(WorkloadKind.Deployment, "grafana")), NAMESPACE, TIMEOUT.seconds.toInt())

        assertFailedOnFirst403(result.exceptionOrNull())
    }

    @Test
    fun `the backend scale-down wait fails on the first 403`() {
        server.createClient().use { client ->
            client
                .apps()
                .deployments()
                .inNamespace(NAMESPACE)
                .resource(
                    DeploymentBuilder()
                        .withNewMetadata()
                        .withName("loki")
                        .endMetadata()
                        .withNewSpec()
                        .withReplicas(1)
                        .endSpec()
                        .build(),
                ).create()
        }
        deny("/api/v1/namespaces/$NAMESPACE/pods?labelSelector=app.kubernetes.io%2Fname%3Dloki", "pods")
        val workloads = K8sBackendWorkloads(clientProvider, NAMESPACE, pollInterval = POLL_INTERVAL)

        val failure = runCatchingException { workloads.scaleDown("loki", TIMEOUT) }.exceptionOrNull()

        assertFailedOnFirst403(failure)
    }

    @Test
    fun `the collector stop wait fails on the first 403`() {
        val first = TelemetrySenders.COLLECTORS.first()
        server.createClient().use { client ->
            client
                .apps()
                .daemonSets()
                .inNamespace(NAMESPACE)
                .resource(
                    DaemonSetBuilder()
                        .withNewMetadata()
                        .withName(first)
                        .endMetadata()
                        .build(),
                ).create()
        }
        deny("/api/v1/namespaces/$NAMESPACE/pods?labelSelector=app.kubernetes.io%2Fname%3D$first", "pods")
        val senders = K8sTelemetrySenders(clientProvider, NAMESPACE, pollInterval = POLL_INTERVAL)

        val failure = runCatchingException { senders.stop(TIMEOUT, {}, {}, {}) }.exceptionOrNull()

        assertFailedOnFirst403(failure)
    }
}
