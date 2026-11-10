package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.services.HostOperationsService
import com.rustyrazorblade.easydblab.ssh.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `upload-keys` uploads the joined keys from a scratch file that it removes afterwards. The keys
 * directory is a typed path, so a relative one resolves against the process's current directory.
 */
class UploadAuthorizedKeysTest : BaseKoinTest() {
    @TempDir
    lateinit var keysParent: File

    private val uploadedContents = mutableListOf<String>()

    private val remote: RemoteOperationsService =
        mock {
            on { executeRemotely(any(), any(), any(), any()) } doReturn Response("")
            on { upload(any(), any(), any()) } doAnswer { uploadedContents += Files.readString(it.getArgument<Path>(1)) }
        }

    private val state =
        ClusterState(
            name = "test",
            versions = mutableMapOf(),
            hosts = mapOf(ServerType.Cassandra to listOf(ClusterHost("1.1.1.1", "10.0.0.1", "db0", "us-west-2a", "i-1"))),
        )

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<ClusterStateManager> { mock<ClusterStateManager>().also { whenever(it.load()).thenReturn(state) } }
                single { HostOperationsService(get()) }
                single<RemoteOperationsService> { remote }
            },
        )

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
}
