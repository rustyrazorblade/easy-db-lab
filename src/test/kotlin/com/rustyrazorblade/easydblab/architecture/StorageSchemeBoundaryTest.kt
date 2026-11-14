package com.rustyrazorblade.easydblab.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No main class outside the S3 adapter packages renders or parses an `s3://` URI or an
 * `amazonaws.com` host (object-store spec, "No scheme outside the S3 adapter").
 *
 * The search reads the Kotlin sources, not bytecode, because a scheme or a host is a string, which
 * ArchUnit cannot see. Comments are left out: a KDoc may say what a location looks like on AWS.
 * Each exemption names the one line it allows and why.
 */
class StorageSchemeBoundaryTest {
    private val mainRoot = File("src/main/kotlin/com/rustyrazorblade/easydblab")
    private val adapters = listOf("services/aws/", "providers/aws/")
    private val s3Spelling = Regex("""s3://|amazonaws\.com""")

    /** File path (relative to [mainRoot]) to the text of the one line it may hold. */
    private val exemptions =
        mapOf(
            // Design D3: the S3 case of the neutral store model holds the AWS endpoint as its default.
            "configuration/store/StoreBackend.kt" to "endpoint: String = \"s3.\$region.amazonaws.com\",",
            // Help text naming the form `spark submit` accepts; S3Uri parses it.
            "commands/spark/SparkSubmit.kt" to "description = [\"Path to JAR file (local path or s3://bucket/key)\"],",
        )

    @Test
    fun `no main source outside the S3 adapters spells an s3 URI or an AWS host`() {
        val offending =
            mainRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(mainRoot).invariantSeparatorsPath to it }
                .filterNot { (path, _) -> adapters.any { path.startsWith(it) } }
                .flatMap { (path, file) -> codeLines(file).map { (number, line) -> Triple(path, number, line) } }
                .filter { (_, _, line) -> s3Spelling.containsMatchIn(line) }
                .filterNot { (path, _, line) -> exemptions[path] == line }
                .map { (path, number, line) -> "$path:$number: $line" }
                .toList()

        assertThat(offending).isEmpty()
    }

    @Test
    fun `the search sees the source tree and its exemptions still exist`() {
        assertThat(mainRoot.resolve("configuration/store/StoreBackend.kt")).exists()
        exemptions.forEach { (path, line) ->
            assertThat(codeLines(mainRoot.resolve(path)).map { it.second }).contains(line)
        }
    }

    /** The trimmed non-comment lines of [file], with their line numbers. */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file
            .readLines()
            .mapIndexed { index, line -> index + 1 to line.trim() }
            .filterNot { (_, line) -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") }
}
