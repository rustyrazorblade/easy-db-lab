package com.rustyrazorblade.easydblab.configuration.store

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `cluster-config` keys and the backend env names are neutral (design D5): no source, test,
 * resource or doc still names the S3-specific ones (`aws_region`, `s3_bucket`, `*_s3_prefix`, the
 * `${S3_BUCKET}`-style runtime names or the same names quoted as an env var, or Pyroscope's
 * `__PROFILES_S3_PREFIX__`).
 *
 * A key name is a string, which no compiler or ArchUnit rule sees, so this reads the files. A test
 * fixture that still used an old key would build a `cluster-config` no workload reads, so the test
 * sources are searched too. Each exemption names the lines it allows and why.
 */
class StoreKeyNamesTest {
    private val roots = listOf(File("src/main"), File("src/test"), File("src/integrationTest"), File("docs"))
    private val oldNames =
        Regex(
            """\b(aws_region|s3_bucket|[a-z]+_s3_prefix)\b|\$\{(S3_BUCKET|AWS_REGION|[A-Z]+_S3_PREFIX)\}|""" +
                """"(S3_BUCKET|(METRICS|LOGS|TRACES|PROFILES)_S3_PREFIX)"|__PROFILES_S3_PREFIX__""",
        )
    private val textExtensions = setOf("kt", "yaml", "yml", "md", "sh", "conf", "json")
    private val testSources = "src/test/kotlin/com/rustyrazorblade/easydblab"

    /** Files that name the old names throughout, by design. */
    private val exemptFiles =
        setOf(
            // This test names the old names to find them.
            "$testSources/configuration/store/StoreKeyNamesTest.kt",
            // The golden captured before the rename; StoreGoldenTest maps the new names back to these.
            "src/test/resources/com/rustyrazorblade/easydblab/golden/store/compactor-task-definition.json",
        )

    /** File path to the trimmed text of the lines it may hold. */
    private val exemptions =
        mapOf(
            // A Packer build variable, not a cluster-config key; the AMI templates declare it.
            "src/main/kotlin/com/rustyrazorblade/easydblab/containers/Packer.kt" to setOf("addVar(\"s3_bucket\", user.s3Bucket)"),
            // Assertions that the old names are gone.
            "$testSources/services/ClusterConfigDataTest.kt" to
                setOf(
                    ".doesNotContainKeys(\"aws_region\", \"s3_bucket\", \"metrics_s3_prefix\", \"logs_s3_prefix\", \"traces_s3_prefix\")",
                ),
            "$testSources/services/ObservabilityStackServiceTest.kt" to
                setOf("assertThat(data.firstValue).doesNotContainKey(\"cluster_s3_prefix\")"),
            "$testSources/services/TemplateServiceTest.kt" to
                setOf("assertThat(variables).doesNotContainKeys(\"PYROSCOPE_STORAGE_PREFIX\", \"PROFILES_S3_PREFIX\")"),
            "$testSources/services/aws/CompactorTaskDefinitionTest.kt" to
                setOf(
                    ".doesNotContainKeys(\"S3_BUCKET\", \"AWS_REGION\", \"METRICS_S3_PREFIX\", \"LOGS_S3_PREFIX\", \"TRACES_S3_PREFIX\")",
                ),
            // The D5 rename, mapped back to compare with the golden captured before it.
            "$testSources/configuration/store/StoreGoldenTest.kt" to
                setOf(
                    "\"STORE_BUCKET\" to \"S3_BUCKET\",",
                    "\"METRICS_STORE_PREFIX\" to \"METRICS_S3_PREFIX\",",
                    "\"LOGS_STORE_PREFIX\" to \"LOGS_S3_PREFIX\",",
                    "\"TRACES_STORE_PREFIX\" to \"TRACES_S3_PREFIX\",",
                ),
        )

    @Test
    fun `no source, test, resource or doc names an S3-specific cluster-config key or backend env name`() {
        val offending =
            roots
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in textExtensions }.toList() }
                .filter { it.invariantSeparatorsPath !in exemptFiles }
                .flatMap { file ->
                    val path = file.invariantSeparatorsPath
                    file
                        .readLines()
                        .mapIndexed { index, line -> Triple(path, index + 1, line.trim()) }
                        .filter { (_, _, line) -> oldNames.containsMatchIn(line) && line !in exemptions[path].orEmpty() }
                }.map { (path, number, line) -> "$path:$number: $line" }

        assertThat(offending).isEmpty()
    }

    @Test
    fun `the search sees the sources, tests and docs and its exemptions still exist`() {
        assertThat(File("docs/reference/pyroscope-configuration.md")).exists()
        assertThat(File("src/integrationTest")).isDirectory()
        exemptFiles.forEach { assertThat(File(it)).exists() }
        exemptions.forEach { (path, lines) ->
            assertThat(File(path).readLines().map { it.trim() }).containsAll(lines)
        }
    }
}
