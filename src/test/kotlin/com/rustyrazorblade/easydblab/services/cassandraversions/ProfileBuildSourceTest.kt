package com.rustyrazorblade.easydblab.services.cassandraversions

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/** The named builds of the profile's `cassandra_versions/` directory. */
class ProfileBuildSourceTest {
    @TempDir
    lateinit var dir: File

    private fun write(
        name: String,
        text: String,
    ) = File(dir, name).writeText(text.trimIndent())

    private fun source() = ProfileBuildSource(dir)

    @Test
    fun `an entry loads with every field, and removed keys are ignored`() {
        write(
            "mine.yaml",
            """
            - version: my-build
              url: https://example.com/my.tar.gz
              java_supported: ["11", "17"]
              java: "11"
              python: "3.12.1"
              lazy: true
              jvm_options: []
            - version: my-git
              url: https://github.com/me/cassandra.git
              branch: trunk-fix
              java: "17"
              ant_flags: "-Dx=y"
            """,
        )

        assertThat(source().builds()).containsExactlyInAnyOrder(
            ProfileBuild("my-build", "https://example.com/my.tar.gz", javaSupported = listOf("11", "17"), java = "11", python = "3.12.1"),
            ProfileBuild("my-git", "https://github.com/me/cassandra.git", branch = "trunk-fix", java = "17", antFlags = "-Dx=y"),
        )
        assertThat(source().find("my-build")?.url).isEqualTo("https://example.com/my.tar.gz")
        assertThat(source().find("5.0")).isNull()
    }

    @Test
    fun `the JDK list is java supported, else the one java, else none`() {
        assertThat(ProfileBuild("a", "u", javaSupported = listOf("11", "17"), java = "17").jdks()).containsExactly("11", "17")
        assertThat(ProfileBuild("a", "u", java = "17").jdks()).containsExactly("17")
        assertThat(ProfileBuild("a", "u").jdks()).isEmpty()
    }

    @Test
    fun `no directory gives no builds`() {
        assertThat(ProfileBuildSource(File(dir, "absent")).builds()).isEmpty()
    }

    @Test
    fun `files that are not yaml are not read`() {
        write("notes.txt", "not: [yaml")

        assertThat(source().builds()).isEmpty()
    }

    @Test
    fun `an entry with no url fails, naming the file and the entry`() {
        write("old.yaml", "- version: 4.1.7\n  java: \"11\"")

        assertThatThrownBy { source().builds() }
            .isInstanceOf(ProfileBuildException::class.java)
            .hasMessageContainingAll("old.yaml", "4.1.7", "url")
    }

    @Test
    fun `a name in two files fails, naming both`() {
        write("a.yaml", "- version: dup\n  url: https://a")
        write("b.yaml", "- version: dup\n  url: https://b")

        assertThatThrownBy { source().builds() }
            .isInstanceOf(ProfileBuildException::class.java)
            .hasMessageContainingAll("dup", "a.yaml", "b.yaml")
    }

    @Test
    fun `a file that cannot be parsed fails, naming it`() {
        write("broken.yaml", "- version: x\n  url: [unclosed")

        assertThatThrownBy { source().builds() }
            .isInstanceOf(ProfileBuildException::class.java)
            .hasMessageContaining("broken.yaml")
    }

    @Test
    fun `java outside java supported fails, naming the entry and both`() {
        write("j.yaml", "- version: jb\n  url: https://x\n  java_supported: [\"11\", \"17\"]\n  java: \"21\"")

        assertThatThrownBy { source().builds() }
            .isInstanceOf(ProfileBuildException::class.java)
            .hasMessageContainingAll("j.yaml", "jb", "21", "11, 17")
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = ["../escape", "feature/x", "..", "-rf", "a b"])
    fun `a version name that is not one safe path segment fails, naming the file`(name: String) {
        write("bad.yaml", "- version: \"$name\"\n  url: https://x")

        assertThatThrownBy { source().builds() }
            .isInstanceOf(ProfileBuildException::class.java)
            .hasMessageContaining("bad.yaml", name, "letters, digits")
    }
}
