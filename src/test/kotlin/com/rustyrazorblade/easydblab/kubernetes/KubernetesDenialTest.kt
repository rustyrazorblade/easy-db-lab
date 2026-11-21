package com.rustyrazorblade.easydblab.kubernetes

import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/**
 * The API server's denial and rejection messages, parsed into the facts a user needs to grant a
 * permission or fix an object. The messages are the ones the API server writes; text that does not
 * match a form parses to null so the handler falls back to the request URI.
 */
class KubernetesDenialTest {
    @Test
    fun `an RBAC denial in a namespace names the principal, verb, resource and namespace`() {
        val denial =
            RbacDenial.parse(
                "pods is forbidden: User \"alice\" cannot list resource \"pods\" in API group \"\" in the namespace \"lab\"",
            )

        assertThat(denial).isEqualTo(RbacDenial(principal = "alice", verb = "list", resource = "pods", namespace = "lab"))
    }

    @Test
    fun `an RBAC denial at the cluster scope has no namespace`() {
        val denial =
            RbacDenial.parse("nodes is forbidden: User \"alice\" cannot list resource \"nodes\" in API group \"\" at the cluster scope")

        assertThat(denial).isEqualTo(RbacDenial(principal = "alice", verb = "list", resource = "nodes"))
    }

    @Test
    fun `an RBAC denial on a subresource keeps the subresource and the object name`() {
        val denial =
            RbacDenial.parse(
                "pods \"web-0\" is forbidden: User \"system:serviceaccount:lab:edl\" cannot create resource \"pods/exec\" " +
                    "in API group \"\" in the namespace \"lab\"",
            )

        assertThat(denial).isEqualTo(
            RbacDenial(
                principal = "system:serviceaccount:lab:edl",
                verb = "create",
                resource = "pods/exec",
                namespace = "lab",
                name = "web-0",
            ),
        )
    }

    @Test
    fun `an RBAC denial on a grouped resource names the object`() {
        val denial =
            RbacDenial.parse(
                "deployments.apps \"grafana\" is forbidden: User \"bob\" cannot patch resource \"deployments\" " +
                    "in API group \"apps\" in the namespace \"default\"",
            )

        assertThat(denial?.name).isEqualTo("grafana")
        assertThat(denial?.resource).isEqualTo("deployments")
    }

    @Test
    fun `an anonymous RBAC denial is recognised as anonymous`() {
        val denial =
            RbacDenial.parse(
                "pods is forbidden: User \"system:anonymous\" cannot list resource \"pods\" in API group \"\" in the namespace \"lab\"",
            )

        assertThat(denial?.isAnonymous).isTrue()
        assertThat(RbacDenial("alice", "list", "pods").isAnonymous).isFalse()
    }

    @Test
    fun `text that is not an RBAC denial parses to null`() {
        assertThat(RbacDenial.parse("Unauthorized")).isNull()
        assertThat(RbacDenial.parse("pods \"x\" is forbidden: violates PodSecurity \"restricted:latest\": a")).isNull()
    }

    @Test
    fun `a Pod Security rejection splits its violations at top-level commas only`() {
        val rejection =
            AdmissionRejection.parse(
                "pods \"x\" is forbidden: violates PodSecurity \"restricted:latest\": " +
                    "allowPrivilegeEscalation != false (container \"c\" must set securityContext.allowPrivilegeEscalation=false), " +
                    "unrestricted capabilities (containers \"a\", \"b\" must set securityContext.capabilities.drop=[\"ALL\"]), " +
                    "runAsNonRoot != true (pod or container \"c\" must set securityContext.runAsNonRoot=true)",
            )

        assertThat(rejection).isEqualTo(
            AdmissionRejection.PodSecurity(
                level = "restricted:latest",
                violations =
                    listOf(
                        "allowPrivilegeEscalation != false (container \"c\" must set securityContext.allowPrivilegeEscalation=false)",
                        "unrestricted capabilities (containers \"a\", \"b\" must set securityContext.capabilities.drop=[\"ALL\"])",
                        "runAsNonRoot != true (pod or container \"c\" must set securityContext.runAsNonRoot=true)",
                    ),
                name = "x",
            ),
        )
    }

    @Test
    fun `an admission webhook denial names the webhook and keeps its reason`() {
        val rejection =
            AdmissionRejection.parse(
                "admission webhook \"validate.kyverno.svc\" denied the request: policy require-labels: label 'team' is required",
            )

        assertThat(rejection).isEqualTo(
            AdmissionRejection.Webhook(
                webhook = "validate.kyverno.svc",
                reason = "policy require-labels: label 'team' is required",
            ),
        )
    }

    @Test
    fun `a ValidatingAdmissionPolicy denial names the policy and keeps its reason`() {
        val rejection =
            AdmissionRejection.parse(
                "deployments.apps \"web\" is forbidden: ValidatingAdmissionPolicy 'max-replicas.example.com' " +
                    "with binding 'max-replicas-binding' denied request: failed expression: object.spec.replicas <= 5",
            )

        assertThat(rejection).isEqualTo(
            AdmissionRejection.ValidatingPolicy(
                policy = "max-replicas.example.com",
                reason = "failed expression: object.spec.replicas <= 5",
                name = "web",
            ),
        )
    }

    @Test
    fun `a webhook that does not support dry run is not a rejection`() {
        assertThat(AdmissionRejection.parse("admission webhook \"validate.kyverno.svc\" does not support dry run")).isNull()
    }

    @Test
    fun `unmatched text is not a rejection`() {
        assertThat(AdmissionRejection.parse("the server could not find the requested resource")).isNull()
    }

    @Test
    fun `a 401 or 403 anywhere in the cause chain is an auth rejection`() {
        assertThat(KubernetesClientException("Unauthorized", 401, null).isKubernetesAuthRejection()).isTrue()
        assertThat(CompletionException(KubernetesClientException("forbidden", 403, null)).isKubernetesAuthRejection()).isTrue()
    }

    @Test
    fun `other codes and other exceptions are not auth rejections`() {
        assertThat(KubernetesClientException("not found", 404, null).isKubernetesAuthRejection()).isFalse()
        assertThat(KubernetesClientException("io", 0, null).isKubernetesAuthRejection()).isFalse()
        assertThat(IllegalStateException("403").isKubernetesAuthRejection()).isFalse()
    }
}
