package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.services.ObservabilityResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * How `--cluster x` finds the tenant x is in (design section 7, spike S7): from the workspace's state
 * with no query when it is x's own; with one tenant in the store, that tenant, checked to hold x;
 * with several, one federated series query whose answer names each series' tenant in
 * `__tenant_id__`. The label-values endpoint for `__tenant_id__` is never used: it answers from the
 * header's tenant list and ignores the match.
 */
class ClusterTenantResolverTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** The series each tenant header answers for `up{cluster="<c>"}`, by cluster. */
    private var answers: Map<String, Map<String, List<Map<String, String>>>> = emptyMap()

    private val backends =
        FakeBackends { call ->
            val cluster =
                call
                    .param("match[]")
                    ?.substringAfter("cluster=\"")
                    ?.substringBefore('"')
            FakeBackends.series(*answers[call.tenant]?.get(cluster).orEmpty().toTypedArray())
        }

    private fun resolver(
        storeTenants: List<String>,
        workspace: WorkspaceCluster? = null,
    ) = ClusterTenantResolver(MimirApi(backends::forTenant, clock), storeTenants, workspace)

    @Test
    fun `inside the cluster's own workspace the state's tenant is used, with no query`() {
        val tenants = resolver(listOf("acme", "default"), WorkspaceCluster("lab-1", "acme")).resolve(listOf("lab-1"))

        assertThat(tenants).containsExactly(entry("lab-1", "acme"))
        assertThat(backends.calls).isEmpty()
    }

    @Test
    fun `with one tenant in the store, it is the tenant, checked with a series query that names no other tenant`() {
        answers = mapOf("acme" to mapOf("lab-1" to listOf(mapOf("__name__" to "up", "cluster" to "lab-1"))))

        val tenants = resolver(listOf("acme")).resolve(listOf("lab-1"))

        assertThat(tenants).containsExactly(entry("lab-1", "acme"))
        val call = backends.calls.single()
        assertThat(call.tenant).isEqualTo("acme")
        assertThat(call.route).isEqualTo("/prometheus/api/v1/series")
        assertThat(call.params).containsExactly(
            "match[]" to "up{cluster=\"lab-1\"}",
            "start" to "0",
            "end" to now.epochSecond.toString(),
        )
    }

    @Test
    fun `with one tenant that does not hold the cluster, it fails naming the cluster and the tenant`() {
        assertThatThrownBy { resolver(listOf("acme")).resolve(listOf("nope-1")) }
            .isInstanceOfSatisfying(ClusterNotFoundException::class.java) {
                assertThat(it.cluster).isEqualTo("nope-1")
                assertThat(it.searched).containsExactly("acme")
            }.hasMessageContaining("nope-1")
            .hasMessageContaining("acme")
    }

    @Test
    fun `with no tenant in the store, it fails naming the cluster`() {
        assertThatThrownBy { resolver(emptyList()).resolve(listOf("lab-1")) }
            .isInstanceOfSatisfying(ClusterNotFoundException::class.java) { assertThat(it.searched).isEmpty() }
            .hasMessageContaining("lab-1")
        assertThat(backends.calls).isEmpty()
    }

    @Test
    fun `with two tenants, one federated series query names the tenant of each series, pre-label ones included`() {
        answers =
            mapOf(
                "acme|default" to
                    mapOf(
                        "lab-1" to
                            listOf(
                                mapOf("__tenant_id__" to "acme", "cluster" to "lab-1", "edl_tenant" to "acme", "experiment" to "e"),
                                // From before the label: no edl_tenant, no experiment.
                                mapOf("__tenant_id__" to "acme", "cluster" to "lab-1", "instance" to "db0"),
                            ),
                    ),
            )

        val tenants = resolver(listOf("acme", "default")).resolve(listOf("lab-1"))

        assertThat(tenants).containsExactly(entry("lab-1", "acme"))
        assertThat(backends.calls.single().tenant).isEqualTo("acme|default")
        assertThat(backends.calls.map { it.route }).noneMatch { it.contains("__tenant_id__") }
    }

    @Test
    fun `a cluster in two tenants fails naming both`() {
        answers =
            mapOf(
                "acme|default" to
                    mapOf("dup-1" to listOf(mapOf("__tenant_id__" to "acme", "cluster" to "dup-1"), mapOf("__tenant_id__" to "default"))),
            )

        assertThatThrownBy { resolver(listOf("acme", "default")).resolve(listOf("dup-1")) }
            .isInstanceOfSatisfying(ClusterInSeveralTenantsException::class.java) {
                assertThat(it.cluster).isEqualTo("dup-1")
                assertThat(it.tenants).containsExactly("acme", "default")
            }
    }

    @Test
    fun `with two tenants and no series, it fails naming the cluster and every tenant searched`() {
        assertThatThrownBy { resolver(listOf("acme", "default")).resolve(listOf("nope-1")) }
            .isInstanceOfSatisfying(ClusterNotFoundException::class.java) {
                assertThat(it.searched).containsExactly("acme", "default")
            }
    }

    @Test
    fun `a federated series with no tenant label fails naming the tenant set`() {
        answers = mapOf("acme|default" to mapOf("lab-1" to listOf(mapOf("cluster" to "lab-1"))))

        assertThatThrownBy { resolver(listOf("acme", "default")).resolve(listOf("lab-1")) }
            .isInstanceOf(ClusterTenantUnknownException::class.java)
            .hasMessageContaining("acme|default")
    }

    @Test
    fun `clusters of different tenants are resolved each to its own, in the order given`() {
        answers =
            mapOf(
                "acme|default" to
                    mapOf(
                        "x-1" to listOf(mapOf("__tenant_id__" to "acme")),
                        "w-2" to listOf(mapOf("__tenant_id__" to "default")),
                    ),
            )

        val tenants = resolver(listOf("acme", "default"), WorkspaceCluster("other-3", "acme")).resolve(listOf("x-1", "w-2"))

        assertThat(tenants.toList()).containsExactly("x-1" to "acme", "w-2" to "default")
    }

    @Test
    fun `a refused query fails with Mimir's answer`() {
        val failing = FakeBackends { ObservabilityResponse(503, "store-gateway unavailable") }

        assertThatThrownBy {
            ClusterTenantResolver(MimirApi(failing::forTenant, clock), listOf("acme"), null).resolve(listOf("lab-1"))
        }.hasMessageContaining("503")
            .hasMessageContaining("store-gateway unavailable")
    }

    private fun entry(
        key: String,
        value: String,
    ) = java.util.AbstractMap.SimpleEntry(key, value)
}
