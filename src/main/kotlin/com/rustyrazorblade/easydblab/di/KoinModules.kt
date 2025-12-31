package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.kubernetes.kubernetesModule
import com.rustyrazorblade.easydblab.observability.observabilityModule
import com.rustyrazorblade.easydblab.providers.aws.awsModule
import com.rustyrazorblade.easydblab.providers.docker.dockerModule
import com.rustyrazorblade.easydblab.providers.ssh.sshModule
import com.rustyrazorblade.easydblab.proxy.proxyModule
import com.rustyrazorblade.easydblab.services.servicesModule
import org.koin.core.module.Module

/**
 * Central registry of all Koin modules for the application. Add new modules here as they are
 * created.
 */
object KoinModules {
    /**
     * Get all application modules for Koin initialization.
     */
    fun getAllModules(): List<Module> =
        listOf(
            contextModule,
            outputModule,
            dockerModule,
            sshModule,
            proxyModule,
            kubernetesModule,
            observabilityModule,
            awsModule,
            servicesModule,
            configurationModule,
            commandsModule,
            prompterModule,
        )
}
