package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Context
import org.koin.dsl.module

/**
 * Koin module for `observability local`: the account-bucket reads, the local Grafana client, and the
 * creator and dashboard refresher built on them. The packaged dashboards are the core catalog
 * (`servicesModule`) and every packaged kit's.
 */
val localStackModule =
    module {
        factory<LocalStackStore> { SdkLocalStackStore(get(), get()) }
        factory<LocalGrafana> { LocalGrafanaHttp() }
        factory { LocalStackDashboards(get(), PackagedDashboardTree(get(), PackagedKitDashboards.discover())) }
        factory {
            LocalStackCreator(
                user = get(),
                store = get(),
                writer = LocalStackWriter(toolVersion = get<Context>().version.toString()),
                grafana = get(),
                dashboards = get(),
                eventBus = get(),
            )
        }
        factory { LocalDashboardsRefresher(get(), get(), get()) }
    }
