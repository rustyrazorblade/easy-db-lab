package com.rustyrazorblade.easydblab.providers.ssh

import com.rustyrazorblade.easydblab.configuration.SshTransport
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.providers.aws.ssm.RegionalSsmRoutes
import com.rustyrazorblade.easydblab.providers.aws.ssm.SsmSshRoute
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
 * the SSM transport classes (SsmSshRoute and its parts) by AwsClientsModule.
 */
val sshModule =
    module {
        // SSH connection provider - singleton because it manages a connection pool
        single<SSHConnectionProvider> { DefaultSSHConnectionProvider(get(), get()) }

        // Lazy: the profile that names the transport does not exist yet while `profile setup`
        // runs, and Koin builds this route (through the SOCKS proxy) before that command starts.
        single<SshRoute> {
            LazySshRoute {
                val sshPort = get<SSHConfiguration>().sshPort
                when (get<AwsDestinationAccess>().current().sshTransport) {
                    SshTransport.Direct -> DirectSshRoute(sshPort)
                    SshTransport.Ssm -> SsmSshRoute(get(), sshPort, get())
                }
            }
        }

        // Remote operations service - factory because it's stateless
        factory<RemoteOperationsService> { DefaultRemoteOperationsService(get()) }

        // Remote operations for hosts in another region (an AMI build's own). Only the ssm
        // transport is bound to a region; direct dials the host's address from anywhere.
        factory {
            val destination = get<AwsDestinationAccess>().current()
            val config = get<SSHConfiguration>()
            RegionalRemoteOperations.forProfile(
                shared = get(),
                transport = destination.sshTransport,
                profileRegion = destination.location.region,
                ssmRouteFor = { region -> get<RegionalSsmRoutes>().forRegion(region, config.sshPort) },
                connectionsOver = { route -> DefaultSSHConnectionProvider(config, route) },
            )
        }
    }
