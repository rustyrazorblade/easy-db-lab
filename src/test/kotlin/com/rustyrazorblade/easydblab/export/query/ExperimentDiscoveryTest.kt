package com.rustyrazorblade.easydblab.export.query

import com.rustyrazorblade.easydblab.configuration.ExperimentKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Which clusters `--experiment e` exports, and which experiments a `--cluster` export covers (design
 * section 7 and the pre-label branch, section 8.2): every cluster labelled `experiment=e` in the
 * chosen tenant, and a cluster from before the label whose `<name>-<id>` is `e`; and for clusters,
 * each labelled experiment plus, for a pre-label cluster, its own id.
 */
class ExperimentDiscoveryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)
    private var labelValues: (BackendCall) -> List<String> = { emptyList() }
    private val backends =
        FakeBackends { call ->
            FakeBackends.prometheus(JsonArray(labelValues(call).map(::JsonPrimitive)).toString())
        }
    private val discovery = ExperimentDiscovery(MimirApi(backends::forTenant, clock))

    @Test
    fun `an experiment's clusters are its labelled clusters and a pre-label cluster named for it, in its tenant only`() {
        labelValues = { listOf(LABELLED, PRE_LABEL) }

        val clusters = discovery.clustersOf(ExperimentKey("acme", "e"))

        assertThat(clusters).containsExactly(LABELLED, PRE_LABEL)
        val call = backends.calls.single()
        assertThat(call.tenant).isEqualTo("acme")
        assertThat(call.route).isEqualTo("/prometheus/api/v1/label/cluster/values")
        assertThat(call.params.filter { it.first == "match[]" }.map { it.second })
            .containsExactly("up{experiment=\"e\"}", "up{experiment=\"\",cluster=\"e\"}")
        assertThat(call.param("start")).isEqualTo("0")
    }

    @Test
    fun `an experiment with no clusters fails naming the experiment, the tenant and the query`() {
        assertThatThrownBy { discovery.clustersOf(ExperimentKey("acme", "ghost")) }
            .isInstanceOfSatisfying(ExperimentNotFoundException::class.java) {
                assertThat(it.key).isEqualTo(ExperimentKey("acme", "ghost"))
                assertThat(it.query).contains("up{experiment=\"ghost\"}", "up{experiment=\"\",cluster=\"ghost\"}")
            }.hasMessageContaining("ghost")
            .hasMessageContaining("acme")
            .hasMessageContaining("up{experiment=\"ghost\"}")
    }

    @Test
    fun `a discovered cluster that is not a cluster id is refused naming it, and goes into no query`() {
        val injected = "x\"} or vector(1) #"
        labelValues = { listOf(LABELLED, injected, "short-1") }

        assertThatThrownBy { discovery.clustersOf(ExperimentKey("acme", "e")) }
            .isInstanceOfSatisfying(DiscoveredClusterIdRefused::class.java) {
                assertThat(it.key).isEqualTo(ExperimentKey("acme", "e"))
                assertThat(it.clusters).containsExactly(injected, "short-1")
            }.hasMessageContaining(injected)
        assertThat(backends.calls).describedAs("only the label-values lookup").hasSize(1)
    }

    @Test
    fun `the experiments clusters cover are their labelled experiments and each pre-label cluster's own id`() {
        labelValues = { call ->
            when (call.route) {
                "/prometheus/api/v1/label/experiment/values" -> listOf("exp-a")
                else -> listOf("old-1")
            }
        }

        val covered = discovery.coveredBy("acme", listOf("x-1", "old-1"))

        assertThat(covered).containsExactly(ExperimentKey("acme", "exp-a"), ExperimentKey("acme", "old-1"))
        val (labelled, preLabel) = backends.calls
        assertThat(labelled.params.single { it.first == "match[]" }.second).isEqualTo("up{cluster=~\"x-1|old-1\"}")
        assertThat(preLabel.route).isEqualTo("/prometheus/api/v1/label/cluster/values")
        assertThat(preLabel.params.single { it.first == "match[]" }.second).isEqualTo("up{cluster=~\"x-1|old-1\",experiment=\"\"}")
        assertThat(backends.calls.map { it.tenant }).containsOnly("acme")
    }

    @Test
    fun `a cluster id's regex characters are escaped in the alternation`() {
        assertThat(PromQlValues.anyOf(listOf("a.b-1", "c+d"))).isEqualTo("a\\\\.b-1|c\\\\+d")
    }

    private companion object {
        /** A cluster labelled with the experiment. */
        const val LABELLED = "lab-0b5f8c2a-1d3e-4f5a-9b7c-2e4d6f8a0b1c"

        /** A cluster from before the label; its own id is the experiment's name. */
        const val PRE_LABEL = "old-7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
