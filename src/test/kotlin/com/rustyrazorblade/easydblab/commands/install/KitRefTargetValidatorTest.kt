package com.rustyrazorblade.easydblab.commands.install

import com.rustyrazorblade.easydblab.services.KitArgSpec
import com.rustyrazorblade.easydblab.services.KitCapability
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.KitEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KitRefTargetValidatorTest {
    private val loaded = mutableListOf<String>()

    private fun validatorFor(lookup: KitRefTargetValidator.TargetLookup) =
        KitRefTargetValidator { name ->
            loaded += name
            lookup
        }

    private fun kitRef(
        capability: String = "",
        endpoints: List<KitEndpoint.EndpointType> = emptyList(),
    ) = KitArgSpec(
        flag = "--target",
        variable = "TARGET",
        type = KitArgSpec.ArgType.KIT_REF,
        capability = capability,
        endpoints = endpoints,
    )

    private fun endpoint(type: KitEndpoint.EndpointType) = KitEndpoint(name = type.name, nodeType = "db", port = 1, type = type)

    private fun installed(
        endpoints: List<KitEndpoint.EndpointType> = emptyList(),
        capabilities: List<String> = emptyList(),
    ) = KitRefTargetValidator.TargetLookup.Installed(
        KitConfig(
            name = "target",
            endpoints = endpoints.map(::endpoint),
            capabilities = capabilities.map { KitCapability(type = it) },
        ),
    )

    @Test
    fun `no capability and no endpoints never loads the target`() {
        val validator = validatorFor(KitRefTargetValidator.TargetLookup.NotInstalled("/nowhere/kit.yaml"))

        validator.validate(kitName = "bench", kitRefArg = kitRef(), targetName = "tidb")

        assertThat(loaded).isEmpty()
    }

    @Test
    fun `target not installed keeps the is not installed message`() {
        val validator = validatorFor(KitRefTargetValidator.TargetLookup.NotInstalled("/ws/tidb/kit.yaml"))

        assertThatThrownBy {
            validator.validate("clickhouse-benchmark", kitRef(endpoints = listOf(KitEndpoint.EndpointType.NATIVE)), "tidb")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target kit 'tidb' is not installed (expected /ws/tidb/kit.yaml)")
    }

    @Test
    fun `missing capability keeps its message`() {
        val validator = validatorFor(installed())

        assertThatThrownBy { validator.validate("sysbench", kitRef(capability = "sql"), "presto") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target kit 'presto' does not have the 'sql' capability required by sysbench")
    }

    @Test
    fun `one missing endpoint type is named in the singular`() {
        val validator = validatorFor(installed(endpoints = listOf(KitEndpoint.EndpointType.HTTP)))

        assertThatThrownBy {
            validator.validate(
                "clickhouse-benchmark",
                kitRef(endpoints = listOf(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP)),
                "tidb",
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target kit 'tidb' does not have the 'native' endpoint required by clickhouse-benchmark")
    }

    @Test
    fun `two missing endpoint types are named in one message`() {
        val validator = validatorFor(installed(endpoints = listOf(KitEndpoint.EndpointType.JDBC)))

        assertThatThrownBy {
            validator.validate(
                "clickhouse-benchmark",
                kitRef(endpoints = listOf(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP)),
                "presto",
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Target kit 'presto' does not have the 'native' and 'http' endpoints required by clickhouse-benchmark")
    }

    @Test
    fun `every required endpoint type present passes and loads the target once`() {
        val validator =
            validatorFor(
                installed(
                    endpoints = listOf(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP, KitEndpoint.EndpointType.JDBC),
                    capabilities = listOf("sql"),
                ),
            )

        assertThatCode {
            validator.validate(
                "clickhouse-benchmark",
                kitRef(capability = "sql", endpoints = listOf(KitEndpoint.EndpointType.NATIVE, KitEndpoint.EndpointType.HTTP)),
                "clickhouse",
            )
        }.doesNotThrowAnyException()
        assertThat(loaded).containsExactly("clickhouse")
    }
}
