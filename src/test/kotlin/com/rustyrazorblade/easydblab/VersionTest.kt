package com.rustyrazorblade.easydblab

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class VersionTest {
    @Test
    fun `test Version constructor with full path`() {
        val path = "/usr/local/cassandra/5.0"
        val version = Version(path)

        assertThat(version.path).isEqualTo(path)
        assertThat(version.versionString).isEqualTo("5.0")
        assertThat(version.conf).isEqualTo("/usr/local/cassandra/5.0/conf")
        assertThat(version.localDir).isEqualTo(Path.of("5.0"))
    }

    @Test
    fun `test fromString companion method`() {
        val versionString = "5.0"
        val version = Version.fromString(versionString)

        assertThat(version.path).isEqualTo("/usr/local/cassandra/5.0")
        assertThat(version.versionString).isEqualTo("5.0")
        assertThat(version.conf).isEqualTo("/usr/local/cassandra/5.0/conf")
        assertThat(version.localDir).isEqualTo(Path.of("5.0"))
    }

    @Test
    fun `test fromRemotePath companion method`() {
        val remotePath = "/opt/cassandra/4.1.3"
        val version = Version.fromRemotePath(remotePath)

        assertThat(version.path).isEqualTo(remotePath)
        assertThat(version.versionString).isEqualTo("4.1.3")
        assertThat(version.conf).isEqualTo("/opt/cassandra/4.1.3/conf")
        assertThat(version.localDir).isEqualTo(Path.of("4.1.3"))
    }

    @Test
    fun `test Version with different path separator`() {
        val path = "/some/deep/nested/path/3.11.15"
        val version = Version(path)

        assertThat(version.path).isEqualTo(path)
        assertThat(version.versionString).isEqualTo("3.11.15")
        assertThat(version.conf).isEqualTo("/some/deep/nested/path/3.11.15/conf")
    }

    @Test
    fun `test Version with snapshot version`() {
        val versionString = "5.0-SNAPSHOT"
        val version = Version.fromString(versionString)

        assertThat(version.path).isEqualTo("/usr/local/cassandra/5.0-SNAPSHOT")
        assertThat(version.versionString).isEqualTo("5.0-SNAPSHOT")
        assertThat(version.conf).isEqualTo("/usr/local/cassandra/5.0-SNAPSHOT/conf")
    }

    @Test
    fun `test Version with path without separator`() {
        val path = "cassandra"
        val version = Version(path)

        assertThat(version.path).isEqualTo(path)
        assertThat(version.versionString).isEqualTo("cassandra")
        assertThat(version.conf).isEqualTo("cassandra/conf")
    }

    @Test
    fun `test Version with trailing slash`() {
        val path = "/usr/local/cassandra/4.0/"
        val version = Version(path)

        assertThat(version.path).isEqualTo(path)
        assertThat(version.versionString).isEqualTo("")
        assertThat(version.conf).isEqualTo("/usr/local/cassandra/4.0//conf")
    }

    @Test
    fun `test Version data class equality`() {
        val version1 = Version.fromString("5.0")
        val version2 = Version.fromString("5.0")
        val version3 = Version.fromString("4.1")

        assertThat(version1).isEqualTo(version2)
        assertThat(version1).isNotEqualTo(version3)
    }

    @Test
    fun `test Version data class copy`() {
        val original = Version.fromString("5.0")
        val copy = original.copy(path = "/opt/cassandra/5.0")

        assertThat(copy.path).isEqualTo("/opt/cassandra/5.0")
        assertThat(copy.versionString).isEqualTo("5.0")
        assertThat(copy.conf).isEqualTo("/opt/cassandra/5.0/conf")
    }

    @Test
    fun `test Version toString`() {
        val version = Version.fromString("5.0")
        val stringRepresentation = version.toString()

        assertThat(stringRepresentation).contains("path=/usr/local/cassandra/5.0")
    }
}
