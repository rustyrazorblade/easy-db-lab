package com.rustyrazorblade.easydblab.services.metrics

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.kubernetes.KubeApiLocator
import com.rustyrazorblade.easydblab.proxy.ObservabilityRoutes
import com.rustyrazorblade.easydblab.proxy.RecordedTunnelRoutes
import com.rustyrazorblade.easydblab.proxy.TunnelProcessControl
import com.rustyrazorblade.easydblab.services.DefaultClusterConfigurationService
import org.koin.dsl.module
import java.io.File

/**
 * Koin module for `metrics stream`.
 *
 * Everything here is a factory, resolved only when the command runs: [SamplePublisher] needs the
 * shared Redis publisher, which exists only when `EASY_DB_LAB_REDIS_URL` is set, and the command
 * checks that before it resolves the service.
 */
val metricsStreamModule =
    module {
        factory<ObservabilityRoutes> {
            RecordedTunnelRoutes(
                envFile = get(),
                sshConfig = File(get<Context>().workingDirectory, DefaultClusterConfigurationService.SSH_CONFIG_FILE).absolutePath,
                control = TunnelProcessControl(),
            )
        }
        factory {
            val clusterStateManager = get<ClusterStateManager>()
            CycleQueries(
                loadState = { clusterStateManager.takeIf { it.exists() }?.load() },
                routes = get(),
                httpClientFactory = get(),
                stressJobs = StressJobWatcher(Fabric8StressJobLister(get<KubeApiLocator>())),
            )
        }
        factory<SamplePublisher> { RedisSamplePublisher(get()) }
        factory<StopSignals> { JvmStopSignals() }
        factory { MetricsStreamService(queries = get(), publisher = get(), eventBus = get(), stopSignals = get()) }
    }
