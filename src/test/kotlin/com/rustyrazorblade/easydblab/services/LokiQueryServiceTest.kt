package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class LokiQueryServiceTest {
    private val twoStreams =
        """
        {"status":"success","data":{"resultType":"streams","result":[
          {"stream":{"cluster":"lab-1","source":"cassandra","host_name":"db0"},
           "values":[["1790000000000000000","older line"],["1790000002000000000","newest line"]]},
          {"stream":{"cluster":"lab-1","source":"journald","host_name":"db1","systemd_unit":"docker.service"},
           "values":[["1790000001000000000","middle line"]]}]}}
        """.trimIndent()

    private fun params(path: String): Map<String, String> =
        path
            .substringAfter("?")
            .split("&")
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8) }

    @Test
    fun `a query goes to Loki's range API, newest first, with the time range and limit`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(200, twoStreams))

        DefaultLokiQueryService(http).query("""{cluster="lab-1"}""", since = "30m", limit = 50).getOrThrow()

        val call = http.calls.single()
        assertThat(call.port).isEqualTo(3100)
        assertThat(call.path).startsWith("/loki/api/v1/query_range?")
        assertThat(params(call.path)).containsExactlyInAnyOrderEntriesOf(
            mapOf("query" to """{cluster="lab-1"}""", "since" to "30m", "limit" to "50", "direction" to "backward"),
        )
    }

    @Test
    fun `lines from every stream come back newest first, with their source, host and unit`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(200, twoStreams))

        val lines = DefaultLokiQueryService(http).query("""{cluster="lab-1"}""", "1h", 100).getOrThrow()

        assertThat(lines).containsExactly(
            "[2026-09-21T14:13:22Z] [cassandra] [db0] newest line",
            "[2026-09-21T14:13:21Z] [journald] [db1] [docker.service] middle line",
            "[2026-09-21T14:13:20Z] [cassandra] [db0] older line",
        )
    }

    @Test
    fun `a refused query fails with Loki's answer`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(400, "parse error at line 1"))

        val result = DefaultLokiQueryService(http).query("{", "1h", 10)

        assertThat(result.exceptionOrNull()).hasMessageContaining("400").hasMessageContaining("parse error")
    }
}
