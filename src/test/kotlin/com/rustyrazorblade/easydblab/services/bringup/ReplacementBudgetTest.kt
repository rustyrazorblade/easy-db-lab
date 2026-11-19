package com.rustyrazorblade.easydblab.services.bringup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

class ReplacementBudgetTest {
    @ParameterizedTest(name = "{0} nodes may replace {1}")
    @CsvSource("1, 3", "3, 3", "20, 3", "60, 3", "61, 4", "80, 4", "81, 5", "1000, 50")
    fun `the cluster limit is the larger of 3 and 5 percent of the nodes, rounded up`(
        nodes: Int,
        limit: Int,
    ) {
        assertThat(ReplacementBudget(nodes).clusterLimit).isEqualTo(limit)
    }

    @Test
    fun `one node gets two replacements, and the third is refused`() {
        val budget = ReplacementBudget(nodeCount = 100)

        assertThat(budget.tryTake("db5")).isEqualTo(ReplacementBudget.Grant.Granted(replacement = 1, limit = 2))
        assertThat(budget.tryTake("db5")).isEqualTo(ReplacementBudget.Grant.Granted(replacement = 2, limit = 2))
        assertThat(budget.tryTake("db5")).isEqualTo(ReplacementBudget.Grant.NodeLimitReached(limit = 2))
        assertThat(budget.tryTake("db6")).isEqualTo(ReplacementBudget.Grant.Granted(replacement = 1, limit = 2))
    }

    @Test
    fun `once the cluster limit is spent no node gets a replacement`() {
        val budget = ReplacementBudget(nodeCount = 5)

        listOf("db0", "db0", "db1").forEach { assertThat(budget.tryTake(it)).isInstanceOf(ReplacementBudget.Grant.Granted::class.java) }

        assertThat(budget.tryTake("db2")).isEqualTo(ReplacementBudget.Grant.ClusterLimitReached(limit = 3))
        assertThat(budget.tryTake("db1")).isEqualTo(ReplacementBudget.Grant.ClusterLimitReached(limit = 3))
        assertThat(budget.used).isEqualTo(3)
    }

    @Test
    fun `under any sequence of requests no node passes 2 and the cluster never passes its limit`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val nodes = random.nextInt(1, 400)
            val budget = ReplacementBudget(nodes)
            val granted = mutableMapOf<String, Int>()

            repeat(random.nextInt(0, 600)) {
                val alias = "db${random.nextInt(nodes)}"
                val modelAllows = granted.getOrDefault(alias, 0) < 2 && granted.values.sum() < budget.clusterLimit
                val grant = budget.tryTake(alias)
                assertThat(grant is ReplacementBudget.Grant.Granted).describedAs("seed $seed, $alias").isEqualTo(modelAllows)
                if (grant is ReplacementBudget.Grant.Granted) granted.merge(alias, 1, Int::plus)
            }

            assertThat(granted.values).allMatch { it <= 2 }
            assertThat(budget.used).isEqualTo(granted.values.sum()).isLessThanOrEqualTo(budget.clusterLimit)
        }
    }
}
