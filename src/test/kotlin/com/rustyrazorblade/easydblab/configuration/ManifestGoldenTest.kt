package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes
import com.rustyrazorblade.easydblab.services.TemplateService
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.utils.Serialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the rendered YAML of every workload manifest the control-plane and node-agent builders
 * produce, so a refactor of the shared pod placement and Deployment skeleton cannot change a
 * single byte of what is applied to a cluster.
 *
 * The expected files live in `src/test/resources/golden/manifests/`. Set `UPDATE_GOLDEN=1` to
 * rewrite them after an intended manifest change, then review the diff.
 */
class ManifestGoldenTest : BaseKoinTest() {
    private companion object {
        const val GOLDEN_DIR = "golden/manifests"
    }

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single {
                    mock<ClusterStateManager>().also {
                        whenever(
                            it.load(),
                        ).thenReturn(
                            ClusterState(name = "test", clusterId = "golden-cluster", s3Bucket = "acct"),
                        )
                    }
                }
                single { TemplateService(get(), get()) }
            },
        )

    private fun render(resources: List<HasMetadata>): String = resources.joinToString("---\n") { Serialization.asYaml(it) }

    private fun manifests(): Map<String, List<HasMetadata>> = AllBuilderManifests.render(getKoin().get(), TestScopes.EC2)

    @Test
    fun `every workload manifest renders byte-identical to its golden file`() {
        val update = System.getenv("UPDATE_GOLDEN") == "1"
        val actual = manifests().mapValues { (_, resources) -> render(resources) }
        if (update) {
            val dir = Path.of("src/test/resources", GOLDEN_DIR)
            Files.createDirectories(dir)
            actual.forEach { (name, yaml) -> Files.writeString(dir.resolve("$name.yaml"), yaml) }
        }
        if (update) return
        val differing =
            actual.filter { (name, yaml) ->
                val golden = checkNotNull(javaClass.classLoader.getResource("$GOLDEN_DIR/$name.yaml")) { "missing golden file for $name" }
                yaml != golden.readText()
            }
        assertThat(differing.keys).describedAs("manifests that differ from their golden file").isEmpty()
    }
}
