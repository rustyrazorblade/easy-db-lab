package com.rustyrazorblade.easydblab.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No main Kotlin source names the `default` namespace except `ContractDefaults.OWNED_NAMESPACE`
 * (issue 1094, design "Test strategy"): every namespace comes from the cluster's `InstallScope`, so a
 * cluster installed into another namespace never reaches `default` by a forgotten literal.
 *
 * The search reads the sources, not bytecode, because a namespace is a string. Only namespace-shaped
 * spellings count, so `default` meaning something else (the default ServiceAccount, a kit
 * argument's `default:`, the AWS profile, the tenant) is not reported. Comments are left out.
 */
class NoDefaultNamespaceLiteralTest {
    private val mainRoot = File("src/main/kotlin/com/rustyrazorblade/easydblab")
    private val fixture = File("src/test/kotlin/com/rustyrazorblade/easydblab/architecture/fixtures/DefaultNamespaceLiterals.kt")

    private val namespaceSpellings =
        listOf(
            """namespace: default\b""",
            """-n default\b""",
            """--namespace[ =]default\b""",
            """\.default\.svc""",
            """namespaces/default/""",
            """\{default\}""",
            """withNamespace\("default"\)""",
            """inNamespace\("default"\)""",
            """NAMESPACE = "default"""",
            """namespace: String = "default"""",
        ).map(::Regex)

    private val resourceRoots = listOf(File("src/main/resources"), File("dashboards"))
    private val binaryExtensions = setOf("jar", "png", "jpg", "gz", "zip", "tgz")

    /** File path (relative to [mainRoot]) to the text of the one line it may hold. */
    private val allowed = mapOf("Constants.kt" to "const val OWNED_NAMESPACE = \"default\"")

    @Test
    fun `no main source names the default namespace outside ContractDefaults`() {
        val offending =
            mainRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    hits(file).map { (number, line) -> Triple(file.relativeTo(mainRoot).invariantSeparatorsPath, number, line) }
                }.filterNot { (path, _, line) -> allowed[path] == line }
                .map { (path, number, line) -> "$path:$number: $line" }
                .toList()

        assertThat(offending).isEmpty()
    }

    /**
     * Kit files, the shell library, help text and the dashboards reach a cluster too, as manifests,
     * scripts and text a user copies, so none of them may name `default` either; a kit acts in
     * `${NAMESPACE}` / `__NAMESPACE__`. Every line counts, comments included: a comment there is
     * what a user runs by hand. Dashboards live in the top-level tree the build copies to the
     * classpath.
     */
    @Test
    fun `no packaged resource or dashboard names the default namespace`() {
        val offending =
            resourceRoots
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension !in binaryExtensions && it.name != "CLAUDE.md" }
                        .flatMap { file ->
                            file.readLines().mapIndexedNotNull { index, line ->
                                "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                                    .takeIf { namespaceSpellings.any { it.containsMatchIn(line) } }
                            }
                        }.toList()
                }

        assertThat(resourceRoots).allMatch { it.isDirectory }
        assertThat(offending).isEmpty()
    }

    @Test
    fun `the allowlisted line still exists, so the allowlist cannot hide a moved literal`() {
        allowed.forEach { (path, line) -> assertThat(hits(mainRoot.resolve(path)).map { it.second }).contains(line) }
    }

    @Test
    fun `the guard reports each namespace spelling in the fixture and none of its other uses of default`() {
        val reported = hits(fixture).map { it.second }

        assertThat(reported).containsExactlyInAnyOrder(
            "const val KUBECTL = \"kubectl get pods -n default\"",
            "const val HELM = \"helm install x --namespace default\"",
            "const val HELM_EQUALS = \"helm install x --namespace=default\"",
            "const val SERVICE_HOST = \"mimir.default.svc.cluster.local\"",
            "const val API_PATH = \"/api/v1/namespaces/default/pods\"",
            "const val YAML = \"namespace: default\"",
            "const val NAMESPACE = \"default\"",
            "fun withDefault(namespace: String = \"default\"): String = namespace",
        )
    }

    /** The trimmed non-comment lines of [file] that spell the `default` namespace, with their numbers. */
    private fun hits(file: File): List<Pair<Int, String>> =
        file
            .readLines()
            .mapIndexed { index, line -> index + 1 to line.trim() }
            .filterNot { (_, line) -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") }
            .filter { (_, line) -> namespaceSpellings.any { it.containsMatchIn(line) } }
}
