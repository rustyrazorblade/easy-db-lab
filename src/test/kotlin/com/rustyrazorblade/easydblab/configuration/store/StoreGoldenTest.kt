package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.services.ClusterConfigData
import com.rustyrazorblade.easydblab.services.TemplateService
import com.rustyrazorblade.easydblab.services.aws.CompactorTaskDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest
import java.io.File
import java.util.Base64

/**
 * Golden tests that lock the AWS-rendered store configuration byte-for-byte.
 *
 * Task 0 of issue-1061 captures these from the unchanged code, before the neutral-store refactor.
 * Every later task renders the same artifacts through the refactored code with `S3Backend(bucket,
 * "", region)` and compares against the committed golden files: on AWS, every backend config, the
 * compactor task definition and every IAM/bucket policy document must stay identical.
 *
 * A deliberate, intended change to a backend config must update its golden file in the same change
 * (see configuration/CLAUDE.md).
 */
class StoreGoldenTest : BaseKoinTest() {
    private companion object {
        const val BUCKET = "easy-db-lab-test"
        const val REGION = "us-west-2"
        const val TENANT = "acme"
        const val ACCOUNT_ID = "123456789012"
        const val GOLDEN_DIR = "src/test/resources/com/rustyrazorblade/easydblab/golden/store"

        val control = ClusterHost("54.0.0.1", "10.0.0.5", "control0", "us-west-2a")
        val state =
            ClusterState(
                name = "lab",
                versions = mutableMapOf(),
                clusterId = "golden",
                s3Bucket = BUCKET,
                initConfig = InitConfig(tenant = TENANT, region = REGION),
                hosts = mapOf(ServerType.Control to listOf(control)),
            )
    }

