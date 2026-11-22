package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.kubernetes.KubernetesClientFactory
import com.rustyrazorblade.easydblab.kubernetes.KubernetesErrorHandler
import com.rustyrazorblade.easydblab.services.tenancy.NamespaceEnsurer
import com.rustyrazorblade.easydblab.services.tenancy.NamespacePlan
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import io.fabric8.mockwebserver.http.Headers
import io.fabric8.mockwebserver.http.RecordedRequest
import io.fabric8.mockwebserver.utils.ResponseProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * [CapabilityProbeService] when the API server answers in ways a real K3s cannot be made to: a
 * forbidden namespace read, a namespace no ServiceAccount controller serves, a forbidden ServiceAccount create, a webhook that
 * does not support dry run, and an unrelated rejection of one capability Pod. The Fabric8 mock server
 * answers those requests from expectations and serves everything else from its CRUD store, so the
 * probe runs its real client calls; [CapabilityProbeIntegrationTest] covers real admission.
 */
@EnableKubernetesMockClient(crud = true)
class CapabilityProbeFailureIntegrationTest {
    private companion object {
        const val NAMESPACE = "probe-mock"
        val SCOPE = InstallScope(K8sNamespace(NAMESPACE), "probe-cluster")
        const val NAMESPACES_PATH = "/api/v1/namespaces"
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

    private fun probe() = CapabilityProbeService(clientFactory)

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

    /**
     * The message the API server writes when RBAC denies alice [verb] on [resource]; [subject] is
     * the `<resource> ["<name>"]` it names first. Only this form is an RBAC denial to the handler.
     */
    private fun rbacDenial(
        verb: String,
        resource: String,
        subject: String,
    ): String =
        "$subject is forbidden: User \"alice\" cannot $verb resource \"$resource\" in API group \"\" in the namespace \"$NAMESPACE\""

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
    fun `a forbidden namespace read fails the probe naming the namespace, and keeps the 403 for the Kubernetes handler`() {
        server
            .expect()
            .get()
            .withPath("/api/v1/namespaces/$NAMESPACE")
            .andReturn(
                Constants.HttpStatus.FORBIDDEN,
                status(Constants.HttpStatus.FORBIDDEN, "Forbidden", rbacDenial("get", "namespaces", "namespaces \"$NAMESPACE\"")),
            ).always()

        val thrown = catchThrowable { probe().probe(api, SCOPE) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("User \"alice\" cannot get resource \"namespaces\"")
        assertForbiddenCauseNeedsClusterAdmin(thrown)
    }

    @Test
    fun `a created namespace that no ServiceAccount controller serves gets its default ServiceAccount from the probe and is probed`() {
        answerPods("no-such-pod", status(Constants.HttpStatus.FORBIDDEN, "Forbidden", "unused"))

        val result = probe().probe(api, SCOPE)

        assertThat(result.capabilities.present())
            .containsExactly(NodeCapability.PRIVILEGED, NodeCapability.HOST_NETWORK, NodeCapability.HOST_PATH)
        server.createClient().use { client ->
            assertThat(
                client
                    .serviceAccounts()
                    .inNamespace(NAMESPACE)
                    .withName("default")
                    .get(),
            ).isNotNull()
        }
    }

    @Test
    fun `an existing namespace with no default ServiceAccount gets one from the probe`() {
        createNamespace()
        answerPods("no-such-pod", status(Constants.HttpStatus.FORBIDDEN, "Forbidden", "unused"))

        probe().probe(api, SCOPE)

        server.createClient().use { client ->
            assertThat(
                client
                    .serviceAccounts()
                    .inNamespace(NAMESPACE)
                    .withName("default")
                    .get(),
            ).isNotNull()
        }
    }

    @Test
    fun `a forbidden ServiceAccount create fails the probe with the server message, keeping the 403`() {
        server
            .expect()
            .post()
            .withPath("/api/v1/namespaces/$NAMESPACE/serviceaccounts")
            .andReturn(
                Constants.HttpStatus.FORBIDDEN,
                status(Constants.HttpStatus.FORBIDDEN, "Forbidden", rbacDenial("create", "serviceaccounts", "serviceaccounts")),
            ).always()

        val thrown = catchThrowable { probe().probe(api, SCOPE) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("creating the default ServiceAccount")
            .hasMessageContaining("User \"alice\" cannot create resource \"serviceaccounts\"")
        assertForbiddenCauseNeedsClusterAdmin(thrown)
    }

    @Test
    fun `a namespace create that loses a race is planned again from the namespace that won, which is adopted`() {
        // The winner is already stored, but the first read reports it absent and the create conflicts,
        // as when another `up` or probe created it between this one's read and its create.
        createNamespace()
        server
            .expect()
            .get()
            .withPath("/api/v1/namespaces/$NAMESPACE")
            .andReturn(Constants.HttpStatus.NOT_FOUND, status(Constants.HttpStatus.NOT_FOUND, "NotFound", "not found"))
            .once()
        server
            .expect()
            .post()
            .withPath(NAMESPACES_PATH)
            .andReturn(
                Constants.HttpStatus.CONFLICT,
                status(Constants.HttpStatus.CONFLICT, "AlreadyExists", "namespaces \"$NAMESPACE\" already exists"),
            ).once()

        val plan = server.createClient().use { NamespaceEnsurer.ensure(it, SCOPE) }

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        server.createClient().use { client ->
            assertThat(
                client
                    .namespaces()
                    .withName(NAMESPACE)
                    .get()
                    .metadata.labels,
            ).containsAllEntriesOf(SCOPE.labels)
        }
    }

    @Test
    fun `a forbidden namespace create fails the probe naming the namespace, keeps the 403, and is not retried`() {
        server
            .expect()
            .post()
            .withPath(NAMESPACES_PATH)
            .andReturn(
                Constants.HttpStatus.FORBIDDEN,
                status(Constants.HttpStatus.FORBIDDEN, "Forbidden", rbacDenial("create", "namespaces", "namespaces")),
            ).always()

        val thrown = catchThrowable { probe().probe(api, SCOPE) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("ensuring the namespace")
            .hasMessageContaining("User \"alice\" cannot create resource \"namespaces\"")
        assertForbiddenCauseNeedsClusterAdmin(thrown)
        val creates = List(server.requestCount) { server.takeRequest() }.count { it.method == "POST" && it.path == NAMESPACES_PATH }
        assertThat(creates).isEqualTo(1)
    }

    /** The probe leaves the cluster-admin explanation to the Kubernetes handler, which reads the kept 403. */
    private fun assertForbiddenCauseNeedsClusterAdmin(thrown: Throwable) {
        val forbidden = thrown.cause as KubernetesClientException
        assertThat(forbidden.code).isEqualTo(Constants.HttpStatus.FORBIDDEN)
        assertThat(KubernetesErrorHandler().describe(forbidden)?.remedy).contains("cluster-admin")
    }

    @Test
    fun `a Pod Security rejection of the baseline fails the probe, and is described as rejected with its level and violations`() {
        createNamespace()
        val baseline = "${Constants.Capabilities.PROBE_POD_PREFIX}-baseline"
        answerPods(
            baseline,
            status(
                Constants.HttpStatus.FORBIDDEN,
                "Forbidden",
                "pods \"$baseline\" is forbidden: violates PodSecurity \"restricted:latest\": allowPrivilegeEscalation != false " +
                    "(container \"probe\" must set securityContext.allowPrivilegeEscalation=false), runAsNonRoot != true " +
                    "(pod or container \"probe\" must set securityContext.runAsNonRoot=true)",
            ),
        )

        val thrown = catchThrowable { probe().probe(api, SCOPE) }

        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("baseline")
        val description = KubernetesErrorHandler().describe(thrown.cause as KubernetesClientException)
        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("PodSecurity")
        assertThat(description?.policy?.level).isEqualTo("restricted:latest")
        assertThat(description?.policy?.violations).hasSize(2)
        assertThat(description?.policy?.violations?.get(1)).contains("securityContext.runAsNonRoot")
        assertThat(description?.remedy).contains("pod-security.kubernetes.io/enforce").contains(NAMESPACE)
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

        val result = probe().probe(api, SCOPE)

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

        assertThatThrownBy { probe().probe(api, SCOPE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("'$NAMESPACE'")
            .hasMessageContaining("probing ${NodeCapability.HOST_PATH.serialName}")
            .hasMessageContaining("etcdserver: request timed out")
    }
}
