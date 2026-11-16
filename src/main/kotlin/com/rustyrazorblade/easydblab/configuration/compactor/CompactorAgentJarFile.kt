package com.rustyrazorblade.easydblab.configuration.compactor

import com.rustyrazorblade.easydblab.Constants
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The lease agent jar the distribution ships, on the machine that runs the CLI.
 *
 * The jar comes with every install, Homebrew included: `agents/` next to the OTel agent under the
 * app home, or `/agents` in the container image. It never comes from a source checkout.
 *
 * @property path the local jar.
 */
class CompactorAgentJarFile(
    val path: Path,
) {
    /**
     * Where the jar goes on the control node, named by its SHA-256. Computed once: the jar does not
     * change while the CLI runs.
     */
    val remote: CompactorAgentJar by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        CompactorAgentJar(HexFormat.of().formatHex(digest.digest()))
    }

    companion object {
        /** The system property the container image sets: its agents are not under the app home. */
        const val AGENTS_DIR_PROPERTY = "easydblab.agentsdir"

        /** The system property the start script sets to the install directory. */
        const val APP_HOME_PROPERTY = "easydblab.apphome"

        /** The jar this CLI was installed with. */
        fun installed(): CompactorAgentJarFile = locate(System.getProperty(AGENTS_DIR_PROPERTY), System.getProperty(APP_HOME_PROPERTY))

        /**
         * The jar in [agentsDir], or else in `agents/` under [appHome].
         *
         * @throws IllegalStateException naming where it looked, when the jar is not there.
         */
        fun locate(
            agentsDir: String?,
            appHome: String?,
        ): CompactorAgentJarFile {
            val dir =
                agentsDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    ?: appHome?.takeIf { it.isNotBlank() }?.let { Path.of(it, "agents") }
                    ?: error("Cannot find the compactor lease agent: neither $AGENTS_DIR_PROPERTY nor $APP_HOME_PROPERTY is set")
            val jar = dir.resolve(Constants.Compactor.AGENT_JAR)
            check(Files.isRegularFile(jar)) { "The compactor lease agent is missing from the install: $jar" }
            return CompactorAgentJarFile(jar)
        }
    }
}
