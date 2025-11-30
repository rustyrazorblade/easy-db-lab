package com.rustyrazorblade.easycasslab.di

import com.rustyrazorblade.easycasslab.Context
import com.rustyrazorblade.easycasslab.providers.aws.awsModule
import com.rustyrazorblade.easycasslab.providers.docker.dockerModule
import com.rustyrazorblade.easycasslab.providers.ssh.sshModule
import com.rustyrazorblade.easycasslab.services.servicesModule
import org.koin.core.module.Module

/**
 * Central registry of all Koin modules for the application. Add new modules here as they are
 * created.
 */
object KoinModules {
    /**
     * Get all application modules for Koin initialization.
     *
     * @param context The Context instance to use for modules that require it
     */
    fun getAllModules(context: Context): List<Module> =
        listOf(
            outputModule,
            dockerModule,
            sshModule,
            awsModule,
            terraformModule(context),
            servicesModule,
            configurationModule,
        )
}