    private lateinit var templateService: TemplateService
    private val prettyJson = Json { prettyPrint = true }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) }
                }
                single { TemplateService(get(), get()) }
            },
        )

    @BeforeEach
    fun setup() {
        templateService = getKoin().get()
    }

    private val clusterConfig: Map<String, String> by lazy { ClusterConfigData.of(control, state, REGION) }

    /**
     * Reads the committed golden file. With `UPDATE_GOLDEN=true` in the environment it first rewrites the file from
     * [actual]; that is the only way a golden changes, so a missing file fails rather than being
     * captured silently.
     */
    private fun goldenOrCapture(
        name: String,
        actual: String,
    ): String {
        val file = File(GOLDEN_DIR, name)
        if (System.getenv("UPDATE_GOLDEN") == "true") {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        check(file.exists()) { "Missing golden file ${file.path}; capture it with UPDATE_GOLDEN=true" }
        return file.readText()
    }

    private fun assertGolden(
        name: String,
        actual: String,
    ) {
        assertThat(actual).isEqualTo(goldenOrCapture(name, actual))
    }

    @Test
    fun `mimir config expands to the golden file`() {
        val builder = MimirManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        val expanded =
            ExpandEnv.expand(
                config,
                mapOf(
                    "AWS_REGION" to clusterConfig.getValue("aws_region"),
                    "S3_BUCKET" to clusterConfig.getValue("s3_bucket"),
                    "METRICS_S3_PREFIX" to clusterConfig.getValue("metrics_s3_prefix"),
                ),
            )
        assertGolden("mimir.yaml", expanded)
    }

    @Test
    fun `loki config expands to the golden file`() {
        val builder = LokiManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        val expanded =
            ExpandEnv.expand(
                config,
                mapOf(
                    "AWS_REGION" to clusterConfig.getValue("aws_region"),
                    "S3_BUCKET" to clusterConfig.getValue("s3_bucket"),
                    "LOGS_S3_PREFIX" to clusterConfig.getValue("logs_s3_prefix"),
                    "TENANT" to clusterConfig.getValue("tenant"),
                    "CLUSTER_NAME" to clusterConfig.getValue("cluster_name"),
                ),
            )
        assertGolden("loki.yaml", expanded)
    }

    @Test
    fun `tempo config expands to the golden file`() {
        val builder = TempoManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue("tempo.yaml")
        val expanded =
            ExpandEnv.expand(
                config,
                mapOf(
                    "AWS_REGION" to clusterConfig.getValue("aws_region"),
                    "S3_BUCKET" to clusterConfig.getValue("s3_bucket"),
                    "TRACES_S3_PREFIX" to clusterConfig.getValue("traces_s3_prefix"),
                    "CLUSTER_NAME" to clusterConfig.getValue("cluster_name"),
                ),
            )
        assertGolden("tempo.yaml", expanded)
    }

    @Test
    fun `pyroscope config renders to the golden file`() {
        val builder = PyroscopeManifestBuilder(templateService)
        val config = builder.buildServerConfigMap().data.getValue("config.yaml")
        assertGolden("pyroscope.yaml", config)
    }

    @Test
    fun `compactor task definition renders to the golden file`() {
        val definition =
            CompactorTaskDefinition(
                bucket = BUCKET,
                region = REGION,
                taskRoleArn = "arn:aws:iam::$ACCOUNT_ID:role/EasyDBLabCompactorTaskRole",
                executionRoleArn = "arn:aws:iam::$ACCOUNT_ID:role/EasyDBLabCompactorExecutionRole",
            )
        assertGolden(
            "compactor-task-definition.json",
            prettyJson.encodeToString(JsonObject.serializer(), compactorJson(definition.request())),
        )
    }

    @Test
    fun `instance role policy renders to the golden file`() {
        assertGolden("instance-role-policy.json", AWSPolicy.Inline.S3AccessWildcard(ACCOUNT_ID).toJson())
    }

    @Test
    fun `bucket policy renders to the golden file`() {
        assertGolden("bucket-policy.json", AWSPolicy.Inline.S3BucketPolicy(ACCOUNT_ID, BUCKET).toJson())
    }

    @Test
    fun `compactor task role policy renders to the golden file`() {
        assertGolden("compactor-task-policy.json", AWSPolicy.Inline.CompactorTaskAccess.toJson())
    }

    /**
     * A readable, refactor-stable view of the compactor task: the task shape, each container's
     * command, user and environment, and the config container's files decoded and expanded against
     * the task environment.
     */
    private fun compactorJson(request: RegisterTaskDefinitionRequest): JsonObject {
        val containers = request.containerDefinitions().associateBy { it.name() }
        val configContainer = containers.getValue(CompactorTaskDefinition.CONFIG_CONTAINER)
        val script = configContainer.command().last()
        val taskEnv =
            containers.getValue(CompactorTaskDefinition.MIMIR_CONTAINER).environment().associate { it.name() to it.value() }
        val fileToVar =
            Regex("""echo "\$(CONFIG_\d+)" \| base64 -d > /config/(\S+)""")
                .findAll(script)
                .associate { it.groupValues[2] to it.groupValues[1] }
        val configEnv = configContainer.environment().associate { it.name() to it.value() }
        val writtenConfigs =
            buildJsonObject {
                fileToVar.toSortedMap().forEach { (file, variable) ->
                    val raw = String(Base64.getDecoder().decode(configEnv.getValue(variable)))
                    put(file, ExpandEnv.expand(raw, taskEnv))
                }
            }

        return buildJsonObject {
            put("family", request.family())
            put("cpu", request.cpu())
            put("memory", request.memory())
            put("networkMode", request.networkModeAsString())
            put("taskRoleArn", request.taskRoleArn())
            put("executionRoleArn", request.executionRoleArn())
            put(
                "containers",
                buildJsonArray {
                    request.containerDefinitions().forEach { c ->
                        add(
                            buildJsonObject {
                                put("name", c.name())
                                put("image", c.image())
                                put("essential", c.essential())
                                c.user()?.let { put("user", it) }
                                put("command", JsonArray(c.command().map { JsonPrimitive(it) }))
                                put(
                                    "environment",
                                    buildJsonObject {
                                        if (c.name() == CompactorTaskDefinition.CONFIG_CONTAINER) {
                                            put("writtenConfigs", writtenConfigs)
                                        } else {
                                            c.environment().sortedBy { it.name() }.forEach { put(it.name(), it.value()) }
                                        }
                                    },
                                )
                                put(
                                    "mountPoints",
                                    buildJsonArray {
                                        c.mountPoints().forEach { m ->
                                            add(
                                                buildJsonObject {
                                                    put("sourceVolume", m.sourceVolume())
                                                    put("containerPath", m.containerPath())
                                                    put("readOnly", m.readOnly() ?: false)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}
