package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.configuration.NodeCapability
import com.rustyrazorblade.easydblab.services.KitConfig
import com.rustyrazorblade.easydblab.services.installConfigYaml
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * A built-in kit must run on any Kubernetes cluster, not only on the nodes easy-db-lab builds.
 * Each pattern here is a dependency on those nodes that broke a kit elsewhere: a host path or
 * port, the AMI's agent directories, a hard-coded `type=<role>` node label instead of the role
 * variables, or the AWS-only `10.0.0.0/8` client network.
 *
 * The scan covers every packaged kit file a pod or script reads (manifest templates, scripts,
 * SQL, `extensions.yaml`) and the shared kit shell library. `kit.yaml` is left out, since its
 * `type:` and `node-type:` keys name a role for the CLI, not a node label, and so are markdown
 * and dashboards. Comment lines are skipped, so a comment may say why a kit no longer uses one.
 *
 * A kit that cannot work without one of these declares the capability in its `kit.yaml`
 * `requires` (cqlite-flight reads the database's data directory through `hostPath`, so it requires
 * `host-path`), and `kit install` and `start` refuse it where the cluster lacks that capability.
 * Only that kit's files may then use the pattern the capability names ([ALLOWED_BY]).
 */
class BuiltinKitHostDependencyTest {
    @Test
    fun `no packaged kit file or the kit shell library depends on the nodes easy-db-lab builds`() {
        val files = scannedFiles()
        val findings =
            files.flatMap { file ->
                val declared = declaredCapabilities(file)
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    hostDependency(line, declared)?.let { "${file.relativeTo(file.kitsRoot())}:${index + 1}: $it: ${line.trim()}" }
                }
            }

