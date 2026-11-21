package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kernel.ProviderPolicy
import com.rustyrazorblade.easydblab.kernel.ProviderRequest
import com.rustyrazorblade.easydblab.services.CommandFailureReporter
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.internal.OperationSupport
import io.fabric8.kubernetes.client.http.StandardHttpRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.ConnectException
import java.net.URI
import java.util.concurrent.CompletionException

/**
 * Which Fabric8 failures the Kubernetes handler claims, and what it says about them. Every
 * exception is built by Fabric8's own [OperationSupport.requestFailure] from a real request and
 * [Status], so the message format and the request-URI getters are the ones a command meets.
 */
class KubernetesErrorHandlerTest {
    private val handler = KubernetesErrorHandler()

    private fun failure(
        method: String,
        path: String,
        code: Int,
        message: String?,
        reason: String = "Forbidden",
    ): KubernetesClientException {
        val request = StandardHttpRequest(emptyMap(), URI("https://10.0.0.1:6443$path"), method, null)
        val status =
            StatusBuilder()
                .withStatus("Failure")
                .withCode(code)
                .withReason(reason)
                .withMessage(message)
                .build()
        return OperationSupport.requestFailure(request, status)
    }

    /** A listener that adds every emitted event to [into]. */
    private fun collecting(into: MutableList<Event>): EventListener =
        object : EventListener {
            override fun onEvent(envelope: EventEnvelope) {
                into.add(envelope.event)
            }

            override fun close() = Unit
        }

    @Test
    fun `an RBAC denial is permission-denied with the request the server named`() {
        val error =
            failure(
                "GET",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods is forbidden: User \"alice\" cannot list resource \"pods\" in API group \"\" in the namespace \"lab\"",
            )

        val description = handler.describe(error)

        assertThat(description?.provider).isEqualTo("Kubernetes")
        assertThat(description?.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(description?.request)
            .isEqualTo(ProviderRequest(verb = "list", resource = "pods", namespace = "lab", principal = "alice"))
        assertThat(description?.detail).isEqualTo(
            "pods is forbidden: User \"alice\" cannot list resource \"pods\" in API group \"\" in the namespace \"lab\"",
        )
        assertThat(description?.remedy).contains("cluster-admin").contains("--user=alice")
    }

    @Test
    fun `a ServiceAccount principal gets a ServiceAccount binding in the remedy`() {
        val error =
            failure(
                "GET",
                "/api/v1/nodes",
                403,
                "nodes is forbidden: User \"system:serviceaccount:lab:edl\" cannot list resource \"nodes\" in API group \"\" " +
                    "at the cluster scope",
            )

        val description = handler.describe(error)

        assertThat(description?.request?.namespace).isNull()
        assertThat(description?.remedy).contains("--serviceaccount=lab:edl")
    }

    @Test
    fun `an anonymous 403 is a credentials failure`() {
        val error =
            failure(
                "GET",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods is forbidden: User \"system:anonymous\" cannot list resource \"pods\" in API group \"\" in the namespace \"lab\"",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description?.remedy).contains("Re-authenticate").contains("exec auth plugin")
    }

    @Test
    fun `a 401 is a credentials failure that names no principal`() {
        val error = failure("GET", "/api/v1/namespaces/lab/pods", 401, "Unauthorized", reason = "Unauthorized")

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description?.request?.principal).isNull()
        assertThat(description?.remedy).contains("Re-authenticate")
    }

