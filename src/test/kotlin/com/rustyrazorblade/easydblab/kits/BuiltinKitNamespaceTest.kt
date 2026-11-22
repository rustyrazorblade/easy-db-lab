package com.rustyrazorblade.easydblab.kits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `kubectl` command a built-in kit or the kit shell library runs names its namespace (issue
 * 1094). A command that names none acts in the kubeconfig's current namespace, which on a cluster
 * the user brings is whatever their context says and on every cluster is not necessarily the one
 * `init --namespace` recorded. Kits pass `"${NAMESPACE}"` (or `__NAMESPACE__` in a rendered
 * template). Cluster-scoped and local forms (nodes, a namespace, `--local`, `kubectl proxy`) need
 * none.
 */
class BuiltinKitNamespaceTest {
    @Test
    fun `every kubectl command of a built-in kit or the kit library names its namespace`() {
        val files = builtinKitShellFiles() + File(LIBRARY)
        val offenders = files.flatMap { file -> unscopedCommands(file.readLines()).map { "${file.path}:$it" } }

        assertThat(files.map { it.name }).contains("kit.yaml", "edl-kit-lib.sh", "start.sh.template")
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the scan follows a command over its continuation lines and pipes`() {
        assertThat(
            unscopedCommands(
                listOf(
                    "kubectl wait --for=condition=Ready pods \\",
                    "  -l app=x \\",
                    "  --namespace \"\${NAMESPACE}\" \\",
                    "  --timeout=300s",
                    "kubectl create configmap c --namespace \"\${NAMESPACE}\" --dry-run=client -o yaml |",
                    "  kubectl apply -n \"\${NAMESPACE}\" -f -",
                    "kubectl get nodes -o name",
                    "kubectl annotate --local -f kafka.yaml a=b -o yaml",
                    "# kubectl logs mypod",
                    "kubectl apply -f x.yaml -n __NAMESPACE__",
                ),
            ),
        ).isEmpty()
        assertThat(
            unscopedCommands(
                listOf(
                    "kubectl wait --for=condition=Ready pods \\",
                    "  --timeout=300s",
                    "kubectl run \"\${POD}\" --image=x",
                    "POD=\$(kubectl get pods -l app=x -o name)",
                ),
            ),
        ).containsExactly(
            "1: kubectl wait --for=condition=Ready pods \\ --timeout=300s",
            "3: kubectl run \"\${POD}\" --image=x",
            "4: POD=\$(kubectl get pods -l app=x -o name)",
        )
    }

    /** `<line>: <command>` for each kubectl command in [lines] that names no namespace. */
    private fun unscopedCommands(lines: List<String>): List<String> =
        lines.indices
            .filter { KUBECTL.containsMatchIn(lines[it]) && !lines[it].trim().startsWith("#") }
            .mapNotNull { start ->
                var end = start
                while (CONTINUED.containsMatchIn(lines[end]) && end + 1 < lines.size) end++
                val command = (start..end).joinToString(" ") { lines[it].trim() }
                "${start + 1}: $command".takeUnless { SCOPED.containsMatchIn(command) }
            }

    /** The shell a built-in kit runs: its `kit.yaml` steps and its `.sh` scripts, rendered or not. */
    private fun builtinKitShellFiles(): List<File> =
        File(KITS_DIR)
            .listFiles { dir -> File(dir, "kit.yaml").isFile }
            .orEmpty()
            .flatMap { kitDir ->
                kitDir.walkTopDown().filter { it.isFile && (it.name == "kit.yaml" || SHELL_FILE.matches(it.name)) }.toList()
            }

    private companion object {
        const val KITS_DIR = "src/main/resources/com/rustyrazorblade/easydblab/kits"
        const val LIBRARY = "src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
        val SHELL_FILE = Regex(""".*\.sh(\.template)?""")
        val KUBECTL = Regex("""\bkubectl\s""")
        val CONTINUED = Regex("""(\\|\|)\s*$""")

        /** A namespace given, or a form that is cluster-scoped or never reaches the API server. */
        val SCOPED =
            Regex(
                """(\s-n\s|--namespace[ =]|--all-namespaces|--local\b|kubectl proxy|get nodes|delete namespace|create namespace)""",
            )
    }
}
