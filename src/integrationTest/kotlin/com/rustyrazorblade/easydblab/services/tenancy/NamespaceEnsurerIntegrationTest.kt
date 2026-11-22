package com.rustyrazorblade.easydblab.services.tenancy

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedK3s
import com.rustyrazorblade.easydblab.configuration.tenancy.InstallScope
import com.rustyrazorblade.easydblab.configuration.tenancy.K8sNamespace
import com.rustyrazorblade.easydblab.configuration.tenancy.OwnershipLabels
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.http.BasicBuilder
import io.fabric8.kubernetes.client.http.HttpRequest
import io.fabric8.kubernetes.client.http.Interceptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NamespaceEnsurer] against a real API server: an absent namespace is created labelled, and an
 * existing unlabelled one, `default` included, is adopted with its own labels kept (design D11),
 * and a create that loses a race with another creator gets K3s's own 409 and is retried once.
 * Each test uses a namespace of its own on the shared K3s.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamespaceEnsurerIntegrationTest {
    private lateinit var client: KubernetesClient

    @BeforeAll
    fun setup() {
        client = SharedK3s.client()
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun scope(namespace: String) = InstallScope(K8sNamespace(namespace), "ensurer-cluster")

    private fun labelsOf(namespace: String): Map<String, String> =
        client
            .namespaces()
            .withName(namespace)
            .get()
            .metadata.labels
            .orEmpty()

    @Test
    fun `an absent namespace is created carrying both ownership labels`() {
        val plan = NamespaceEnsurer.ensure(client, scope("ensurer-absent"))

        assertThat(plan).isInstanceOf(NamespacePlan.Create::class.java)
        assertThat(labelsOf("ensurer-absent")).containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
    }

    @Test
    fun `an existing unlabelled namespace is adopted, keeping its own labels`() {
        SharedK3s.createNamespace("ensurer-existing")
        client.namespaces().withName("ensurer-existing").edit {
            it.apply { metadata.labels = metadata.labels.orEmpty() + ("team" to "storage") }
        }

        val plan = NamespaceEnsurer.ensure(client, scope("ensurer-existing"))

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        assertThat(labelsOf("ensurer-existing"))
            .containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
            .containsEntry("team", "storage")
        assertThat(NamespaceEnsurer.ensure(client, scope("ensurer-existing"))).isInstanceOf(NamespacePlan.Unchanged::class.java)
    }

    @Test
    fun `the default namespace is adopted, not refused`() {
        val plan = NamespaceEnsurer.ensure(client, InstallScope(K8sNamespace("default"), "ensurer-default"))

        assertThat(plan).isNotInstanceOf(NamespacePlan.Create::class.java)
        assertThat(labelsOf("default")).containsAllEntriesOf(OwnershipLabels.of("ensurer-default"))
    }

    /**
     * A client whose first [races] namespace creates each lose a race, answered 409 by K3s itself,
     * as when another `up` or probe creates the namespace between this one's read and its create.
     * Before each of those reads reaches the API server another client makes sure [namespace] is
     * absent, so the read plans a create; before each of those creates the other client creates it.
     */
    private fun racingClient(
        namespace: String,
        races: Int,
    ): KubernetesClient {
        val reads = AtomicInteger()
        val creates = AtomicInteger()
        val race =
            object : Interceptor {
                override fun before(
                    builder: BasicBuilder,
                    request: HttpRequest,
                    tags: Interceptor.RequestTags,
                ) {
                    val path = request.uri().path
                    when {
                        request.method() == "GET" && path == "/api/v1/namespaces/$namespace" ->
                            if (reads.getAndIncrement() < races) removeNamespace(namespace)
                        request.method() == "POST" && path == "/api/v1/namespaces" ->
                            if (creates.getAndIncrement() < races) createBareNamespace(namespace)
                    }
                }
            }
        return KubernetesClientBuilder()
            .withConfig(Config.fromKubeconfig(SharedK3s.kubeConfigYaml()))
            .withHttpClientBuilderConsumer { it.addOrReplaceInterceptor("namespace-race", race) }
            .build()
    }

    private fun removeNamespace(namespace: String) {
        val named = client.namespaces().withName(namespace)
        named.delete()
        named.waitUntilCondition({ it == null }, RACE_WAIT_MINUTES, TimeUnit.MINUTES)
    }

    private fun createBareNamespace(namespace: String) {
        client
            .namespaces()
            .resource(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .endMetadata()
                    .build(),
            ).create()
    }

    @Test
    fun `a namespace labelled by an earlier init of the same cluster takes the new cluster id, and the next up is unchanged`() {
        SharedK3s.createNamespace("ensurer-stale")
        client.namespaces().withName("ensurer-stale").edit {
            it.apply { metadata.labels = metadata.labels.orEmpty() + OwnershipLabels.of("old-id") + ("team" to "storage") }
        }

        val plan = NamespaceEnsurer.ensure(client, scope("ensurer-stale"))

        assertThat((plan as NamespacePlan.Adopt).replaced)
            .containsExactly(ReplacedLabel(Constants.K8s.CLUSTER_LABEL, "old-id", "ensurer-cluster"))
        assertThat(labelsOf("ensurer-stale"))
            .containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
            .containsEntry("team", "storage")
        assertThat(NamespaceEnsurer.ensure(client, scope("ensurer-stale"))).isInstanceOf(NamespacePlan.Unchanged::class.java)
    }

    @Test
    fun `a create that loses the race is planned once more from the namespace that won, which is adopted`() {
        val plan = racingClient("ensurer-race", races = 1).use { NamespaceEnsurer.ensure(it, scope("ensurer-race")) }

        assertThat(plan).isInstanceOf(NamespacePlan.Adopt::class.java)
        assertThat(labelsOf("ensurer-race")).containsAllEntriesOf(OwnershipLabels.of("ensurer-cluster"))
    }

    @Test
    fun `a create that loses the race twice fails with the second conflict instead of retrying without end`() {
        val thrown =
            catchThrowable {
                racingClient("ensurer-race-twice", races = 2).use { NamespaceEnsurer.ensure(it, scope("ensurer-race-twice")) }
            }

        assertThat(thrown).isInstanceOf(KubernetesClientException::class.java)
        assertThat((thrown as KubernetesClientException).code).isEqualTo(Constants.HttpStatus.CONFLICT)
    }

    private companion object {
        const val RACE_WAIT_MINUTES = 2L
    }
}