    @Test
    fun `a Pod Security rejection is rejected with the level, each violation and the rejected object`() {
        val error =
            failure(
                "POST",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods \"x\" is forbidden: violates PodSecurity \"restricted:latest\": allowPrivilegeEscalation != false " +
                    "(container \"c\" must set securityContext.allowPrivilegeEscalation=false), runAsNonRoot != true " +
                    "(pod or container \"c\" must set securityContext.runAsNonRoot=true)",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("PodSecurity")
        assertThat(description?.policy?.level).isEqualTo("restricted:latest")
        assertThat(description?.policy?.violations).hasSize(2)
        assertThat(description?.policy?.violations?.get(0)).contains("securityContext.allowPrivilegeEscalation")
        assertThat(description?.request).isEqualTo(ProviderRequest(verb = "POST", resource = "pods", namespace = "lab", name = "x"))
        assertThat(description?.remedy).contains("pod-security.kubernetes.io/enforce").contains("lab")
    }

    @Test
    fun `an admission webhook denial is rejected with any of its status codes`() {
        for (code in listOf(400, 403, 422)) {
            val error =
                failure(
                    "POST",
                    "/apis/apps/v1/namespaces/lab/deployments",
                    code,
                    "admission webhook \"validate.kyverno.svc\" denied the request: label 'team' is required",
                )

            val description = handler.describe(error)

            assertThat(description?.kind).describedAs("code $code").isEqualTo(ProviderErrorKind.REJECTED)
            assertThat(description?.policy)
                .describedAs("code $code")
                .isEqualTo(ProviderPolicy("validate.kyverno.svc", violations = listOf("label 'team' is required")))
            assertThat(description?.request?.resource).isEqualTo("deployments")
        }
    }

    @Test
    fun `a ValidatingAdmissionPolicy denial is rejected naming the policy`() {
        val error =
            failure(
                "PATCH",
                "/apis/apps/v1/namespaces/lab/deployments/web",
                422,
                "deployments.apps \"web\" is forbidden: ValidatingAdmissionPolicy 'max-replicas' with binding 'b' " +
                    "denied request: failed expression: object.spec.replicas <= 5",
                reason = "Invalid",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("max-replicas")
        assertThat(
            description?.request,
        ).isEqualTo(ProviderRequest(verb = "PATCH", resource = "deployments", namespace = "lab", name = "web"))
    }

    @Test
    fun `a 403 the server does not explain is left to the generic error, with no cluster-admin remedy`() {
        val unexplained =
            listOf(
                failure("DELETE", "/apis/apps/v1/namespaces/lab/statefulsets/db", 403, "access denied by gateway"),
                OperationSupport.requestFailure(
                    StandardHttpRequest(emptyMap(), URI("https://10.0.0.1:6443/api/v1/namespaces/lab/pods"), "GET", null),
                    OperationSupport.createStatus(403, "<html>Forbidden</html>"),
                ),
                KubernetesClientException("Forbidden!", 403, null),
            )

        assertThat(unexplained.map { handler.describe(it) }).containsOnlyNulls()
    }

    @Test
    fun `an unexplained 403 reaches the user as the generic error with the server's message`() {
        val emitted = mutableListOf<Event>()
        val bus = EventBus().also { it.addListener(collecting(emitted)) }

        CommandFailureReporter(bus, listOf(handler))
            .report(failure("GET", "/api/v1/namespaces/lab/pods", 403, "access denied by gateway"))

        val event = emitted.single() as Event.Command.ExecutionError
        assertThat(event.toDisplayString()).contains("access denied by gateway").doesNotContain("cluster-admin")
    }

    @Test
    fun `a create in a namespace being deleted is rejected, with a remedy to wait for it`() {
        val error =
            failure(
                "POST",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods \"web\" is forbidden: unable to create new content in namespace lab because it is being terminated",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("NamespaceLifecycle")
        assertThat(description?.request).isEqualTo(ProviderRequest(verb = "POST", resource = "pods", namespace = "lab", name = "web"))
        assertThat(description?.remedy).contains("being deleted").contains("Wait").doesNotContain("cluster-admin")
    }

    @Test
    fun `an exceeded ResourceQuota is rejected, naming the quota, with a remedy to free or raise it`() {
        val error =
            failure(
                "POST",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods \"web\" is forbidden: exceeded quota: compute, requested: limits.cpu=2, used: limits.cpu=4, limited: limits.cpu=5",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy)
            .isEqualTo(
                ProviderPolicy(
                    "ResourceQuota compute",
                    violations = listOf("requested: limits.cpu=2, used: limits.cpu=4, limited: limits.cpu=5"),
                ),
            )
        assertThat(description?.remedy).contains("ResourceQuota compute").contains("raise").doesNotContain("cluster-admin")
    }

    @Test
    fun `a LimitRange refusal is rejected, with a remedy to fit the LimitRange`() {
        val error =
            failure(
                "POST",
                "/api/v1/namespaces/lab/pods",
                403,
                "pods \"web\" is forbidden: maximum cpu usage per Container is 1, but limit is 2",
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy)
            .isEqualTo(ProviderPolicy("LimitRange", violations = listOf("maximum cpu usage per Container is 1, but limit is 2")))
        assertThat(description?.remedy).contains("LimitRange").contains("-n lab").doesNotContain("cluster-admin")
    }

    @ParameterizedTest(name = "401 with status message [{0}]")
    @CsvSource(value = ["NULL", "''", "'  '"], nullValues = ["NULL"])
    fun `a failure with no status message never puts the request URI's query in the detail`(statusMessage: String?) {
        val request =
            StandardHttpRequest(emptyMap(), URI("https://10.0.0.1:6443/api/v1/namespaces/lab/pods?watch=true&token=s3cr3t"), "GET", null)
        val status =
            StatusBuilder()
                .withStatus("Failure")
                .withCode(401)
                .withMessage(statusMessage)
                .build()
        val error = OperationSupport.requestFailure(request, status)

        val description = handler.describe(error)

        assertThat(error.message).contains("s3cr3t")
        assertThat(description?.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description?.detail)
            .isEqualTo("The API server answered HTTP 401 with no message to GET /api/v1/namespaces/lab/pods")
    }

    @Test
    fun `a blank status message with a non-Fabric8 exception message keeps any URI's userinfo and query out of the detail`() {
        val status =
            StatusBuilder()
                .withStatus("Failure")
                .withCode(401)
                .withMessage(" ")
                .build()
        val error =
            KubernetesClientException(
                "Proxy refused https://admin:hunter2@10.0.0.1:6443/api/v1/namespaces/lab/pods?watch=true&token=s3cr3t#frag " +
                    "and http://ops@gateway.local/auth",
                401,
                status,
            )

        val description = handler.describe(error)

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description?.detail)
            .isEqualTo("Proxy refused https://10.0.0.1:6443/api/v1/namespaces/lab/pods and http://gateway.local/auth")
    }

    @Test
    fun `a Fabric8 failure with no status message keeps the URI's userinfo out of the detail`() {
        val request =
            StandardHttpRequest(emptyMap(), URI("https://admin:hunter2@10.0.0.1:6443/api/v1/namespaces/lab/pods?token=s3cr3t"), "GET", null)
        val error = OperationSupport.requestFailure(request, OperationSupport.createStatus(401, ""))

        val description = handler.describe(error)

        assertThat(error.message).contains("hunter2")
        assertThat(description?.detail)
            .isNotNull()
            .doesNotContain("admin")
            .doesNotContain("hunter2")
            .doesNotContain("s3cr3t")
    }

    @Test
    fun `not-found, conflict, invalid and dry-run failures are not claimed`() {
        val unclaimed =
            listOf(
                failure("GET", "/api/v1/namespaces/lab/pods/x", 404, "pods \"x\" not found", reason = "NotFound"),
                failure("POST", "/api/v1/namespaces/lab/pods", 409, "pods \"x\" already exists", reason = "AlreadyExists"),
                failure(
                    "POST",
                    "/api/v1/namespaces/lab/pods",
                    422,
                    "Pod \"x\" is invalid: spec.containers: Required value",
                    reason = "Invalid",
                ),
                failure(
                    "PATCH",
                    "/api/v1/namespaces/lab/pods/x",
                    400,
                    "admission webhook \"w\" does not support dry run",
                    reason = "BadRequest",
                ),
            )

        assertThat(unclaimed.map { handler.describe(it) }).containsOnlyNulls()
    }

    @Test
    fun `a transport failure with code 0 is not claimed`() {
        val error = KubernetesClientException("An error has occurred.", ConnectException("Connection refused"))

        assertThat(handler.describe(error)).isNull()
    }

    @Test
    fun `other exceptions are not claimed`() {
        assertThat(handler.describe(IllegalStateException("403 Forbidden"))).isNull()
    }

    @Test
    fun `a 403 kept as the cause of an IllegalStateException or inside a CompletionException is reported through the reporter`() {
        val denial =
            failure(
                "PATCH",
                "/apis/apps/v1/namespaces/lab/deployments/grafana",
                403,
                "deployments.apps \"grafana\" is forbidden: User \"alice\" cannot patch resource \"deployments\" " +
                    "in API group \"apps\" in the namespace \"lab\"",
            )
        for (wrapped in listOf(IllegalStateException("Failed to apply Grafana", denial), CompletionException(denial))) {
            val emitted = mutableListOf<Event>()
            val bus = EventBus().also { it.addListener(collecting(emitted)) }

            CommandFailureReporter(bus, listOf(handler)).report(wrapped)

            val event = emitted.single() as Event.Command.ProviderError
            assertThat(event.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
            assertThat(event.request?.name).isEqualTo("grafana")
            assertThat(event.toDisplayString()).startsWith("Kubernetes denied permission: alice cannot patch deployments/grafana")
        }
    }
}
