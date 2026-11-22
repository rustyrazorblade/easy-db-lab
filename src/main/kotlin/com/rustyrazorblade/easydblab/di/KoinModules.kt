package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.kubernetes.kubernetesModule
import com.rustyrazorblade.easydblab.providers.aws.awsClientsModule
import com.rustyrazorblade.easydblab.providers.docker.dockerModule
import com.rustyrazorblade.easydblab.providers.ssh.sshModule
import com.rustyrazorblade.easydblab.proxy.proxyModule
import com.rustyrazorblade.easydblab.services.aws.awsServicesModule
import com.rustyrazorblade.easydblab.services.metrics.metricsStreamModule
import com.rustyrazorblade.easydblab.services.servicesModule
import org.koin.core.module.Module
import java.io.File

/**
 * Central registry of all Koin modules for the application. Add new modules here as they are
 * created.
 */
object KoinModules {
    /**
     * Get all application modules for Koin initialization, with [startDirectory] as the workspace.
     */
    fun getAllModules(startDirectory: File): List<Module> =
        listOf(
            contextModule(startDirectory),
            outputModule,
            eventBusModule,
            dockerModule,
            sshModule,
            proxyModule,
            kubernetesModule,
            awsClientsModule,
            awsServicesModule,
            servicesModule,
            metricsStreamModule,
            configurationModule,
            commandsModule,
            prompterModule,
        )
}
