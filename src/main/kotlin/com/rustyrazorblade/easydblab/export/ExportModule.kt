package com.rustyrazorblade.easydblab.export

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.localstack.PackagedDashboardTree
import com.rustyrazorblade.easydblab.localstack.PackagedKitDashboards
import com.rustyrazorblade.easydblab.localstack.S3ClientForRegion
import com.rustyrazorblade.easydblab.services.aws.BucketRegion
import org.koin.dsl.module
import software.amazon.awssdk.services.s3.S3Client

/**
 * Koin module for `observability export`: the account bucket read in its own region with the
 * profile's credentials (the client for a region comes from `localStackModule`), and the tenant
 * exporter with the packaged dashboards.
 */
val exportModule =
    module {
        factory<ExportStoreFactory> {
            val user = get<User>()
            val s3 = get<S3Client>()
            val clients = get<S3ClientForRegion>()
            ExportStoreFactory {
                val bucket = user.s3Bucket.ifBlank { error("No account bucket in the profile. Run 'easy-db-lab up' once to create it.") }
                S3ExportStore(clients.forRegion(BucketRegion(s3).of(bucket)), bucket)
            }
        }
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
