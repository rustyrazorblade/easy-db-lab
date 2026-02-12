package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.commands.BuildBaseImage
import com.rustyrazorblade.easydblab.commands.BuildCassandraImage
import com.rustyrazorblade.easydblab.commands.BuildImage
import com.rustyrazorblade.easydblab.commands.Clean
import com.rustyrazorblade.easydblab.commands.ConfigureAWS
import com.rustyrazorblade.easydblab.commands.ConfigureAxonOps
import com.rustyrazorblade.easydblab.commands.Down
import com.rustyrazorblade.easydblab.commands.Exec
import com.rustyrazorblade.easydblab.commands.Hosts
import com.rustyrazorblade.easydblab.commands.Init
import com.rustyrazorblade.easydblab.commands.Ip
import com.rustyrazorblade.easydblab.commands.PruneAMIs
import com.rustyrazorblade.easydblab.commands.Repl
import com.rustyrazorblade.easydblab.commands.Server
import com.rustyrazorblade.easydblab.commands.SetupInstance
import com.rustyrazorblade.easydblab.commands.SetupProfile
import com.rustyrazorblade.easydblab.commands.ShowIamPolicies
import com.rustyrazorblade.easydblab.commands.Status
import com.rustyrazorblade.easydblab.commands.Up
import com.rustyrazorblade.easydblab.commands.UploadAuthorizedKeys
import com.rustyrazorblade.easydblab.commands.Version
import com.rustyrazorblade.easydblab.commands.aws.Vpcs
import com.rustyrazorblade.easydblab.commands.cassandra.DownloadConfig
import com.rustyrazorblade.easydblab.commands.cassandra.ListVersions
import com.rustyrazorblade.easydblab.commands.cassandra.Restart
import com.rustyrazorblade.easydblab.commands.cassandra.Start
import com.rustyrazorblade.easydblab.commands.cassandra.Stop
import com.rustyrazorblade.easydblab.commands.cassandra.UpdateConfig
import com.rustyrazorblade.easydblab.commands.cassandra.UseCassandra
import com.rustyrazorblade.easydblab.commands.cassandra.WriteConfig
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressFields
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressInfo
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressList
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressLogs
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStart
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStatus
import com.rustyrazorblade.easydblab.commands.cassandra.stress.StressStop
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseInit
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStart
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStatus
import com.rustyrazorblade.easydblab.commands.clickhouse.ClickHouseStop
import com.rustyrazorblade.easydblab.commands.dashboards.DashboardsGenerate
import com.rustyrazorblade.easydblab.commands.dashboards.DashboardsUpload
import com.rustyrazorblade.easydblab.commands.k8.K8Apply
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStart
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStatus
import com.rustyrazorblade.easydblab.commands.opensearch.OpenSearchStop
import com.rustyrazorblade.easydblab.commands.spark.SparkJobs
import com.rustyrazorblade.easydblab.commands.spark.SparkLogs
import com.rustyrazorblade.easydblab.commands.spark.SparkStatus
import com.rustyrazorblade.easydblab.commands.spark.SparkSubmit
import org.koin.dsl.module

/**
 * Koin module for registering all PicoCLI commands.
 * Commands are registered as factories to provide fresh instances per request.
 */
val commandsModule =
    module {
        // Top-level commands
        factory { BuildBaseImage() }
        factory { BuildCassandraImage() }
        factory { BuildImage() }
        factory { Clean() }
        factory { ConfigureAWS() }
        factory { ConfigureAxonOps() }
        factory { Exec() }
        factory { Hosts() }
        factory { Init() }
        factory { Ip() }
        factory { PruneAMIs() }
        factory { Repl() }
        factory { Server() }
        factory { SetupInstance() }
        factory { SetupProfile() }
        factory { ShowIamPolicies() }
        factory { Status() }
        factory { Up() }
        factory { UploadAuthorizedKeys() }
        factory { Version() }

        // AWS subcommands
        factory { Vpcs() }

        // Cassandra subcommands
        factory { Down() }
        factory { DownloadConfig() }
        factory { ListVersions() }
        factory { Restart() }
        factory { Start() }
        factory { Stop() }
        factory { UpdateConfig() }
        factory { UseCassandra() }
        factory { WriteConfig() }

        // Stress subcommands
        factory { StressFields() }
        factory { StressInfo() }
        factory { StressList() }
        factory { StressLogs() }
        factory { StressStart() }
        factory { StressStatus() }
        factory { StressStop() }

        // Dashboards subcommands
        factory { DashboardsGenerate() }
        factory { DashboardsUpload() }

        // ClickHouse subcommands
        factory { ClickHouseInit() }
        factory { ClickHouseStart() }
        factory { ClickHouseStatus() }
        factory { ClickHouseStop() }

        // K8s subcommands
        factory { K8Apply() }

        // OpenSearch subcommands
        factory { OpenSearchStart() }
        factory { OpenSearchStatus() }
        factory { OpenSearchStop() }

        // Spark subcommands
        factory { SparkJobs() }
        factory { SparkLogs() }
        factory { SparkStatus() }
        factory { SparkSubmit() }
    }
