package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.events.EventBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

class CassandraBuildServiceTest {
    @Test
    fun `base version is read from the build xml property`() {
        val buildXml =
            """
            <project basedir="." default="jar" name="apache-cassandra">
                <property name="debuglevel" value="source,lines,vars"/>
                <property name="base.version" value="5.1"/>
                <property name="scm.connection" value="scm:https://gitbox.apache.org/repos/asf/cassandra.git"/>
            </project>
            """.trimIndent()

        assertThat(CassandraBuildService.readBaseVersion(buildXml, "build.xml")).isEqualTo("5.1")
    }

    @Test
    fun `a build xml with no base version names the file it could not read`() {
        assertThatThrownBy {
            CassandraBuildService.readBaseVersion("<project/>", "/src/cassandra/build.xml")
        }.hasMessageContaining("/src/cassandra/build.xml")
    }

    @Test
    fun `a similarly named property is not mistaken for the base version`() {
        val buildXml = """<property name="base.version.extra" value="wrong"/>"""

        assertThatThrownBy {
            CassandraBuildService.readBaseVersion(buildXml, "build.xml")
        }.hasMessageContaining("base.version")
    }

    @Test
    fun `a directory with no build xml is refused, naming what is missing`(
        @TempDir tmp: Path,
    ) {
        val notACheckout = tmp.resolve("src").also { it.createDirectory() }
        notACheckout.resolve(".git").createDirectory()

        assertThatThrownBy { service().validateCheckout(notACheckout.toFile()) }
            .hasMessageContaining("build.xml")
    }

    @Test
    fun `a source tree that is not a git checkout is refused, because there is no sha to name it`(
        @TempDir tmp: Path,
    ) {
        val noGit = tmp.resolve("src").also { it.createDirectory() }
        noGit.resolve("build.xml").writeText("<project/>")

        assertThatThrownBy { service().validateCheckout(noGit.toFile()) }
            .hasMessageContaining("not a git checkout")
    }

    @Test
    fun `a path that is not a directory at all is refused`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("build.xml").also { it.writeText("<project/>") }

        assertThatThrownBy { service().validateCheckout(file.toFile()) }
            .hasMessageContaining("Not a directory")
    }

    @Test
    fun `a real checkout passes and yields its build xml`(
        @TempDir tmp: Path,
    ) {
        val checkout = tmp.resolve("cassandra").also { it.createDirectory() }
        checkout.resolve("build.xml").writeText("""<property name="base.version" value="5.1"/>""")
        checkout.resolve(".git").createDirectory()

        val buildXml = service().validateCheckout(checkout.toFile())

        assertThat(CassandraBuildService.readBaseVersion(buildXml.readText(), buildXml.path)).isEqualTo("5.1")
    }

    @Test
    fun `ant flags are split into separate arguments`() {
        assertThat(CassandraBuildService.antFlagList("-Duse.jdk11=true -Dsomething=2"))
            .containsExactly("-Duse.jdk11=true", "-Dsomething=2")
    }

    @Test
    fun `absent or blank ant flags contribute no arguments`() {
        assertThat(CassandraBuildService.antFlagList(null)).isEmpty()
        assertThat(CassandraBuildService.antFlagList("   ")).isEmpty()
    }

    @Test
    fun `the binary tarball is found by shape, whatever version the tree names it`(
        @TempDir tmp: Path,
    ) {
        val buildDir = tmp.resolve("build").also { it.createDirectory() }
        buildDir.resolve("apache-cassandra-5.1-SNAPSHOT-bin.tar.gz").writeText("tarball")
        // The source tarball and the jars sit in the same directory and must not be picked up.
        buildDir.resolve("apache-cassandra-5.1-SNAPSHOT-src.tar.gz").writeText("source")
        buildDir.resolve("apache-cassandra-5.1-SNAPSHOT.jar").writeText("jar")

        val found = service().locateTarball(buildDir.toFile())

        assertThat(found.name).isEqualTo("apache-cassandra-5.1-SNAPSHOT-bin.tar.gz")
    }

    @Test
    fun `a build directory with no tarball fails rather than publishing nothing`(
        @TempDir tmp: Path,
    ) {
        val buildDir = tmp.resolve("build").also { it.createDirectory() }

        assertThatThrownBy { service().locateTarball(buildDir.toFile()) }
            .hasMessageContaining("produced no")
    }

    @Test
    fun `a stale tarball beside a fresh one is refused rather than guessed between`(
        @TempDir tmp: Path,
    ) {
        val buildDir = tmp.resolve("build").also { it.createDirectory() }
        buildDir.resolve("apache-cassandra-5.0-SNAPSHOT-bin.tar.gz").writeText("stale")
        buildDir.resolve("apache-cassandra-5.1-SNAPSHOT-bin.tar.gz").writeText("fresh")

        assertThatThrownBy { service().locateTarball(buildDir.toFile()) }
            .hasMessageContaining("found 2")
            .hasMessageContaining("realclean")
    }

    @Test
    fun `a missing build directory is reported, not treated as an empty one`(
        @TempDir tmp: Path,
    ) {
        assertThatThrownBy { service().locateTarball(tmp.resolve("build").toFile()) }
            .hasMessageContaining("produced no")
    }

    @Test
    fun `the tarball digest is the file's real sha256`(
        @TempDir tmp: Path,
    ) {
        val empty = tmp.resolve("empty.tar.gz").toFile().also { it.writeBytes(ByteArray(0)) }

        assertThat(CassandraBuildService.sha256(empty))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `the digest reads the whole file, not just its first buffer`(
        @TempDir tmp: Path,
    ) {
        // Larger than the 8 KiB read buffer: a digest that stopped after one read would collide.
        val a = tmp.resolve("a.bin").toFile().also { it.writeBytes(ByteArray(20_000) { 1 }) }
        val b =
            tmp.resolve("b.bin").toFile().also {
                it.writeBytes(ByteArray(20_000) { i -> if (i < 19_999) 1 else 2 })
            }

        assertThat(CassandraBuildService.sha256(a)).isNotEqualTo(CassandraBuildService.sha256(b))
    }

    @Test
    fun `a jdk's major version is read from its release file, not its directory name`(
        @TempDir tmp: Path,
    ) {
        // SDKMAN's "current" is a symlink whose name carries no version at all.
        val current = tmp.resolve("current").also { it.createDirectory() }
        current.resolve("release").writeText("""JAVA_VERSION="21.0.10"\nJAVA_VENDOR="Amazon"""")

        assertThat(service().javaMajorOf(current.toFile())).isEqualTo("21")
    }

    @Test
    fun `an old-style 1 dot 8 version reads as java 8`(
        @TempDir tmp: Path,
    ) {
        val jdk = tmp.resolve("jdk8").also { it.createDirectory() }
        jdk.resolve("release").writeText("""JAVA_VERSION="1.8.0_452"""")

        assertThat(service().javaMajorOf(jdk.toFile())).isEqualTo("8")
    }

    @Test
    fun `a directory that is not a jdk yields no version rather than a wrong one`(
        @TempDir tmp: Path,
    ) {
        val notAJdk = tmp.resolve("nope").also { it.createDirectory() }

        assertThat(service().javaMajorOf(notAJdk.toFile())).isNull()
    }

    @Test
    fun `an unavailable jdk is refused with the places that were searched`() {
        assertThatThrownBy { service().resolveJavaHome("3") }
            .hasMessageContaining("No JDK 3 found")
    }

    private fun service() = CassandraBuildService(EventBus())
}
