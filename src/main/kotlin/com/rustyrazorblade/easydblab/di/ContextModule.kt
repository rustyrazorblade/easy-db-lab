package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.ContextFactory
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easydblab.resolveEasyDbLabUserDir
import org.koin.dsl.module
import java.io.File

/**
 * Koin module for Context-related dependencies. Creates the ContextFactory and Context over
 * [startDirectory], the workspace, and [userDirectory] (by default `~/.easy-db-lab/`, or
 * `EASY_DB_LAB_USER_DIR`).
 */
fun contextModule(
    startDirectory: File,
    userDirectory: File = resolveEasyDbLabUserDir(),
) = module {
    single { ContextFactory(userDirectory, startDirectory) }

    // Provide Context instance for services that need it
    single<Context> { get<ContextFactory>().getDefault() }

    // Provide UserConfigProvider to manage user configuration loading
    single {
        val context = get<Context>()
        UserConfigProvider(context.profileDir)
    }

    // Provide User configuration via UserConfigProvider
    single { get<UserConfigProvider>().getUserConfig() }

    // Provide SSH configuration from the user config provider
    single<SSHConfiguration> {
        val userConfigProvider = get<UserConfigProvider>()
        DefaultSSHConfiguration(
            keyPath = userConfigProvider.sshKeyPath,
        )
    }
}
