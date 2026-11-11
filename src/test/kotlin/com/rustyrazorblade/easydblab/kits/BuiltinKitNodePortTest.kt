package com.rustyrazorblade.easydblab.kits

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.services.ExtensionRegistry
import com.rustyrazorblade.easydblab.services.ExtensionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Running several kits at once is a core goal, and a NodePort is cluster-wide: two kits (or two
 * instances of one kit) asking for the same port cannot both start. postgres-duckdb used plain
 * postgres's 30432 and 30987, so the two could not run side by side.
 *
 * Every NodePort a built-in kit declares in its manifests, every port a postgres instance takes
 * from `extensions.yaml` (plain postgres takes the resolver's defaults), and the Hubble UI's
 * NodePort must be distinct.
 */
class BuiltinKitNodePortTest {
    @Test
    fun `every NodePort a built-in kit or postgres instance declares is unique`() {
        val claims = manifestNodePorts() + postgresInstanceNodePorts() + ("cilium:hubble-ui" to Constants.Cilium.HUBBLE_UI_NODE_PORT)

        val duplicates =
            claims
                .groupBy({ it.second }, { it.first })
                .filterValues { it.size > 1 }

        assertThat(claims.map { it.first }).contains("clickhouse:nodeport-service.yaml.template", "postgres:duckdb:client")
        assertThat(duplicates).isEmpty()
    }

    /** `nodePort: <n>` in each built-in kit's manifests, keyed `kit:file`; placeholders are skipped. */
    private fun manifestNodePorts(): List<Pair<String, Int>> =
        builtinKitDirs().flatMap { kitDir ->
            kitDir
                .walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".template") || it.name.endsWith(".yaml")) }
                .flatMap { file ->
                    NODE_PORT.findAll(file.readText()).map { "${kitDir.name}:${file.name}" to it.groupValues[1].toInt() }
                }.toList()
        }

    /** The client and metrics NodePorts of plain postgres and of each extension instance. */
    private fun postgresInstanceNodePorts(): List<Pair<String, Int>> {
        val registry = ExtensionRegistry.fromClasspath("postgres")
        val instances = listOf("") + registry.all().keys
        return instances.flatMap { extension ->
            val config =
                ExtensionResolver(registry, "17").resolve(
                    extensions = listOfNotNull(extension.ifBlank { null }),
                    imageOverride = null,
                    additionalPreload = emptyList(),
                    additionalCreate = emptyList(),
                )
            val name = "postgres:${extension.ifBlank { "plain" }}"
            listOf("$name:client" to config.postgresPort, "$name:metrics" to config.metricsPort)
        }
    }

    // Main and test resources both have a kits directory; the built-in kits are in main.
    private fun builtinKitDirs(): List<File> =
        javaClass.classLoader
            .getResources(KITS_RESOURCE_DIR)
            .toList()
            .flatMap { url -> File(url.toURI()).listFiles { dir -> File(dir, "kit.yaml").isFile }.orEmpty().toList() }
            .distinctBy { it.name }

    private companion object {
        const val KITS_RESOURCE_DIR = "com/rustyrazorblade/easydblab/kits"
        val NODE_PORT = Regex("""nodePort:\s*(\d+)""")
    }
}
