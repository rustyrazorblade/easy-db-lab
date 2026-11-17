package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.Version
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [installFile]: the one upload-then-`sudo mv` sequence. The test double records what reaches the
 * service and the content of each uploaded file, so the assertions are on the staged name, the
 * command and the bytes, not on how many calls were made.
 */
class InstallFileTest {
    private val host = Host(public = "54.0.0.1", private = "10.0.0.1", alias = "control0", availabilityZone = "us-west-2a")

    private class Upload(
        val remote: String,
        val content: String,
        val localPath: Path,
    )

    private class RecordingRemoteOps : RemoteOperationsService {
        val uploads = mutableListOf<Upload>()
        val commands = mutableListOf<String>()
        val attemptedUploads = mutableListOf<Path>()
        var uploadFailure: Throwable? = null
        var commandFailure: Throwable? = null

        override fun executeRemotely(
            host: Host,
            command: String,
            output: Boolean,
            secret: Boolean,
        ): Response {
            commands += command
            commandFailure?.let { throw it }
            return Response("")
        }

        override fun upload(
            host: Host,
            local: Path,
            remote: String,
        ) {
            attemptedUploads.add(local)
            uploadFailure?.let { throw it }
            uploads += Upload(remote, Files.readString(local), local)
        }

        override fun uploadDirectory(
            host: Host,
            localDir: File,
            remoteDir: String,
        ) = error("not used")

        override fun uploadDirectory(
            host: Host,
            version: Version,
        ) = error("not used")

        override fun replaceDirectory(
            host: Host,
            localDir: File,
            remoteDir: String,
            owner: String,
        ) = error("not used")

        override fun download(
            host: Host,
            remote: String,
            local: Path,
        ) = error("not used")

        override fun downloadDirectory(
            host: Host,
            remoteDir: String,
            localDir: File,
            includeFilters: List<String>,
            excludeFilters: List<String>,
        ) = error("not used")

        override fun getRemoteVersion(
            host: Host,
            inputVersion: String,
        ): Version = error("not used")
    }

    @ParameterizedTest
    @CsvSource(
        "/usr/local/bin/setup.sh, 0755",
        "/etc/profile.d/stress.sh, 0644",
        "/etc/rancher/k3s/config.yaml, 0600",
        "/etc/systemd/system/cassandra.service.d/axonops.conf, 644",
    )
    fun `moves the staged file to the path with sudo and sets the mode in the same command`(
        remotePath: String,
        mode: String,
        @TempDir dir: Path,
    ) {
        val source = Files.writeString(dir.resolve("local.txt"), "payload")
        val ops = RecordingRemoteOps()

        ops.installFile(host, source, remotePath, mode)

        val upload = ops.uploads.single()
        assertThat(upload.content).isEqualTo("payload")
        assertThat(upload.remote).doesNotContain("/").endsWith("-" + remotePath.substringAfterLast('/'))
        assertThat(ops.commands.single())
            .isEqualTo("sudo chmod $mode ${upload.remote} && sudo mv ${upload.remote} $remotePath || { rm -f ${upload.remote}; exit 1; }")
    }

    @Test
    fun `two installs of the same file name stage under different names`(
        @TempDir dir: Path,
    ) {
        val source = Files.writeString(dir.resolve("local.txt"), "payload")
        val ops = RecordingRemoteOps()

        ops.installFile(host, source, "/etc/a/config.yaml", "0644")
        ops.installFile(host, source, "/etc/b/config.yaml", "0644")

        assertThat(ops.uploads.map { it.remote }).doesNotHaveDuplicates()
    }

    @Test
    fun `quotes a path that the shell would split or expand`(
        @TempDir dir: Path,
    ) {
        val source = Files.writeString(dir.resolve("local.txt"), "payload")
        val ops = RecordingRemoteOps()

        ops.installFile(host, source, "/opt/my dir/it's; rm -rf x", "0644")

        assertThat(ops.commands.single())
            .contains(" '/opt/my dir/it'\\''s; rm -rf x' || { rm -f ")
            .doesNotContain(" /opt/my dir")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "+x", "u+x", "0888", "75", "07555", "0644; rm -rf /", "0644 "])
    fun `refuses a mode that is not octal digits before anything is uploaded`(
        mode: String,
        @TempDir dir: Path,
    ) {
        val source = Files.writeString(dir.resolve("local.txt"), "payload")
        val ops = RecordingRemoteOps()

        assertThatThrownBy { ops.installFile(host, source, "/etc/x", mode) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(ops.uploads).isEmpty()
        assertThat(ops.commands).isEmpty()
    }

    @Test
    fun `a classpath resource is uploaded with its content and its temporary copy is deleted`() {
        val ops = RecordingRemoteOps()

        ops.installFile(host, "/com/rustyrazorblade/easydblab/commands/generate_registry_cert.sh", "/tmp/generate_registry_cert.sh", "0755")

        val upload = ops.uploads.single()
        assertThat(upload.content).contains("openssl req")
        assertThat(upload.localPath).doesNotExist()
        assertThat(ops.commands.single()).startsWith("sudo chmod 0755 ").contains(" /tmp/generate_registry_cert.sh || ")
    }

    @Test
    fun `a failed chmod or mv fails the install and the command removes the staged copy`(
        @TempDir dir: Path,
    ) {
        val source = Files.writeString(dir.resolve("local.txt"), "secret")
        val failing = RecordingRemoteOps().apply { commandFailure = IllegalStateException("chmod: cannot access") }

        assertThatThrownBy { failing.installFile(host, source, "/etc/rancher/k3s/config.yaml", "0600") }
            .hasMessage("chmod: cannot access")

        assertThat(
            failing.commands.single(),
        ).matches("sudo chmod 0600 (\\S+) && sudo mv \\1 /etc/rancher/k3s/config.yaml \\|\\| \\{ rm -f \\1; exit 1; }")
    }

    @Test
    fun `a missing classpath resource fails before anything is uploaded`() {
        val ops = RecordingRemoteOps()

        assertThatThrownBy { ops.installFile(host, "/no/such/resource.sh", "/tmp/x.sh", "0755") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("/no/such/resource.sh")

        assertThat(ops.uploads).isEmpty()
    }

    @Test
    fun `a failed upload leaves no temporary copy of a classpath resource`() {
        val failing = RecordingRemoteOps().apply { uploadFailure = IllegalStateException("sftp failed") }

        assertThatThrownBy {
            failing.installFile(host, "/com/rustyrazorblade/easydblab/commands/generate_registry_cert.sh", "/tmp/x.sh", "0755")
        }.hasMessage("sftp failed")

        assertThat(failing.attemptedUploads.single()).doesNotExist()
    }
}