        assertThat(files.map { it.name }).contains("edl-kit-lib.sh", "statefulset.yaml.template", "start.sh.template")
        assertThat(findings).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "      hostPath:",
            "        hostPort: 8080",
            "  hostNetwork: true",
            "  value: -javaagent:/usr/local/otel/opentelemetry-javaagent.jar",
            "  - /usr/local/pyroscope/pyroscope.jar",
            "kubectl get nodes -l type=app",
            "    type: db",
            "      type: \"control\"",
            "nodeSelector: {\"type\": \"app\"}",
            "  ip: 10.0.0.0/8",
        ],
    )
    fun `each pattern matches the form it names`(line: String) {
        assertThat(hostDependency(line)).isNotNull()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "  type: NodePort",
            "nodeSelector: __APP_NODE_SELECTOR__",
            "kubectl get nodes -l \"\${APP_NODE_LABELS}\"",
            "  ip: __CLIENT_NETWORKS_JSON__",
            "    # the pod declares no hostPort",
            "  value: 10.0.0.1",
        ],
    )
    fun `a line that does not depend on the nodes matches no pattern`(line: String) {
        assertThat(hostDependency(line)).isNull()
    }

    @Test
    fun `a hostPath is still a finding in a kit that does not require host-path`() {
        val hostPath = "      hostPath:"

        assertThat(hostDependency(hostPath, declared = emptySet())).isEqualTo("host path")
        assertThat(hostDependency(hostPath, declared = setOf(NodeCapability.PRIVILEGED))).isEqualTo("host path")
        assertThat(hostDependency(hostPath, declared = setOf(NodeCapability.HOST_PATH))).isNull()
    }

    @Test
    fun `requiring host-path allows only hostPath, not a host port or the host network`() {
        val declared = setOf(NodeCapability.HOST_PATH)

        assertThat(hostDependency("        hostPort: 8080", declared)).isEqualTo("host port")
        assertThat(hostDependency("  hostNetwork: true", declared)).isEqualTo("host network")
    }

    @Test
    fun `the requires exemption is read from each kit's own kit yaml`() {
        val cqliteFlight = scannedFiles().first { it.path.endsWith("cqlite-flight${File.separator}daemonset.yaml.template") }
        val otherKit = scannedFiles().first { it.path.endsWith("cassandra-pods${File.separator}statefulset.yaml.template") }

        assertThat(declaredCapabilities(cqliteFlight)).contains(NodeCapability.HOST_PATH)
        assertThat(declaredCapabilities(otherKit)).doesNotContain(NodeCapability.HOST_PATH)
        assertThat(declaredCapabilities(kitLibrary())).isEmpty()
    }

    /**
     * The name of the first pattern [line] matches that none of the [declared] capabilities allows,
     * or null; a comment line matches none.
     */
    private fun hostDependency(
        line: String,
        declared: Set<NodeCapability> = emptySet(),
    ): String? {
        if (line.trimStart().startsWith("#")) return null
        val allowed = declared.mapNotNull { ALLOWED_BY[it] }.toSet()
        return PATTERNS.entries.firstOrNull { (name, pattern) -> name !in allowed && pattern.containsMatchIn(line) }?.key
    }

    /** The capabilities the `kit.yaml` of the kit holding [file] `requires`; none for the kit library. */
    private fun declaredCapabilities(file: File): Set<NodeCapability> {
        val kitYaml =
            generateSequence(file.parentFile) { it.parentFile }
                .takeWhile { it.name != "kits" }
                .map { File(it, "kit.yaml") }
                .firstOrNull { it.isFile } ?: return emptySet()
        return installConfigYaml.decodeFromString(KitConfig.serializer(), kitYaml.readText()).requires.toSet()
    }

    private fun scannedFiles(): List<File> =
        kitRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.isScanned(root) }.toList() }
            .distinctBy { it.relativeTo(it.kitsRoot()).path } + kitLibrary()

    private fun File.isScanned(root: File): Boolean =
        name != "kit.yaml" &&
            extension != "md" &&
            "dashboards" !in relativeTo(root).path.split(File.separatorChar)

    private fun File.kitsRoot(): File = generateSequence(this) { it.parentFile }.first { it.name == "kits" || it.name == "configuration" }

    /** Each built-in kit's directory: one holding a `kit.yaml`, so the test classes' package is not scanned. */
    private fun kitRoots(): List<File> =
        resourceDirs(KITS_RESOURCE_DIR)
            .flatMap { dir -> dir.listFiles { kit -> File(kit, "kit.yaml").isFile }.orEmpty().toList() }

    private fun kitLibrary(): File =
        resourceDirs(CONFIGURATION_RESOURCE_DIR)
            .map { File(it, KIT_LIBRARY) }
            .first { it.isFile }

    private fun resourceDirs(path: String): List<File> =
        javaClass.classLoader
            .getResources(path)
            .toList()
            .map { File(it.toURI()) }

    private companion object {
        const val KITS_RESOURCE_DIR = "com/rustyrazorblade/easydblab/kits"
        const val CONFIGURATION_RESOURCE_DIR = "com/rustyrazorblade/easydblab/configuration"
        const val KIT_LIBRARY = "edl-kit-lib.sh"
        const val ROLE = "(db|app|control)"

        val PATTERNS =
            mapOf(
                "host path" to Regex("""\bhostPath\b"""),
                "host port" to Regex("""\bhostPort\b"""),
                "host network" to Regex("""\bhostNetwork\b"""),
                "AMI agent directory" to Regex("""/usr/local/(otel|pyroscope)\b"""),
                "type label selector" to Regex("""(?<![\w-])type=$ROLE\b"""),
                "type label" to Regex("""(?<![\w-])type:\s*"?$ROLE"?\s*$"""),
                "type label in JSON" to Regex(""""type"\s*:\s*"$ROLE""""),
                "AWS client network" to Regex("""\b10\.0\.0\.0/8\b"""),
            )

        /** The pattern a kit that `requires` the capability may use. */
        val ALLOWED_BY = mapOf(NodeCapability.HOST_PATH to "host path")
    }
}
