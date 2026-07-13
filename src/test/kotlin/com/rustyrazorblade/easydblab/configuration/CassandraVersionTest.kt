package com.rustyrazorblade.easydblab.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

class CassandraVersionTest {
    private val mainFilePath = Paths.get("packer/cassandra/cassandra_versions.yaml")
    private val extrasDirectoryPath =
        Paths.get(
            "src/test/resources/com/rustyrazorblade/easydblab/configuration/extra_versions",
        )

    @Test
    fun testLoadFromMainAndExtras_ValidYaml() {
        val cassandraVersions = CassandraVersion.loadFromMainAndExtras(mainFilePath, extrasDirectoryPath)
        assertThat(cassandraVersions).isNotEmpty
        assertThat(cassandraVersions).anyMatch { it.version == "3.0" }
        assertThat(cassandraVersions).anyMatch { it.version == "3.11" }
        assertThat(cassandraVersions).anyMatch { it.version == "4.0" }
        assertThat(cassandraVersions).anyMatch { it.version == "1.2" }
    }

    @Test
    fun testYamlDoesNotHaveNulls() {
        val cassandraVersions = CassandraVersion.loadFromMainAndExtras(mainFilePath, extrasDirectoryPath)
        val output = ByteArrayOutputStream()
        CassandraVersion.write(cassandraVersions, output)
        assertThat(output).matches { !it.toString().contains("null") }
    }

    @Test
    fun testJavaDistributionFieldLoadsFromYaml() {
        val cassandraVersions = CassandraVersion.loadFromFile(mainFilePath)
        assertThat(cassandraVersions).allMatch { it.javaDistribution == "openjdk" }
    }

    @Test
    fun testJavaDistributionDefaultsToOpenjdkWhenMissing() {
        val cassandraVersions = CassandraVersion.loadFromMainAndExtras(mainFilePath, extrasDirectoryPath)
        val extraVersion = cassandraVersions.first { it.version == "1.2" }
        assertThat(extraVersion.javaDistribution).isEqualTo("openjdk")
    }

    @Test
    fun testJavaDistributionSerializedInOutput() {
        val cassandraVersions = CassandraVersion.loadFromFile(mainFilePath)
        val output = ByteArrayOutputStream()
        CassandraVersion.write(cassandraVersions, output)
        assertThat(output.toString()).contains("java_distribution")
        assertThat(output.toString()).contains("openjdk")
    }
}
