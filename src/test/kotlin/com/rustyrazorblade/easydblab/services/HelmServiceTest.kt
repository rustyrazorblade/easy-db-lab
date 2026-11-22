package com.rustyrazorblade.easydblab.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The decisions [DefaultHelmService] makes, through a [RecordingClusterTooling]: how a values file
 * reaches helm and how `helm list` output reads as a release. The commands each method runs on the
 * control node are in [ClusterToolingSshCommandsTest].
 */
internal class HelmServiceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `upgradeInstall passes the values file's content as input to --values -`() {
        val valuesFile = File(tempDir, "values.yaml").also { it.writeText("key: value\n") }
        val tooling = RecordingClusterTooling()

        DefaultHelmService(
            tooling,
        ).upgradeInstall(release = "myapp", chart = "myrepo/myapp", namespace = "default", valuesFile = valuesFile)

        val call = tooling.calls.single()
        assertThat(call.input).isEqualTo("key: value\n")
        assertThat(call.args).containsSequence("--values", "-")
    }

    @Test
    fun `upgradeInstall without a values file passes no input and no --values`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).upgradeInstall(release = "r", chart = "c", namespace = "n")

        assertThat(tooling.calls.single().input).isNull()
        assertThat(tooling.calls.single().args).doesNotContain("--values")
    }

    @Test
    fun `releaseExists reads any listed name as present, and blank output as absent, asking quietly`() {
        val listed = RecordingClusterTooling { "myapp\n" }
        val blank = RecordingClusterTooling { "  \n" }

        assertThat(DefaultHelmService(listed).releaseExists("myapp", "default")).isTrue()
        assertThat(DefaultHelmService(blank).releaseExists("noapp", "default")).isFalse()
        assertThat(listed.calls.single().echo).isFalse()
    }

    /**
     * `helm list` shows only deployed and failed releases unless asked for all. A release an
     * interrupted install left `pending-install` still holds its name, so the lookup must list
     * every state, or a re-run of `up` would try to install over it.
     */
    @Test
    fun `releaseExists lists releases in every state, pending ones included`() {
        val tooling = RecordingClusterTooling { "" }

        DefaultHelmService(tooling).releaseExists("cilium", "kube-system")

        assertThat(tooling.calls.single().args).contains("--all")
    }

    /**
     * Property over names holding every regex metacharacter helm's Go `regexp` knows: the `-f`
     * filter matches the name itself and nothing that differs from it only where a metacharacter
     * would have matched something else. Java's regex reads these escapes as Go's does.
     */
    @Test
    fun `releaseExists filters on the release name matched literally and whole`() {
        val cases =
            listOf(
                "my.app" to listOf("myxapp", "my.app2", "amy.app"),
                "a+b" to listOf("aab", "aaab"),
                "x*" to listOf("xxx", ""),
                "v1?" to listOf("v"),
                "(a|b)" to listOf("a", "b"),
                "[ab]" to listOf("a"),
                "c{2}" to listOf("cc"),
                "^\\$" to listOf(""),
            )

        cases.forEach { (release, others) ->
            val tooling = RecordingClusterTooling()
            DefaultHelmService(tooling).releaseExists(release, "default")
            val args = tooling.calls.single().args
            val filter = Regex(args[args.indexOf("-f") + 1])

            assertThat(filter.matches(release)).describedAs("%s matches itself", filter).isTrue()
            assertThat(others).describedAs("%s matches only %s", filter, release).noneMatch { filter.matches(it) }
        }
    }
}
