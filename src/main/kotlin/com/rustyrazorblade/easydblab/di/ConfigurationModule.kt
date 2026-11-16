package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.CLUSTER_STATE
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContractSource
import com.rustyrazorblade.easydblab.configuration.environment.StateClusterContractSource
import org.koin.dsl.module

/**
 * Koin module for configuration-related dependencies.
 */
val configurationModule =
    module {
        single { ClusterStateManager(get<Context>().workspaceFile(CLUSTER_STATE)) }
        // Stateless: it reads state.json on every call, so one instance never goes stale.
        single<ClusterContractSource> { StateClusterContractSource(get()) }
    }
