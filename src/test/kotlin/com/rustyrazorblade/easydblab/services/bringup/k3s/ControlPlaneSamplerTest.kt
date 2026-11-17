package com.rustyrazorblade.easydblab.services.bringup.k3s

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ControlPlaneSamplerTest {
    private val name = "apiserver_flowcontrol_rejected_requests_total"

    @Test
    fun `the rejected requests of every priority level are summed, and other series are ignored`() {
        val metrics =
            """
            # HELP $name [BETA] Number of requests rejected by API Priority and Fairness subsystem
            # TYPE $name counter
            $name{flow_schema="system-nodes",priority_level="node-high",reason="queue-full"} 2027
            $name{flow_schema="global-default",priority_level="global-default",reason="time-out"} 596
            ${name}_other{priority_level="node-high"} 1000
            apiserver_flowcontrol_dispatched_requests_total{priority_level="node-high"} 99999
            """.trimIndent()

        assertThat(ControlPlaneSampler.rejectedRequests(metrics)).isEqualTo(2623.0)
    }

    @Test
    fun `no series means nothing was rejected yet`() {
        assertThat(ControlPlaneSampler.rejectedRequests("# TYPE $name counter\nup 1\n")).isZero()
    }

    @Test
    fun `a label value holding a brace or spaces, a timestamp and exponent notation are all read`() {
        val metrics =
            """
            $name{flow_schema="a } b",priority_level="x y",reason="time-out"} 1.5e3 1700000000000
            $name{flow_schema="quote \" }",priority_level="z",reason="cancelled"} 7
            $name 3
            """.trimIndent()

        assertThat(ControlPlaneSampler.rejectedRequests(metrics)).isEqualTo(1510.0)
    }

    @Test
    fun `the sum of any set of series is the sum of their values`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val values = List(random.nextInt(0, 12)) { random.nextInt(0, 100_000).toDouble() }
            val metrics =
                values
                    .mapIndexed { i, v -> "$name{priority_level=\"level-$i\",reason=\"r $i\"} $v" }
                    .plus("other_metric{a=\"b\"} ${random.nextInt()}")
                    .shuffled(random)
                    .joinToString("\n")

            assertThat(ControlPlaneSampler.rejectedRequests(metrics)).isCloseTo(values.sum(), within(0.0001))
        }
    }
}
