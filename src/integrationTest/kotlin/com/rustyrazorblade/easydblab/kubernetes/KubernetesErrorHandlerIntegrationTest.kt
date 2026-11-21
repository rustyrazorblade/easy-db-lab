package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [KubernetesErrorHandler] against messages a real API server writes: an RBAC denial for a
 * ServiceAccount bound to nothing, and a Pod Security rejection of a privileged Pod in a namespace
 * that enforces `restricted`. The unit tests parse the documented forms; this pins them to K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesErrorHandlerIntegrationTest {
    private companion object {
        const val RBAC_NAMESPACE = "handler-rbac"
        const val RESTRICTED_NAMESPACE = "handler-restricted"
        const val SERVICE_ACCOUNT = "no-rbac"
        const val ENFORCE_LABEL = "pod-security.kubernetes.io/enforce"
    }

    private lateinit var admin: KubernetesClient
    private val handler = KubernetesErrorHandler()

    @BeforeAll
    fun setup() {
        admin = SharedK3s.client()
        SharedK3s.createNamespace(RBAC_NAMESPACE)
        SharedK3s.createNamespace(RESTRICTED_NAMESPACE)
        admin.namespaces().withName(RESTRICTED_NAMESPACE).edit {
            it.apply { metadata.labels = metadata.labels.orEmpty() + (ENFORCE_LABEL to "restricted") }
        }
        admin
            .serviceAccounts()
            .inNamespace(RBAC_NAMESPACE)
            .resource(
                ServiceAccountBuilder()
                    .withNewMetadata()
                    .withName(SERVICE_ACCOUNT)
                    .endMetadata()
                    .build(),
            ).createOr { it.update() }
    }

    @AfterAll
    fun teardown() {
        admin.close()
    }

    /** A client that authenticates as the ServiceAccount, with a token the API server issued for it. */
    private fun serviceAccountClient(): KubernetesClient {
        val token =
            admin
                .serviceAccounts()
                .inNamespace(RBAC_NAMESPACE)
                .withName(SERVICE_ACCOUNT)
                .tokenRequest()
                .status
                .token
        val config =
            Config.fromKubeconfig(SharedK3s.kubeConfigYaml()).apply {
                clientCertData = null
                clientKeyData = null
                oauthToken = token
                autoOAuthToken = token
            }
        return KubernetesClientBuilder().withConfig(config).build()
    }

    private fun describe(call: () -> Unit): ErrorDescription? =
        handler.describe(catchThrowableOfType(KubernetesClientException::class.java) { call() })

    @Test
    fun `a ServiceAccount with no RBAC is denied, named with the verb, resource, namespace and principal`() {
        val description =
            serviceAccountClient().use { client ->
                describe {
                    client
                        .pods()
                        .inNamespace(RBAC_NAMESPACE)
                        .list()
                }
            }

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.PERMISSION_DENIED)
        assertThat(description?.request?.verb).isEqualTo("list")
        assertThat(description?.request?.resource).isEqualTo("pods")
        assertThat(description?.request?.namespace).isEqualTo(RBAC_NAMESPACE)
        assertThat(description?.request?.principal).isEqualTo("system:serviceaccount:$RBAC_NAMESPACE:$SERVICE_ACCOUNT")
        assertThat(description?.remedy).contains("--serviceaccount=$RBAC_NAMESPACE:$SERVICE_ACCOUNT")
    }

    @Test
    fun `a privileged Pod in a restricted namespace is rejected by Pod Security, naming each violating field`() {
        val pod =
            PodBuilder()
                .withNewMetadata()
                .withName("privileged")
                .withNamespace(RESTRICTED_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("c")
                .withImage("busybox")
                .withNewSecurityContext()
                .withPrivileged(true)
                .endSecurityContext()
                .endContainer()
                .endSpec()
                .build()

        val description =
            describe {
                admin
                    .pods()
                    .inNamespace(RESTRICTED_NAMESPACE)
                    .resource(pod)
                    .dryRun()
                    .create()
            }

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("PodSecurity")
        assertThat(description?.policy?.level).startsWith("restricted:")
        assertThat(description?.policy?.violations)
            .hasSizeGreaterThan(1)
            .anySatisfy { assertThat(it).contains("privileged") }
            .anySatisfy { assertThat(it).contains("securityContext.runAsNonRoot") }
        assertThat(description?.request?.name).isEqualTo("privileged")
        assertThat(description?.request?.namespace).isEqualTo(RESTRICTED_NAMESPACE)
    }
}
