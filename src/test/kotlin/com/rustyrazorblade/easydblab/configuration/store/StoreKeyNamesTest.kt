package com.rustyrazorblade.easydblab.configuration.store

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `cluster-config` keys and the backend env names are neutral (design D5): no source, resource
 * or doc still names the S3-specific ones (`aws_region`, `s3_bucket`, `*_s3_prefix`, the
 * `${S3_BUCKET}`-style runtime names, or Pyroscope's `__PROFILES_S3_PREFIX__`).
 *
 * A key name is a string, which no compiler or ArchUnit rule sees, so this reads the files. Each
 * exemption names the one line it allows and why.
 */
class StoreKeyNamesTest {
    private val roots = listOf(File("src/main"), File("docs"))
    private val oldNames =
        Regex("""\b(aws_region|s3_bucket|[a-z]+_s3_prefix)\b|\$\{(S3_BUCKET|AWS_REGION|[A-Z]+_S3_PREFIX)\}|__PROFILES_S3_PREFIX__""")
    private val textExtensions = setOf("kt", "yaml", "yml", "md", "sh", "conf", "json")

    /** File path to the trimmed text of the one line it may hold. */
    private val exemptions =
        mapOf(
            // A Packer build variable, not a cluster-config key; the AMI templates declare it.
            "src/main/kotlin/com/rustyrazorblade/easydblab/containers/Packer.kt" to "addVar(\"s3_bucket\", user.s3Bucket)",
        )

    @Test
    fun `no source, resource or doc names an S3-specific cluster-config key or backend env name`() {
        val offending =
            roots
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in textExtensions }.toList() }
                .flatMap { file ->
                    val path = file.invariantSeparatorsPath
                    file
                        .readLines()
                        .mapIndexed { index, line -> Triple(path, index + 1, line.trim()) }
                        .filter { (_, _, line) -> oldNames.containsMatchIn(line) && exemptions[path] != line }
                }.map { (path, number, line) -> "$path:$number: $line" }

        assertThat(offending).isEmpty()
    }

    @Test
    fun `the search sees the sources and docs and its exemptions still exist`() {
        assertThat(File("docs/reference/pyroscope-configuration.md")).exists()
        exemptions.forEach { (path, line) ->
            assertThat(File(path).readLines().map { it.trim() }).contains(line)
        }
    }
}
