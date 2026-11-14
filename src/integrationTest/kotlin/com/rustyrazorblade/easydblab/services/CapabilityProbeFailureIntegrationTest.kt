package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import io.fabric8.mockwebserver.http.Headers
import io.fabric8.mockwebserver.http.RecordedRequest
import io.fabric8.mockwebserver.utils.ResponseProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

/**
 * [CapabilityProbeService] when the API server answers in ways a real K3s cannot be made to: a
 * forbidden namespace read, a namespace whose default ServiceAccount never appears, a webhook that
 * does not support dry run, and an unrelated rejection of one capability Pod. The Fabric8 mock server
 * answers those requests from expectations and serves everything else from its CRUD store, so the
 * probe runs its real client calls; [CapabilityProbeIntegrationTest] covers real admission.
 */
@EnableKubernetesMockClient(crud = true)
class CapabilityProbeFailureIntegrationTest {
    private companion object {
        const val NAMESPACE = "probe-mock"
        const val PODS_PATH = "/api/v1/namespaces/$NAMESPACE/pods?dryRun=All"
        const val WEBHOOK = "kyverno-resource-validating-webhook-cfg"
    }

    lateinit var server: KubernetesMockServer

    private val serialization = KubernetesSerialization()

    /** Hands the probe a client for the mock server, whatever cluster the [KubeApi] names. */
    private val clientFactory =
        object : KubernetesClientFactory {
            override fun createClient(api: KubeApi): KubernetesClient = server.createClient()
        }

    private val api = KubeApi(Path.of("unused-kubeconfig"), "unused-context")

    private fun probe(namespaceReadyTimeout: Duration = Duration.ofSeconds(Constants.Capabilities.NAMESPACE_READY_TIMEOUT_SECONDS)) =
        CapabilityProbeService(clientFactory, namespaceReadyTimeout)

    private fun status(
        code: Int,
        reason: String,
        message: String,
    ): Status =
        StatusBuilder()
            .withStatus("Failure")
            .withCode(code)
            .withReason(reason)
            .withMessage(message)
            .build()

    private fun createNamespace() {
        server.createClient().use { client ->
            client
                .namespaces()
                .resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                        .withName(NAMESPACE)
                        .endMetadata()
                        .build(),
                ).create()
        }
    }

    /**
     * Answers each dry-run Pod create: [rejectedPod]'s with [rejection], every other one as admitted,
     * by echoing the submitted Pod back.
     */
    private fun answerPods(
        rejectedPod: String,
        rejection: Status,
    ) {
        server
            .expect()
            .post()
            .withPath(PODS_PATH)
            .andReply(
                object : ResponseProvider<Any> {
                    private var headers = Headers.builder().build()

                    // Reading a request body drains it, and the status and the body both need it.
                    private val bodies = mutableMapOf<RecordedRequest, String>()

                    private fun bodyOf(request: RecordedRequest) = bodies.getOrPut(request) { request.utf8Body }

                    private fun rejects(request: RecordedRequest) = "\"$rejectedPod\"" in bodyOf(request)

                    override fun getStatusCode(request: RecordedRequest): Int =
                        if (rejects(request)) rejection.code else Constants.HttpStatus.CREATED

                    override fun getBody(request: RecordedRequest): Any =
                        if (rejects(request)) serialization.asJson(rejection) else bodyOf(request)

                    override fun getHeaders(): Headers = headers

                    override fun setHeaders(headers: Headers) {
                        this.headers = headers
                    }
                },
            ).always()
    }

    private fun probePod(capability: NodeCapability) = "${Constants.Capabilities.PROBE_POD_PREFIX}-${capability.serialName}"

    @Test
    fun `a forbidden namespace read fails the probe, naming the namespace, the server message and cluster-admin`() {
        server
            .expect()
            .get()
            .withPath("/api/v1/namespaces/$NAMESPACE")
            .andReturn(
                Constants.HttpStatus.FORBIDDEN,
                status(Constants.HttpStatus.FORBIDDEN, "Forbidden", "namespaces \"$NAMESPACE\" is forbidden: user cannot get"),
            ).always()

        assertThatThrownBy { probe().probe(api, NAMESPACE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("user cannot get")
            .hasMessageContaining("cluster-admin")
    }

    @Test
    fun `a created namespace whose default ServiceAccount never appears fails the probe within the timeout`() {
        assertThatThrownBy { probe(Duration.ofSeconds(1)).probe(api, NAMESPACE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("the default ServiceAccount did not appear within 1 s")
    }

    @Test
    fun `a forbidden ServiceAccount wait fails the probe with the server message`() {
        server
            .expect()
            .get()
            .withPath("/api/v1/namespaces/$NAMESPACE/serviceaccounts?fieldSelector=metadata.name%3Ddefault")
            .andReturn(
                Constants.HttpStatus.FORBIDDEN,
                status(Constants.HttpStatus.FORBIDDEN, "Forbidden", "serviceaccounts is forbidden: user cannot list"),
            ).always()

        assertThatThrownBy { probe().probe(api, NAMESPACE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("the default ServiceAccount did not appear")
            .hasMessageContaining("user cannot list")
            .hasMessageContaining("cluster-admin")
    }

    @Test
    fun `a webhook that does not support dry run makes privileged absent, naming the webhook, and leaves the others present`() {
        createNamespace()
        answerPods(
            probePod(NodeCapability.PRIVILEGED),
            status(
                Constants.HttpStatus.BAD_REQUEST,
                "BadRequest",
                "admission webhook \"$WEBHOOK\" does not support dry run",
            ),
        )

        val result = probe().probe(api, NAMESPACE)

        assertThat(result.capabilities.present()).containsExactly(NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH)
        assertThat(result.notes.keys).containsExactly(NodeCapability.PRIVILEGED)
        assertThat(result.notes.getValue(NodeCapability.PRIVILEGED)).contains(WEBHOOK).contains("dry run")
    }

    @Test
    fun `an unexpected rejection of one capability Pod fails the probe, naming that capability`() {
        createNamespace()
        answerPods(
            probePod(NodeCapability.HOST_PATH),
            status(Constants.HttpStatus.SERVER_ERROR_MIN, "InternalError", "etcdserver: request timed out"),
        )

        assertThatThrownBy { probe().probe(api, NAMESPACE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("probing ${NodeCapability.HOST_PATH.serialName}")
            .hasMessageContaining("etcdserver: request timed out")
    }
}
