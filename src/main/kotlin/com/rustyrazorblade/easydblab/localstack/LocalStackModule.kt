package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Context
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/**
 * Koin module for `observability local`: the account-bucket reads, the local Grafana client, and the
 * creator and dashboard refresher built on them. The packaged dashboards are the core catalog
 * (`servicesModule`) and every packaged kit's. The bucket is read in its own region, with the
 * profile's credentials.
 */
val localStackModule =
    module {
        factory<S3ClientForRegion> {
            val credentials = get<AwsCredentialsProvider>()
            S3ClientForRegion { region ->
                S3Client
                    .builder()
                    .region(Region.of(region))
                    .credentialsProvider(credentials)
                    .build()
            }
        }
        factory<LocalStackStore> { SdkLocalStackStore(get(), get(), get()) }
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
