package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.ContextFactory
import com.rustyrazorblade.easydblab.configuration.ProfileRegion
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easydblab.resolveEasyDbLabUserDir
import org.koin.dsl.module

/**
 * Koin module for Context-related dependencies. Creates the ContextFactory and Context
 * internally using the standard user directory (~/.easy-db-lab/).
 */
val contextModule =
    module {
        // Create and provide the ContextFactory
        single {
            val easyDbLabUserDirectory = resolveEasyDbLabUserDir()
            ContextFactory(easyDbLabUserDirectory)
        }

        // Provide Context instance for services that need it
        single<Context> { get<ContextFactory>().getDefault() }

        // Provide UserConfigProvider to manage user configuration loading
        single {
            val context = get<Context>()
            UserConfigProvider(context.profileDir)
        }

        // Provide User configuration via UserConfigProvider
        single { get<UserConfigProvider>().getUserConfig() }

        // The profile's region, loaded only when a state records no region (never in a local workspace).
        factory<ProfileRegion> { ProfileRegion { get<User>().region } }

        // Provide SSH configuration from the user config provider
        single<SSHConfiguration> {
            val userConfigProvider = get<UserConfigProvider>()
            DefaultSSHConfiguration(
                keyPath = userConfigProvider.sshKeyPath,
            )
        }
    }
