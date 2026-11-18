package com.rustyrazorblade.easydblab.configuration.environment

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Each way a recorded `environment` value can be unreadable, and the message [EnvironmentRecord] names it with. */
class EnvironmentRecordTest {
    private val jackson = ObjectMapper()
    private val path = "/work/state.json"

    private fun problemOf(json: String?): String? = EnvironmentRecord.problem(json?.let(jackson::readTree), path)

    /** A type id missing from the map would make [EnvironmentRecord.problem] fail instead of checking its fields. */
    @Test
    fun `every type id declares its required fields, an empty list when it needs none`() {
        assertThat(EnvironmentRecord.requiredFields.keys).containsExactlyInAnyOrderElementsOf(Environment.TYPE_IDS)
        assertThat(EnvironmentRecord.requiredFields.filterValues { it.isEmpty() }.keys).containsExactly(Environment.EC2_K3S)
    }

    /** The record check guards `state.json`; the constructors guard every other way a variant is built. */
    @Test
    fun `a variant cannot be built with a relative path`() {
        assertThatThrownBy { Environment.K3sDocker("obs") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absolute")
        assertThatThrownBy { Environment.Kubeconfig("kube/config") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absolute")
        assertThat(Environment.Kubeconfig("/home/me/.kube/config").source).isEqualTo("/home/me/.kube/config")
    }

    @Test
    fun `no environment recorded, missing or null, is readable`() {
        assertThat(problemOf(null)).isNull()
        assertThat(problemOf("null")).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readable")
    fun `a well-formed environment of each type is readable`(json: String) {
        assertThat(problemOf(json)).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadable")
    fun `an unreadable environment names the file and the problem`(
        json: String,
        expected: List<String>,
    ) {
        assertThat(problemOf(json)).isNotNull().contains(path).contains(expected)
    }

    companion object {
        @JvmStatic
        fun readable(): List<String> =
            listOf(
                """{"type": "ec2-k3s"}""",
                """{"type": "k3s-docker", "dataDir": "/data"}""",
                """{"type": "kubeconfig", "source": "/home/me/.kube/config"}""",
            )

        @JvmStatic
        fun unreadable(): List<Arguments> {
            val known = "ec2-k3s, k3s-docker, kubeconfig"
            return listOf(
                Arguments.of("\"ec2-k3s\"", listOf("must be an object", known)),
                Arguments.of("[]", listOf("must be an object", known)),
                Arguments.of("{}", listOf("no type", known)),
                Arguments.of("""{"type": " "}""", listOf("no type", known)),
                Arguments.of("""{"type": 7}""", listOf("no type", known)),
                Arguments.of("""{"type": "moon-base"}""", listOf("Unknown execution environment 'moon-base'", known)),
                Arguments.of("""{"type": "k3s-docker"}""", listOf("'k3s-docker'", "'dataDir'")),
                Arguments.of("""{"type": "k3s-docker", "dataDir": " "}""", listOf("'k3s-docker'", "'dataDir'")),
                Arguments.of("""{"type": "kubeconfig"}""", listOf("'kubeconfig'", "'source'")),
                Arguments.of("""{"type": "kubeconfig", "source": ""}""", listOf("'kubeconfig'", "'source'")),
                Arguments.of("""{"type": "kubeconfig", "source": 3}""", listOf("'kubeconfig'", "'source'")),
                Arguments.of("""{"type": "k3s-docker", "dataDir": "obs"}""", listOf("'k3s-docker'", "'dataDir'", "absolute", "obs")),
                Arguments.of(
                    """{"type": "kubeconfig", "source": "kube/config"}""",
                    listOf("'kubeconfig'", "'source'", "absolute", "kube/config"),
                ),
            )
        }
    }
}
