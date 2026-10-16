package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.export.query.DockerComposeCli
import com.rustyrazorblade.easydblab.export.query.DockerQueryRuntime
import com.rustyrazorblade.easydblab.export.query.QueryExporter
import com.rustyrazorblade.easydblab.export.query.QueryStoreFactory
import com.rustyrazorblade.easydblab.export.query.StackRuntime
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import org.koin.core.scope.Scope
import org.koin.dsl.module

/** The files an export carries besides its data, with the packaged dashboards. */
private fun Scope.stackFiles(): ExportStackFiles =
    ExportStackFiles(PackagedDashboardTree(get(), PackagedKitDashboards.discover()), get<Context>().version.toString())

/**
 * Koin module for `observability export`: the account bucket, checked readable and then read in its
 * own region with the profile's credentials (the check and the client for a region come from
 * `localStackModule`), the tenant exporter, and the query exporter with its docker compose stacks.
 */
val exportModule =
    module {
        factory { CheckedExportStoreFactory(get(), get(), get(), get()) }
        factory<ExportStoreFactory> { get<CheckedExportStoreFactory>() }
        factory {
            TenantExporter(
                stores = get(),
                stack = stackFiles(),
                zipWriter = ExportZipWriter(),
                diskSpace = { directory -> directory.usableSpace },
                eventBus = get(),
            )
        }
        factory {
            val stores = get<CheckedExportStoreFactory>()
            QueryExporter(
                stores = QueryStoreFactory { stores.openForQuery() },
                runtime = DockerQueryRuntime(StackRuntime(DockerComposeCli, eventBus = get())),
                stack = stackFiles(),
                zipWriter = ExportZipWriter(),
                eventBus = get(),
            )
        }
    }
