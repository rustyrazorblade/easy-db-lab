package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogQlTest {
    private val cluster = "lab-0f1e2d3c"

    @Test
    fun `with no filter every line of the current cluster matches`() {
        assertThat(LogQl.logsQuery(cluster)).isEqualTo("""{cluster="lab-0f1e2d3c"}""")
    }

    @Test
    fun `the source is a stream label, host and unit filter lines, and grep matches text`() {
        val query = LogQl.logsQuery(cluster, source = "cassandra", host = "db0", unit = "cassandra.service", grep = "OutOfMemory")

        assertThat(query).isEqualTo(
            """{cluster="lab-0f1e2d3c", source="cassandra"} | host_name="db0" | systemd_unit="cassandra.service" |= "OutOfMemory"""",
        )
    }

    @Test
    fun `quotes and backslashes in a value cannot break out of its string`() {
        val query = LogQl.logsQuery(cluster, grep = """say "hi" \ bye""")

        assertThat(query).isEqualTo("""{cluster="lab-0f1e2d3c"} |= "say \"hi\" \\ bye"""")
    }

    @Test
    fun `a Spark step's lines are those of the service the Java agent names after the step`() {
        assertThat(LogQl.sparkStep(cluster, "BulkWriter")).isEqualTo("""{cluster="lab-0f1e2d3c", service_name="spark-BulkWriter"}""")
    }

    @Test
    fun `Spark job lines are those of every spark service in the cluster`() {
        assertThat(LogQl.sparkJobs(cluster)).isEqualTo("""{cluster="lab-0f1e2d3c", service_name=~"spark-.+"}""")
    }

    @Test
    fun `a trace's logs are found by trace id across the selected clusters`() {
        assertThat(LogQl.traceToLogs("\${__trace.traceId}")).isEqualTo("""{cluster=~".+"} | trace_id="${'$'}{__trace.traceId}"""")
    }
}
