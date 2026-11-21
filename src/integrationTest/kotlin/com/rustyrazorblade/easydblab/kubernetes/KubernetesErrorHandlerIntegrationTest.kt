package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.providers.aws.pollUntil
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
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
import java.time.Duration

/**
 * [KubernetesErrorHandler] against messages a real API server writes: an RBAC denial for a
 * ServiceAccount bound to nothing, and a Pod Security rejection of a privileged Pod in a namespace
 * that enforces `restricted`, a create over a full ResourceQuota, and a create in a namespace held in
 * Terminating. The unit tests parse the documented forms; this pins them to K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesErrorHandlerIntegrationTest {
    private companion object {
        const val RBAC_NAMESPACE = "handler-rbac"
        const val RESTRICTED_NAMESPACE = "handler-restricted"
        const val SERVICE_ACCOUNT = "no-rbac"
        const val ENFORCE_LABEL = "pod-security.kubernetes.io/enforce"
        const val QUOTA_NAMESPACE = "handler-quota"
        const val QUOTA = "no-configmaps"
        const val TERMINATING_NAMESPACE = "handler-terminating"
        const val HOLD_FINALIZER = "easydblab.test/hold"
        const val QUOTA_POLLS = 120
        const val QUOTA_POLL_INTERVAL_MS = 500L
    }

    private lateinit var admin: KubernetesClient
    private val handler = KubernetesErrorHandler()

    @BeforeAll
    fun setup() {
        admin = SharedK3s.client()
        SharedK3s.createNamespace(RBAC_NAMESPACE)
        SharedK3s.createNamespace(RESTRICTED_NAMESPACE)
        SharedK3s.createNamespace(QUOTA_NAMESPACE)
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
        assertThat(description?.request?.verb).isEqualTo("POST")
        assertThat(description?.request?.resource).isEqualTo("pods")
        assertThat(description?.request?.name).isEqualTo("privileged")
        assertThat(description?.request?.namespace).isEqualTo(RESTRICTED_NAMESPACE)
    }

    /**
     * A full ResourceQuota answers 403 too. It is not an RBAC denial, so it must name the quota and
     * never send the user to cluster-admin; the request comes from Fabric8's own failure message.
     */
    @Test
    fun `a create over a full ResourceQuota is rejected naming the quota, with no cluster-admin advice`() {
        admin
            .resourceQuotas()
            .inNamespace(QUOTA_NAMESPACE)
            .resource(
                ResourceQuotaBuilder()
                    .withNewMetadata()
                    .withName(QUOTA)
                    .endMetadata()
                    .withNewSpec()
                    .addToHard("count/configmaps", Quantity("0"))
                    .endSpec()
                    .build(),
            ).createOr { it.update() }
        // Until the quota controller fills in its usage, admission answers "status unknown" instead.
        pollUntil(
            operationName = "quota-usage",
            maxAttempts = QUOTA_POLLS,
            interval = Duration.ofMillis(QUOTA_POLL_INTERVAL_MS),
            done = { !it.isNullOrEmpty() },
        ) {
            admin
                .resourceQuotas()
                .inNamespace(QUOTA_NAMESPACE)
                .withName(QUOTA)
                .get()
                ?.status
                ?.used
        }

        val description = describe { createConfigMap(QUOTA_NAMESPACE, "over-quota") }

        assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
        assertThat(description?.policy?.name).isEqualTo("ResourceQuota $QUOTA")
        assertThat(description?.request?.verb).isEqualTo("POST")
        assertThat(description?.request?.resource).isEqualTo("configmaps")
        assertThat(description?.request?.namespace).isEqualTo(QUOTA_NAMESPACE)
        assertThat(description?.remedy).contains(QUOTA).doesNotContain("cluster-admin")
    }

    /**
     * A namespace held in Terminating by a finalizer refuses new objects with a 403 that names the
     * namespace. The remedy is to wait, never to grant a role.
     */
    @Test
    fun `a create in a terminating namespace is rejected naming the namespace, with no cluster-admin advice`() {
        SharedK3s.createNamespace(TERMINATING_NAMESPACE)
        val held =
            ConfigMapBuilder()
                .withNewMetadata()
                .withName("held")
                .withFinalizers(HOLD_FINALIZER)
                .endMetadata()
                .build()
        admin
            .configMaps()
            .inNamespace(TERMINATING_NAMESPACE)
            .resource(held)
            .create()
        admin.namespaces().withName(TERMINATING_NAMESPACE).delete()
        try {
            // The admission plugin reads the namespace from its cache, so a create just after the
            // delete may still be admitted; ask until it is refused.
            val description =
                pollUntil(
                    operationName = "namespace-terminating",
                    maxAttempts = QUOTA_POLLS,
                    interval = Duration.ofMillis(QUOTA_POLL_INTERVAL_MS),
                    done = { it != null },
                ) { refusal { createConfigMap(TERMINATING_NAMESPACE, "late") } }

            assertThat(description?.kind).isEqualTo(ProviderErrorKind.REJECTED)
            assertThat(description?.request?.verb).isEqualTo("POST")
            assertThat(description?.request?.resource).isEqualTo("configmaps")
            assertThat(description?.request?.namespace).isEqualTo(TERMINATING_NAMESPACE)
            assertThat(description?.remedy).contains("kubectl get namespace $TERMINATING_NAMESPACE").doesNotContain("cluster-admin")
        } finally {
            admin.configMaps().inNamespace(TERMINATING_NAMESPACE).withName("held").edit {
                it.apply { metadata.finalizers = emptyList() }
            }
        }
    }

    /** How the handler describes [call]'s refusal, or null when the API server accepted it. */
    private fun refusal(call: () -> Unit): ErrorDescription? =
        try {
            call()
            null
        } catch (e: KubernetesClientException) {
            handler.describe(e)
        }

    private fun createConfigMap(
        namespace: String,
        name: String,
    ) {
        admin
            .configMaps()
            .inNamespace(namespace)
            .resource(
                ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .endMetadata()
                    .build(),
            ).dryRun()
            .create()
    }
}
