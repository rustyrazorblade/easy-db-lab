package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.services.RecordingClusterTooling.Call
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The argument lists [DefaultHelmService] builds, through a [RecordingClusterTooling]. They are what
 * both forms run, so a drifted argument fails here; the strings the SSH form renders from them are
 * pinned in [ClusterToolingSshCommandsTest].
 */
internal class HelmServiceTest {
    @TempDir
    lateinit var tempDir: File

    private fun helm(
        vararg args: String,
        stdin: String? = null,
        echo: Boolean = true,
        shellQuoted: Set<Int> = emptySet(),
    ) = Call(ClusterTool.HELM, args.toList(), stdin, echo, shellQuoted)

    @Test
    fun `repoAdd adds the repository with --force-update, then updates that repository`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).repoAdd("myrepo", "https://example.com/charts")

        assertThat(tooling.calls).containsExactly(
            helm("repo", "add", "myrepo", "https://example.com/charts", "--force-update"),
            helm("repo", "update", "myrepo"),
        )
    }

    @Test
    fun `upgradeInstall builds the install with its version and one --set per value`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).upgradeInstall(
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "default",
            version = "1.2.3",
            values = linkedMapOf("key1" to "val1", "key2" to "val2"),
        )

        assertThat(tooling.calls).containsExactly(
            helm(
                "upgrade",
                "--install",
                "myapp",
                "myrepo/myapp",
                "--namespace",
                "default",
                "--version",
                "1.2.3",
                "--set",
                "key1=val1",
                "--set",
                "key2=val2",
            ),
        )
    }

    @Test
    fun `upgradeInstall adds --wait with its timeout and --create-namespace only when asked to`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).upgradeInstall(
            release = "myapp",
            chart = "myrepo/myapp",
            namespace = "mynamespace",
            createNamespace = true,
            wait = true,
            timeout = "10m",
        )

        assertThat(tooling.calls).containsExactly(
            helm(
                "upgrade",
                "--install",
                "myapp",
                "myrepo/myapp",
                "--namespace",
                "mynamespace",
                "--create-namespace",
                "--wait",
                "--timeout",
                "10m",
            ),
        )
    }

    @Test
    fun `upgradeInstall passes the values file's content as input to --values -`() {
        val valuesFile = File(tempDir, "values.yaml").also { it.writeText("key: value\n") }
        val tooling = RecordingClusterTooling()

        DefaultHelmService(
            tooling,
        ).upgradeInstall(release = "myapp", chart = "myrepo/myapp", namespace = "default", valuesFile = valuesFile)

        assertThat(tooling.calls).containsExactly(
            helm("upgrade", "--install", "myapp", "myrepo/myapp", "--namespace", "default", "--values", "-", stdin = "key: value\n"),
        )
    }

    @Test
    fun `upgradeInstall without a values file passes no input`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).upgradeInstall(release = "r", chart = "c", namespace = "n")

        assertThat(tooling.calls.single().stdin).isNull()
        assertThat(tooling.calls.single().args).doesNotContain("--values")
    }

    @Test
    fun `uninstall ignores a release that is not there`() {
        val tooling = RecordingClusterTooling()

        DefaultHelmService(tooling).uninstall("myapp", "default")

        assertThat(tooling.calls).containsExactly(helm("uninstall", "myapp", "-n", "default", "--ignore-not-found"))
    }

    /**
     * The release filter holds a `$` the SSH form's remote shell would read as a variable, so the
     * service marks it for quoting and passes it raw: the local form hands it to helm as it is.
     */
    @Test
    fun `releaseExists filters on the exact release name, quietly, and reads any output as present`() {
        val tooling = RecordingClusterTooling { "myapp\n" }

        val exists = DefaultHelmService(tooling).releaseExists("myapp", "default")

        assertThat(exists).isTrue()
        assertThat(tooling.calls).containsExactly(
            helm("list", "-n", "default", "-f", "^myapp$", "-q", echo = false, shellQuoted = setOf(4)),
        )
    }

    @Test
    fun `releaseExists is false when helm lists nothing`() {
        val tooling = RecordingClusterTooling { "  \n" }

        assertThat(DefaultHelmService(tooling).releaseExists("noapp", "default")).isFalse()
    }

    @Test
    fun `a failed tool's exception reaches the caller unchanged`() {
        val failure = ClusterToolFailedException(ClusterTool.HELM, listOf("upgrade"), 1, "boom")
        val tooling = RecordingClusterTooling { throw failure }

        assertThatThrownBy { DefaultHelmService(tooling).upgradeInstall(release = "r", chart = "c", namespace = "n") }.isSameAs(failure)
    }
}
