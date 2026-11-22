package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * `upload-keys` uploads the joined keys from a scratch file that it removes afterwards. A keys
 * directory the user types resolves against the process's current directory; with none, the
 * workspace's `authorized_keys` directory is read.
 */
class UploadAuthorizedKeysTest : BaseKoinTest() {
    @TempDir
    lateinit var keysParent: File

    private val uploadedContents = Collections.synchronizedList(mutableListOf<String>())
    private val uploadedTargets = Collections.synchronizedList(mutableListOf<String>())
    private val uploadedSources = Collections.synchronizedList(mutableListOf<Path>())
    private var failUpload = false

    private val remote: RemoteOperationsService =
        mock {
            on { executeRemotely(any(), any(), any(), any()) } doReturn Response("")
            on { upload(any(), any(), any(), any()) } doAnswer {
                uploadedSources.add(it.getArgument<Path>(1))
                check(!failUpload) { "upload failed" }
                uploadedContents += Files.readString(it.getArgument<Path>(1))
                uploadedTargets += it.getArgument<String>(2)
            }
        }

    private val state =
        ClusterState(
            name = "test",
            hosts =
                mapOf(
                    ServerType.Cassandra to
                        listOf(
                            ClusterHost("1.1.1.1", "10.0.0.1", "db0", "us-west-2a", "i-1"),
                            ClusterHost("1.1.1.2", "10.0.0.2", "db1", "us-west-2a", "i-2"),
                        ),
                    ServerType.Stress to listOf(ClusterHost("1.1.2.1", "10.0.1.1", "app0", "us-west-2a", "i-3")),
                    ServerType.Control to listOf(ClusterHost("1.1.3.1", "10.0.2.1", "control0", "us-west-2a", "i-4")),
                ),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single { HostOperationsService(get()) }
                single<RemoteOperationsService> { remote }
            },
        )

    @BeforeEach
    fun seedState() {
        get<ClusterStateManager>().replace(state)
    }

    @Test
    fun `uploads the joined keys and leaves no scratch file behind`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")
        File(keys, "b.pub").writeText("ssh-ed25519 BBB b\n")

        UploadAuthorizedKeys().apply { localDir = keys.absolutePath }.execute()

        assertThat(uploadedContents).isNotEmpty
        assertThat(uploadedContents.first()).isEqualTo("ssh-ed25519 AAA a\nssh-ed25519 BBB b\n")
        assertThat(File("authorized_keys_extra")).doesNotExist()
        assertThat(File(context.workingDirectory, "authorized_keys_extra")).doesNotExist()
    }

    @Test
    fun `uploads to the fixed remote name not the scratch file name`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")

        UploadAuthorizedKeys().apply { localDir = keys.absolutePath }.execute()

        assertThat(uploadedTargets).isNotEmpty.containsOnly("~/.ssh/authorized_keys_extra")
    }

    @Test
    fun `a failed upload propagates and still removes the scratch file`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")
        failUpload = true

        assertThatThrownBy { UploadAuthorizedKeys().apply { localDir = keys.absolutePath }.execute() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("upload failed")

        assertThat(uploadedSources).isNotEmpty
        assertThat(uploadedSources.first()).doesNotExist()
    }

    @Test
    fun `with no keys directory given, the workspace's authorized_keys directory is read`() {
        val keys = File(context.workingDirectory, "authorized_keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 WWW workspace\n")
        assertThat(File("authorized_keys").absoluteFile).isNotEqualTo(keys.absoluteFile)

        UploadAuthorizedKeys().execute()

        assertThat(uploadedContents.first()).isEqualTo("ssh-ed25519 WWW workspace\n")
    }

    @Test
    fun `a relative keys directory resolves against the process current directory not the workspace`() {
        // Not @TempDir: a relative path is resolved against the JVM current directory, which this
        // test must not change, so the directory has to live under it.
        val relative = "build/tmp/upload-keys-relative-${System.nanoTime()}"
        val keys = File(relative).also { it.mkdirs() }
        try {
            File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")
            assertThat(File(context.workingDirectory, relative)).doesNotExist()

            UploadAuthorizedKeys().apply { localDir = relative }.execute()

            assertThat(uploadedContents.first()).isEqualTo("ssh-ed25519 AAA a\n")
        } finally {
            keys.deleteRecursively()
        }
    }

    /** Every db, app and control node gets the keys; `--hosts` narrows only the db nodes. */
    @Test
    fun `every node gets the keys, with the db nodes narrowed by the host filter`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")
        val uploadedTo = Collections.synchronizedList(mutableListOf<String>())
        whenever(remote.upload(any(), any(), any(), any())).doAnswer {
            uploadedTo += it.getArgument<Host>(0).alias
            uploadedContents += Files.readString(it.getArgument<Path>(1))
        }

        UploadAuthorizedKeys()
            .apply {
                localDir = keys.absolutePath
                hosts.hostList = "db1"
            }.execute()

        assertThat(uploadedTo).containsExactlyInAnyOrder("db1", "app0", "control0")
        assertThat(uploadedContents).hasSize(3).containsOnly("ssh-ed25519 AAA a\n")
    }

    /** A missing directory fails the one command, not the process, and names the absolute path it looked at. */
    @Test
    fun `with no keys directory in the workspace, the failure names the workspace's authorized_keys path`() {
        val expected = File(context.workingDirectory, "authorized_keys").absolutePath

        assertThatThrownBy { UploadAuthorizedKeys().execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining(expected)
        assertThat((get<OutputHandler>() as BufferedOutputHandler).messages).anyMatch { it.contains("$expected does not exist") }
    }

    /** A directory with no `.pub` file fails before any node is touched, instead of appending a blank line everywhere. */
    @Test
    fun `a keys directory with no public keys fails and changes no node`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "notes.txt").writeText("not a key\n")

        assertThatThrownBy { UploadAuthorizedKeys().apply { localDir = keys.absolutePath }.execute() }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining(keys.absolutePath)
        verifyNoInteractions(remote)
        assertThat((get<OutputHandler>() as BufferedOutputHandler).messages)
            .anyMatch { it.contains("No .pub files in ${keys.absolutePath}") }
    }

    /**
     * Every node's upload is in flight at once: each fake upload waits until all four have started,
     * so a serial run never gets past the first node and fails the barrier's wait.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `the keys go to every node at the same time`() {
        val keys = File(keysParent, "keys").also { it.mkdirs() }
        File(keys, "a.pub").writeText("ssh-ed25519 AAA a\n")
        val allStarted = CyclicBarrier(state.hosts.values.sumOf { it.size })
        whenever(remote.upload(any(), any(), any(), any())).doAnswer {
            allStarted.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS)
            uploadedContents += Files.readString(it.getArgument<Path>(1))
        }

        UploadAuthorizedKeys().apply { localDir = keys.absolutePath }.execute()

        assertThat(uploadedContents).hasSize(4).containsOnly("ssh-ed25519 AAA a\n")
    }

    private companion object {
        const val BARRIER_WAIT_SECONDS = 10L
    }
}
