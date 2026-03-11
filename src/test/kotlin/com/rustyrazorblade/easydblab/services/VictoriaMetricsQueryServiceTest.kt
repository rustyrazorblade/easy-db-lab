package com.rustyrazorblade.easydblab.services

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VictoriaMetricsQueryServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses successful vector response with host_name labels`() {
        val responseJson =
            """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {
                    "metric": {"host_name": "db-0"},
                    "value": [1709913600, "34.2"]
                  },
                  {
                    "metric": {"host_name": "db-1"},
                    "value": [1709913600, "28.7"]
                  }
                ]
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<PromQueryResponse>(responseJson)

        assertThat(parsed.status).isEqualTo("success")
        assertThat(parsed.data?.result).hasSize(2)

        val db0 = parsed.data!!.result[0]
        assertThat(db0.metric["host_name"]).isEqualTo("db-0")
        assertThat(db0.numericValue()).isEqualTo(34.2)

        val db1 = parsed.data!!.result[1]
        assertThat(db1.metric["host_name"]).isEqualTo("db-1")
        assertThat(db1.numericValue()).isEqualTo(28.7)
    }

    @Test
    fun `parses empty result set`() {
        val responseJson =
            """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": []
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<PromQueryResponse>(responseJson)

        assertThat(parsed.status).isEqualTo("success")
        assertThat(parsed.data?.result).isEmpty()
    }

    @Test
    fun `parses error response`() {
        val responseJson =
            """
            {
              "status": "error",
              "errorType": "bad_data",
              "error": "invalid query"
            }
            """.trimIndent()

        val parsed = json.decodeFromString<PromQueryResponse>(responseJson)

        assertThat(parsed.status).isEqualTo("error")
        assertThat(parsed.errorType).isEqualTo("bad_data")
        assertThat(parsed.error).isEqualTo("invalid query")
    }

    @Test
    fun `numericValue handles NaN`() {
        val responseJson =
            """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {
                    "metric": {"host_name": "db-0"},
                    "value": [1709913600, "NaN"]
                  }
                ]
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<PromQueryResponse>(responseJson)
        assertThat(parsed.data!!.result[0].numericValue()).isNull()
    }

    @Test
    fun `numericValue handles scalar result`() {
        val result =
            PromQueryResult(
                metric = emptyMap(),
                value =
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive(1709913600),
                        kotlinx.serialization.json.JsonPrimitive("42.5"),
                    ),
            )

        assertThat(result.numericValue()).isEqualTo(42.5)
    }

    @Test
    fun `parses response with no metric labels`() {
        val responseJson =
            """
            {
              "status": "success",
              "data": {
                "resultType": "vector",
                "result": [
                  {
                    "metric": {},
                    "value": [1709913600, "1500.0"]
                  }
                ]
              }
            }
            """.trimIndent()

        val parsed = json.decodeFromString<PromQueryResponse>(responseJson)

        assertThat(parsed.data!!.result).hasSize(1)
        assertThat(parsed.data!!.result[0].metric).isEmpty()
        assertThat(parsed.data!!.result[0].numericValue()).isEqualTo(1500.0)
    }
}
