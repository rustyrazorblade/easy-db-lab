package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import org.koin.dsl.module

/**
 * Koin module for configuration-related dependencies.
 */
val configurationModule =
    module {
        single { ClusterStateManager(get<Context>().workspaceFile(CLUSTER_STATE)) }
    }
