package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class MimirQueryServiceTest {
    @Test
    fun `an instant query goes to Mimir's Prometheus API and returns each series with its value`() {
        val http =
            RecordingObservabilityHttp(
                ObservabilityResponse(
                    200,
                    """
                    {"status":"success","data":{"resultType":"vector","result":[
                      {"metric":{"host_name":"db-0"},"value":[1709913600,"34.2"]},
                      {"metric":{"host_name":"db-1"},"value":[1709913600,"NaN"]}]}}
                    """.trimIndent(),
                ),
            )

        val results = DefaultMimirQueryService(http).query("""up{cluster="lab-1"}""").getOrThrow()

        val call = http.calls.single()
        assertThat(call.port).isEqualTo(9009)
        assertThat(call.path).startsWith("/prometheus/api/v1/query?query=")
        assertThat(URLDecoder.decode(call.path.substringAfter("query="), Charsets.UTF_8)).isEqualTo("""up{cluster="lab-1"}""")
        assertThat(results.map { it.metric["host_name"] to it.numericValue() }).containsExactly("db-0" to 34.2, "db-1" to null)
    }

    @Test
    fun `a refused query fails with Mimir's answer`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(400, "bad_data: parse error"))

        val result = DefaultMimirQueryService(http).query("up{")

        assertThat(result.exceptionOrNull()).hasMessageContaining("400").hasMessageContaining("parse error")
    }

    @Test
    fun `an error status in a 200 body fails`() {
        val http = RecordingObservabilityHttp(ObservabilityResponse(200, """{"status":"error","errorType":"timeout","error":"slow"}"""))

        assertThat(DefaultMimirQueryService(http).query("up").exceptionOrNull()).hasMessageContaining("timeout")
    }
}
