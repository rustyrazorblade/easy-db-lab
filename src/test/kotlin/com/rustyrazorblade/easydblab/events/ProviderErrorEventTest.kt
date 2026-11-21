package com.rustyrazorblade.easydblab.events

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kernel.ProviderPolicy
import com.rustyrazorblade.easydblab.kernel.ProviderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The provider's own message is what tells the user why a call failed, and the remedy can be a
 * best guess (a 403 may be a bucket policy), so the message must come before the remedy. A
 * Kubernetes failure also says which request failed and which policy refused it, so the headline
 * names them.
 */
class ProviderErrorEventTest {
    @Test
    fun `the provider message is printed before the remedy`() {
        val text =
            Event.Command
                .ProviderError("AWS", ProviderErrorKind.PERMISSION_DENIED, "User is not authorized to s3:PutObject", "Attach the policy")
                .toDisplayString()

        assertThat(text.indexOf("User is not authorized to s3:PutObject"))
            .isGreaterThanOrEqualTo(0)
            .isLessThan(text.indexOf("Attach the policy"))
    }

    @Test
    fun `a permission denial with a request names the verb, resource, namespace and principal in the headline`() {
        val text =
            Event.Command
                .ProviderError(
                    provider = "Kubernetes",
                    kind = ProviderErrorKind.PERMISSION_DENIED,
                    detail = "pods is forbidden",
                    remedy = "Grant cluster-admin",
                    request = ProviderRequest(verb = "list", resource = "pods", namespace = "lab", principal = "alice"),
                ).toDisplayString()

        assertThat(text.lineSequence().first())
            .isEqualTo("Kubernetes denied permission: alice cannot list pods in namespace lab.")
    }

    @Test
    fun `a cluster-scoped request with an object name names the object and no namespace`() {
        val text =
            Event.Command
                .ProviderError(
                    provider = "Kubernetes",
                    kind = ProviderErrorKind.PERMISSION_DENIED,
                    detail = "d",
                    remedy = "r",
                    request = ProviderRequest(verb = "get", resource = "nodes", name = "n1"),
                ).toDisplayString()

        assertThat(text.lineSequence().first()).isEqualTo("Kubernetes denied permission: get nodes/n1.")
    }

    @Test
    fun `a rejection names the policy, its level and each violation`() {
        val text =
            Event.Command
                .ProviderError(
                    provider = "Kubernetes",
                    kind = ProviderErrorKind.REJECTED,
                    detail = "pods \"x\" is forbidden",
                    remedy = "Relax the policy",
                    request = ProviderRequest(verb = "create", resource = "pods", namespace = "lab", name = "x"),
                    policy =
                        ProviderPolicy(
                            name = "PodSecurity",
                            level = "restricted:latest",
                            violations = listOf("allowPrivilegeEscalation != false", "runAsNonRoot != true"),
                        ),
                ).toDisplayString()

        assertThat(text.lineSequence().first())
            .isEqualTo("Kubernetes rejected create pods/x in namespace lab: PodSecurity restricted:latest.")
        assertThat(text).contains("  - allowPrivilegeEscalation != false\n  - runAsNonRoot != true")
    }

    @Test
    fun `a rejection with neither request nor policy has a generic headline`() {
        val text =
            Event.Command
                .ProviderError("Kubernetes", ProviderErrorKind.REJECTED, "denied", "fix")
                .toDisplayString()

        assertThat(text.lineSequence().first()).isEqualTo("Kubernetes rejected this operation.")
    }
}
