package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The `5.0-rustyrazorblade` entry of the release-line table copies the JDK list of the fork's
 * branch. This reads that branch's `build.xml` and fails when the two differ, so the table cannot
 * drift from the fork without a red test. Remove it with the entry, once the cassandra-builds
 * manifest publishes the supported JDKs (cassandra-builds#1).
 */
class ForkBuildXmlJdkIntegrationTest {
    @Test
    fun `the 5_0-rustyrazorblade entry matches java supported on the fork branch`() {
        val buildXml = checkNotNull(PublicHttp().getOrNull(FORK_BUILD_XML)) { "$FORK_BUILD_XML is not there" }
        val entry = checkNotNull(ReleaseLines.load().lines.firstOrNull { it.name == "5.0-rustyrazorblade" })

        assertThat(BuildXml.javaSupported(buildXml)).isNotEmpty.isEqualTo(entry.javaSupported)
    }

    private companion object {
        const val FORK_BUILD_XML =
            "https://raw.githubusercontent.com/rustyrazorblade/cassandra/cassandra-5.0-rustyrazorblade/build.xml"
    }
}
