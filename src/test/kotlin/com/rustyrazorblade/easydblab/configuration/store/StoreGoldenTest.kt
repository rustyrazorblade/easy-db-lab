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
import io.fabric8.kubernetes.api.model.apps.Deployment
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
 * compactor task definition and every IAM/bucket policy document must stay identical. The compactor
 * task's env names that design D5 renamed are mapped back to their task-0 names before the compare,
 * so its golden file stays the task-0 capture.
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

        /**
         * The compactor env names design D5 renamed, new name to the name in the task-0 capture. The
         * compactor golden is that capture, so the test maps these back instead of recapturing it.
         */
        val D5_ENV_RENAMES =
            mapOf(
                "STORE_BUCKET" to "S3_BUCKET",
                "STORE_REGION" to "AWS_REGION",
                "METRICS_STORE_PREFIX" to "METRICS_S3_PREFIX",
                "LOGS_STORE_PREFIX" to "LOGS_S3_PREFIX",
                "TRACES_STORE_PREFIX" to "TRACES_S3_PREFIX",
            )

        /** The env D5 added to the compactor task, with the value it must carry on AWS. */
        val D5_ENV_ADDED = mapOf("STORE_ENDPOINT" to "s3.$REGION.amazonaws.com")

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

    /**
     * The values a backend pod sees: every env var of [deployment]'s containers that reads the
     * `cluster-config` ConfigMap, resolved against [clusterConfig] exactly as Kubernetes would. A key
     * the ConfigMap does not publish fails here, so a mis-keyed or dropped env wiring cannot hide
     * behind a hand-written expansion map.
     */
    private fun podEnv(deployment: Deployment): Map<String, String> =
        deployment.spec.template.spec.containers
            .flatMap { it.env.orEmpty() }
            .mapNotNull { env ->
                env.valueFrom?.configMapKeyRef?.takeIf { it.name == ClusterConfigData.NAME }?.let { ref ->
                    val value = clusterConfig[ref.key]
                    checkNotNull(value) { "${env.name} reads cluster-config key '${ref.key}', which it does not publish" }
                    env.name to value
                }
            }.toMap()

    @Test
    fun `mimir config expands to the golden file`() {
        val builder = MimirManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        assertGolden("mimir.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
    }

    @Test
    fun `loki config expands to the golden file`() {
        val builder = LokiManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        assertGolden("loki.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
    }

    @Test
    fun `tempo config expands to the golden file`() {
        val builder = TempoManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue("tempo.yaml")
        assertGolden("tempo.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
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
        assertGolden("compactor-task-policy.json", AWSPolicy.Inline.CompactorTaskAccess(storePrefix = "").toJson())
    }

    /**
     * A container's [env] as the task-0 capture names it: each [D5_ENV_RENAMES] name mapped back to
     * its old one, and each [D5_ENV_ADDED] entry checked for its value and then dropped. Any other
     * difference in the env, a missing renamed name included, still fails the golden comparison.
     */
    private fun baselineEnv(env: Map<String, String>): Map<String, String> {
        D5_ENV_ADDED.forEach { (name, value) -> assertThat(env).containsEntry(name, value) }
        return env
            .filterKeys { it !in D5_ENV_ADDED }
            .mapKeys { (name, _) -> D5_ENV_RENAMES[name] ?: name }
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
                                            baselineEnv(c.environment().associate { it.name() to it.value() })
                                                .toSortedMap()
                                                .forEach { (name, value) -> put(name, value) }
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
