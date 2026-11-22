package com.rustyrazorblade.easydblab.kits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every packaged kit script uses the `KUBECONFIG` easy-db-lab sets for the process it launches
 * (`KitProcessEnvironment`), and never a path written into the script when it is rendered.
 *
 * A rendered path goes stale when the workspace is moved or renamed, and a path holding `"` or `$`
 * would break the unescaped shell string it lands in. The old `${SCRIPT_DIR}/../../__KUBECONFIG__`
 * form only worked from a kit directory one level under the workspace.
 */
class KitScriptKubeconfigTest {
    private fun packagedScriptTemplates(): List<File> =
        javaClass.classLoader
            .getResources(KITS_RESOURCE_DIR)
            .toList()
            .flatMap { url -> File(url.toURI()).walkTopDown().filter { it.isFile && it.name.endsWith(".sh.template") }.toList() }
            .distinctBy { it.relativeTo(File(it.path.substringBefore(KITS_RESOURCE_DIR))).path }

    @Test
    fun `every kit script that sets KUBECONFIG takes it from the environment easy-db-lab sets`() {
        val exports =
            packagedScriptTemplates().flatMap { script ->
                script.readLines().filter { it.trimStart().startsWith("export KUBECONFIG=") }.map { script.name to it.trim() }
            }

        assertThat(exports).isNotEmpty()
        assertThat(exports).allSatisfy { (_, line) -> assertThat(line).isEqualTo(PROCESS_KUBECONFIG_EXPORT) }
    }

    @Test
    fun `no kit script bakes the kubeconfig path into its text`() {
        val baked =
            packagedScriptTemplates().filter { "__KUBECONFIG__" in it.readText() }.map { it.path.substringAfter(KITS_RESOURCE_DIR) }

        assertThat(baked).isEmpty()
    }

    private companion object {
        const val KITS_RESOURCE_DIR = "com/rustyrazorblade/easydblab/kits"
        const val PROCESS_KUBECONFIG_EXPORT =
            "export KUBECONFIG=\"\${KUBECONFIG:?run this through easy-db-lab, which sets KUBECONFIG}\""
    }
}
