package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.ssm.SsmSshRoute
import org.koin.dsl.module

/**
 * Koin module for SSH-related dependency injection.
 *
 * Provides:
 * - SSHConnectionProvider as a singleton (manages connection pool)
 * - SshRoute as a singleton, chosen by the profile's SSH transport; this is the one place the
 *   transport is decided
 * - RemoteOperationsService as a factory (stateless operations)
 *
 * Note: SSHConfiguration must be provided by another module (e.g., contextModule), and
 * SsmSessionCommandBuilder by the SSM module.
 */
val sshModule =
    module {
        // SSH connection provider - singleton because it manages a connection pool
        single<SSHConnectionProvider> { DefaultSSHConnectionProvider(get(), get()) }

        single<SshRoute> {
            val sshPort = get<SSHConfiguration>().sshPort
            when (get<User>().sshTransport) {
                SshTransport.Direct -> DirectSshRoute(sshPort)
                SshTransport.Ssm -> SsmSshRoute(get(), sshPort, get())
            }
        }

        // Remote operations service - factory because it's stateless
        factory<RemoteOperationsService> { DefaultRemoteOperationsService(get()) }
    }
