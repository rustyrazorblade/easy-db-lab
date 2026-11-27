package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.ContextFactory
import com.rustyrazorblade.easydblab.configuration.profile.ProfileAccess
import com.rustyrazorblade.easydblab.configuration.profile.ProfileStore
import com.rustyrazorblade.easydblab.configuration.profile.aws.LegacyAwsProfileReader
import com.rustyrazorblade.easydblab.configuration.profile.aws.awsProfileSerializers
import com.rustyrazorblade.easydblab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easydblab.resolveEasyDbLabUserDir
import org.koin.dsl.module
import java.io.File

/**
 * Koin module for Context-related dependencies. Creates the ContextFactory and Context over
 * [startDirectory], the workspace, and [userDirectory] (by default `~/.easy-db-lab/`, or
 * `EASY_DB_LAB_USER_DIR`).
 *
 * It binds the [ProfileStore] and [ProfileAccess], never a profile value: a value bound once would
 * miss a key pair or bucket a command saves later. The composition supplies the AWS destination's
 * serializers and the earlier file's reader, so the store itself names no destination type.
 */
fun contextModule(
    startDirectory: File,
    userDirectory: File = resolveEasyDbLabUserDir(),
) = module {
    single { ContextFactory(userDirectory, startDirectory) }

    // Provide Context instance for services that need it
    single<Context> { get<ContextFactory>().getDefault() }

    single { ProfileStore(get<Context>().profileDir, awsProfileSerializers, LegacyAwsProfileReader()) }

    // Reads the saved profile at each call
    single<ProfileAccess> {
        val store = get<ProfileStore>()
        ProfileAccess { store.load() }
    }

    single<SSHConfiguration> { DefaultSSHConfiguration(keyPath = get<ProfileStore>().sshKeyPath) }
}
