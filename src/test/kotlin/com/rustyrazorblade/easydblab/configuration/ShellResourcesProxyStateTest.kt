package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * No packaged shell script reads proxy state from JSON: shell code gets it from the sourceable proxy
 * env file. Every `.sh` resource under the `configuration` package is checked, found on the classpath,
 * so a script added later is covered with no change here. Comment lines are skipped: a comment may say
 * why a script avoids `jq`.
 */
internal class ShellResourcesProxyStateTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("scripts")
    fun `a packaged shell script uses no jq, no python and no proxy state JSON`(name: String) {
        val code =
            scriptFiles()
                .getValue(name)
                .readLines()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString("\n")

        assertThat(code).doesNotContainPattern("""\bjq\b""")
        assertThat(code).doesNotContainPattern("""\bpython\d*\b""")
        assertThat(code).doesNotContain(".socks5-proxy-state")
    }

    companion object {
        private const val CONFIGURATION = "com/rustyrazorblade/easydblab/configuration"

        /**
         * Every `.sh` resource under the configuration package, by its path relative to the package, from
         * every classpath root that holds the package: the classes and resources directories are separate.
         */
        private fun scriptFiles(): Map<String, File> =
            ShellResourcesProxyStateTest::class.java.classLoader
                .getResources(CONFIGURATION)
                .toList()
                .filter { it.protocol == "file" }
                .map { File(it.toURI()) }
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "sh" }
                        .map { it.relativeTo(root).path to it }
                        .toList()
                }.toMap()

        @JvmStatic
        fun scripts(): List<String> {
            val found = scriptFiles().keys.sorted()
            // The three workspace scripts must be among them, or the search looked in the wrong place.
            check(found.containsAll(listOf("env.sh", "tool-wrapper.sh", "edl-socks-tunnel.sh"))) { "shell resources not found: $found" }
            return found
        }
    }
}
