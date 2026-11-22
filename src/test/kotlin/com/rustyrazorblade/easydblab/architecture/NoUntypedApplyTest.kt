package com.rustyrazorblade.easydblab.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No main source applies Kubernetes objects through the untyped paths (`applyYaml`, `applyManifests`,
 * `applyManifestFromResources`). `OwnedManifestOperations` labels only the typed `applyResource`, so
 * an object sent through an untyped path would reach the cluster without the ownership labels and a
 * sweep by cluster would miss it (issue 1094, design D6). The two implementation files, which
 * define those paths, are the only places allowed to name them.
 */
class NoUntypedApplyTest {
    private val mainRoot = File("src/main/kotlin/com/rustyrazorblade/easydblab")
    private val fixture = File("src/test/kotlin/com/rustyrazorblade/easydblab/architecture/fixtures/UntypedApplies.kt")

    private val untypedApply = Regex("""\.(applyYaml|applyManifests|applyManifestFromResources)\(""")

    /** Where the untyped paths are implemented, on top of `ManifestApplier`. */
    private val implementations = setOf("services/DefaultK8sManifestOperations.kt", "services/DefaultK8sJobOperations.kt")

    @Test
    fun `no main source applies objects through an untyped path`() {
        val offending =
            mainRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.relativeTo(mainRoot).invariantSeparatorsPath in implementations }
                .flatMap { file ->
                    val path = file.relativeTo(mainRoot).invariantSeparatorsPath
                    hits(file).map { (number, line) -> "$path:$number: $line" }
                }.toList()

        assertThat(offending).isEmpty()
    }

    @Test
    fun `the guard reports each untyped apply in the fixture`() {
        assertThat(hits(fixture).map { it.second }).containsExactly(
            "manifests.applyYaml(\"kind: ConfigMap\")",
            "manifests.applyManifests(Path.of(\"manifests\"))",
            "manifests.applyManifestFromResources(\"k8s/thing.yaml\")",
        )
    }

    /** The trimmed non-comment lines of [file] that call an untyped apply, with their numbers. */
    private fun hits(file: File): List<Pair<Int, String>> =
        file
            .readLines()
            .mapIndexed { index, line -> index + 1 to line.trim() }
            .filterNot { (_, line) -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") }
            .filter { (_, line) -> untypedApply.containsMatchIn(line) }
}
