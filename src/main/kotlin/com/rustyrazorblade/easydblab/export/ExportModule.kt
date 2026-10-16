package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import org.koin.dsl.module

/**
 * Koin module for `observability export`: the account bucket, checked readable and then read in its
 * own region with the profile's credentials (the check and the client for a region come from
 * `localStackModule`), and the tenant exporter with the packaged dashboards.
 */
val exportModule =
    module {
        factory<ExportStoreFactory> { CheckedExportStoreFactory(get(), get(), get(), get()) }
        factory {
            TenantExporter(
                stores = get(),
                stack = ExportStackFiles(PackagedDashboardTree(get(), PackagedKitDashboards.discover()), get<Context>().version.toString()),
                zipWriter = ExportZipWriter(),
                diskSpace = { directory -> directory.usableSpace },
                eventBus = get(),
            )
        }
    }
