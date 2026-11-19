package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.InitConfig
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorAgentJar
import com.rustyrazorblade.easydblab.configuration.compactor.CompactorManifestBuilder
import com.rustyrazorblade.easydblab.configuration.loki.LokiManifestBuilder
import com.rustyrazorblade.easydblab.configuration.mimir.MimirManifestBuilder
import com.rustyrazorblade.easydblab.configuration.pyroscope.PyroscopeManifestBuilder
import com.rustyrazorblade.easydblab.configuration.tempo.TempoManifestBuilder
import com.rustyrazorblade.easydblab.providers.aws.AWSPolicy
import com.rustyrazorblade.easydblab.services.ClusterConfigData
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.apps.Deployment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Golden tests that lock the AWS-rendered store configuration byte-for-byte.
 *
 * Task 0 of issue-1061 captures these from the unchanged code, before the neutral-store refactor.
 * Every later task renders the same artifacts through the refactored code with `S3Backend(bucket,
 * "", region)` and compares against the committed golden files: on AWS, every backend config, the
 * account compactor's configs as its containers expand them, and every IAM/bucket policy document
 * must stay identical. The Fargate compactor task of the task-0 capture is gone (issue 1071): the
 * compactor now runs in each cluster, and its golden is the expanded configs it reads.
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

        /** The golden files, under the project directory Gradle passes in, not the JVM's working directory. */
        val GOLDEN_DIR =
            File(
                checkNotNull(System.getProperty("easydblab.projectDir")) { "easydblab.projectDir is not set; run the test through Gradle" },
                "src/test/resources/com/rustyrazorblade/easydblab/golden/store",
            )

        /** The length of the SHA-256 that names the lease agent jar. */
        const val SHA256_HEX_DIGITS = 64

        val control = ClusterHost("54.0.0.1", "10.0.0.5", "control0", "us-west-2a")
        val state =
            ClusterState(
                name = "lab",
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

    private val clusterConfig: Map<String, String> by lazy { ClusterConfigData.of(state, REGION) }

    /**
     * Reads the committed golden file. With `UPDATE_GOLDEN=true` in the environment it first rewrites the file from
     * [actual]; that is the only way a golden changes, so a missing file fails rather than being
     * captured silently. A rewrite in CI is refused ([GoldenUpdate]).
     */
    private fun goldenOrCapture(
        name: String,
        actual: String,
    ): String {
        val file = File(GOLDEN_DIR, name)
        if (GoldenUpdate.requested(System.getenv())) {
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
     * [assertGolden] for a backend config as its pod reads it, every variable expanded. A `${` left in it is a
     * variable no env supplied, which the golden would otherwise capture as if it were intended.
     */
    private fun assertExpandedGolden(
        name: String,
        actual: String,
    ) {
        assertThat(actual).describedAs("unexpanded variable in $name").doesNotContain("\${")
        assertGolden(name, actual)
    }

    /** [assertGolden] for a policy document, pretty-printed so a change shows as a readable diff. */
    private fun assertPolicyGolden(
        name: String,
        policy: String,
    ) = assertGolden(name, prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(policy)))

    /**
     * The values a backend pod sees: every env var of [deployment]'s containers that reads the
     * `cluster-config` ConfigMap, resolved against [clusterConfig] exactly as Kubernetes would. A key
     * the ConfigMap does not publish fails here, so a mis-keyed or dropped env wiring cannot hide
     * behind a hand-written expansion map.
     */
    private fun podEnv(deployment: Deployment): Map<String, String> =
        deployment.spec.template.spec.containers
            .flatMap { it.env.orEmpty() }
            .mapNotNull { env -> clusterConfigValue(env)?.let { env.name to it } }
            .toMap()

    /** [env]'s value when it reads the `cluster-config` ConfigMap; fails on a key the ConfigMap does not publish. */
    private fun clusterConfigValue(env: EnvVar): String? =
        env.valueFrom?.configMapKeyRef?.takeIf { it.name == ClusterConfigData.NAME }?.let { ref ->
            checkNotNull(clusterConfig[ref.key]) { "${env.name} reads cluster-config key '${ref.key}', which it does not publish" }
        }

    /** [container]'s env as the pod sees it: literal values, and `cluster-config` keys resolved. */
    private fun containerEnv(container: Container): Map<String, String> =
        container.env
            .orEmpty()
            .mapNotNull { env -> (env.value ?: clusterConfigValue(env))?.let { env.name to it } }
            .toMap()

    @Test
    fun `mimir config expands to the golden file`() {
        val builder = MimirManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(MimirManifestBuilder.CONFIG_FILE)
        assertExpandedGolden("mimir.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
    }

    @Test
    fun `loki config expands to the golden file`() {
        val builder = LokiManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue(LokiManifestBuilder.CONFIG_FILE)
        assertExpandedGolden("loki.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
    }

    @Test
    fun `tempo config expands to the golden file`() {
        val builder = TempoManifestBuilder(templateService)
        val config = builder.buildConfigMap().data.getValue("tempo.yaml")
        assertExpandedGolden("tempo.yaml", ExpandEnv.expand(config, podEnv(builder.buildDeployment())))
    }

    @Test
    fun `pyroscope config renders to the golden file`() {
        val builder = PyroscopeManifestBuilder(templateService)
        val config = builder.buildServerConfigMap().data.getValue("config.yaml")
        assertExpandedGolden("pyroscope.yaml", config)
    }

    /**
     * The account compactor's configs as each compactor reads them: mimir.yaml, loki.yaml and
     * tempo-backend.yaml from its ConfigMap, each expanded against the env of the container that
     * reads it, so the store block, the `store_bucket` bucket and the `*_store_prefix` roots all
     * resolve exactly as in the pod.
     */
    @Test
    fun `compactor configs expand to the golden file`() {
        val builder = CompactorManifestBuilder(templateService)
        val configs = builder.buildConfigMap().data
        val containers =
            builder
                .buildDeployment(CompactorAgentJar("0".repeat(SHA256_HEX_DIGITS)), S3Backend(bucket = BUCKET, region = REGION))
                .spec.template.spec.containers
                .associateBy { it.name }
        val readers =
            mapOf(
                MimirManifestBuilder.CONFIG_FILE to CompactorManifestBuilder.MIMIR_CONTAINER,
                LokiManifestBuilder.CONFIG_FILE to CompactorManifestBuilder.LOKI_CONTAINER,
                CompactorManifestBuilder.TEMPO_BACKEND_FILE to CompactorManifestBuilder.TEMPO_WORKER_CONTAINER,
            )
        val expanded =
            readers.entries.joinToString("\n") { (file, container) ->
                "# ==== $file ($container)\n" + ExpandEnv.expand(configs.getValue(file), containerEnv(containers.getValue(container)))
            }
        assertExpandedGolden("compactor-configs.yaml", expanded)
    }

    @Test
    fun `instance role policy renders to the golden file`() {
        assertPolicyGolden("instance-role-policy.json", AWSPolicy.Inline.S3AccessWildcard(ACCOUNT_ID).toJson())
    }

    @Test
    fun `bucket policy renders to the golden file`() {
        assertPolicyGolden("bucket-policy.json", AWSPolicy.Inline.S3BucketPolicy(ACCOUNT_ID, BUCKET).toJson())
    }
}
