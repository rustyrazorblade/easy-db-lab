package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.YamlTestSupport
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.CompactorTaskDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Every backend config takes its object-store block from the store's fragment and nothing else.
 *
 * Each config is rendered with a stub fragment that names no S3 bucket, endpoint or URI. The stub
 * block must land at the path where the S3 block stands, and the rest of the config must hold no
 * S3 scheme, no AWS host and no S3 endpoint: any of those would be S3 left outside its fragment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreBlockStubTest : BaseKoinTest() {
    private val stubFragment =
        checkNotNull(javaClass.getResourceAsStream("stub-block.yaml")) { "missing stub-block.yaml" }
            .bufferedReader()
            .use { it.readText() }

    private val stub = StoreBlockRenderer(StoreBlockSource { stubFragment })

    private val state =
        ClusterState(
            name = "lab",
            versions = mutableMapOf(),
            clusterId = "stub",
            s3Bucket = "easy-db-lab-test",
            initConfig = InitConfig(tenant = "acme", region = "us-west-2"),
            hosts = mapOf(ServerType.Control to listOf(ClusterHost("54.0.0.1", "10.0.0.5", "control0", "us-west-2a"))),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { TemplateService(get(), get()) }
            },
        )

    private fun templates(): TemplateService = getKoin().get()

    /** Each backend config rendered with the stub, and the path of its object-store block. */
    fun renderedConfigs(): List<Arguments> =
        listOf(
            Arguments.of("mimir", listOf("blocks_storage")),
            Arguments.of("loki", listOf("common", "storage")),
            Arguments.of("tempo", listOf("storage", "trace")),
            Arguments.of("pyroscope", listOf("storage")),
            Arguments.of("compactor tempo-backend", listOf("storage", "trace")),
        )

    private fun render(name: String): String =
        when (name) {
            "mimir" -> MimirManifestBuilder(templates(), stub).buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
            "loki" -> LokiManifestBuilder(templates(), stub).buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
            "tempo" -> TempoManifestBuilder(templates(), stub).buildConfigMap().data.getValue("tempo.yaml")
            "pyroscope" -> PyroscopeManifestBuilder(templates(), stub).buildServerConfigMap().data.getValue("config.yaml")
            "compactor tempo-backend" -> CompactorTaskDefinition.configFiles(stub).getValue(CompactorTaskDefinition.TEMPO_BACKEND_FILE)
            else -> error("unknown config $name")
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renderedConfigs")
    fun `the stub block stands where the S3 block stood`(
        name: String,
        blockPath: List<String>,
    ) {
        val config = render(name)

        assertThat(YamlTestSupport.scalarAt(config, *(blockPath + "backend").toTypedArray())).isEqualTo("stub")
        assertThat(YamlTestSupport.scalarAt(config, *(blockPath + listOf("stub", "marker")).toTypedArray())).isEqualTo("stub-block")
        assertThat(YamlTestSupport.nodeAt(config, *(blockPath + "s3").toTypedArray())).isNull()
    }

    /** The names of every backend config. */
    fun configNames(): List<String> = renderedConfigs().map { it.get()[0] as String }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configNames")
    fun `no S3 is left outside the fragment`(name: String) {
        val config = render(name)

        assertThat(config).doesNotContain("s3://", "amazonaws.com", "backend: s3", "STORE_ENDPOINT")
        assertThat(config).doesNotContainPattern("""endpoint:\s*s3\.""")
    }

    @Test
    fun `the compactor writes the same rendered configs it is given`() {
        val files = CompactorTaskDefinition.configFiles(stub)

        assertThat(
            files.keys,
        ).containsExactly(MimirManifestBuilder.CONFIG_FILE, LokiManifestBuilder.CONFIG_FILE, CompactorTaskDefinition.TEMPO_BACKEND_FILE)
        assertThat(files.values).allSatisfy { assertThat(it).contains("stub-block") }
    }
}
