package com.rustyrazorblade.easydblab.configuration.agents

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Pyroscope agent image is built from `docker/pyroscope-java-agent/Dockerfile` and published
 * under the tag `publish-container.yml` reads from its `PYROSCOPE_VERSION`, while kits pull the tag
 * in [Constants.Kit.Agents.PYROSCOPE_IMAGE]. A bump that changes one and not the other publishes
 * one tag while every pod pulls another.
 */
class PyroscopeAgentImageVersionTest {
    @Test
    fun `the Dockerfile builds the agent version the kits pull`() {
        val version =
            File("docker/pyroscope-java-agent/Dockerfile")
                .readLines()
                .firstNotNullOfOrNull { VERSION_ARG.matchEntire(it.trim())?.groupValues?.get(1) }

        assertThat(version).isEqualTo(Constants.PyroscopeJavaAgent.VERSION)
        assertThat(
            Constants.Kit.Agents.PYROSCOPE_IMAGE
                .substringAfterLast(':'),
        ).isEqualTo(version)
    }

    private companion object {
        val VERSION_ARG = Regex("""ARG PYROSCOPE_VERSION=(\S+)""")
    }
}
