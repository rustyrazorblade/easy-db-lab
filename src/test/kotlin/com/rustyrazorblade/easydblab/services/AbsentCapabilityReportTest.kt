package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import com.rustyrazorblade.easydblab.configuration.NodeCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbsentCapabilityReportTest {
    private val stages =
        listOf(
            StageRequirement("Plain"),
            StageRequirement("Needs privilege A", setOf(NodeCapability.PRIVILEGED)),
            StageRequirement("Needs privilege B", setOf(NodeCapability.PRIVILEGED)),
            StageRequirement("Needs the journal", setOf(NodeCapability.SYSTEMD_JOURNAL)),
        )

    @Test
    fun `nothing is reported when every capability is present`() {
        assertThat(AbsentCapabilityReport.render(NodeCapabilities.AWS, stages)).isNull()
    }

    @Test
    fun `each absent capability is listed with its reason and every stage that requires it`() {
        val capabilities = NodeCapabilities(NodeCapability.entries.toSet() - NodeCapability.PRIVILEGED)

        val report = AbsentCapabilityReport.render(capabilities, stages)

        assertThat(report).isEqualTo(
            """
            Absent capabilities:
              privileged: ${NodeCapability.PRIVILEGED.absentReason}
                disables: Needs privilege A, Needs privilege B
            """.trimIndent(),
        )
    }

    @Test
    fun `an absent capability no stage requires says so`() {
        val capabilities =
            NodeCapabilities(NodeCapability.entries.toSet() - NodeCapability.HOST_PATH - NodeCapability.SYSTEMD_JOURNAL)

        val report = AbsentCapabilityReport.render(capabilities, stages)

        assertThat(report).isEqualTo(
            """
            Absent capabilities:
              systemd-journal: ${NodeCapability.SYSTEMD_JOURNAL.absentReason}
                disables: Needs the journal
              host-path: ${NodeCapability.HOST_PATH.absentReason}
                no component needs it yet
            """.trimIndent(),
        )
    }
}
