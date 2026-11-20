package com.rustyrazorblade.easydblab.commands.mixins

import com.rustyrazorblade.easydblab.Constants
import picocli.CommandLine.Option

/**
 * PicoCLI mixin for OpenSearch domain initialization parameters.
 *
 * Provides reusable options for configuring an AWS-managed OpenSearch domain
 * including instance type, count, version, and EBS volume size.
 *
 * OpenSearch domains are created during 'up' if enabled, similar to Spark EMR clusters.
 */
class OpenSearchInitMixin {
    @Option(
        names = ["--opensearch.enable"],
        description = ["Enable AWS OpenSearch domain"],
    )
    var enable: Boolean = false

    @Option(
        names = ["--opensearch.instance.type"],
        description = ["OpenSearch instance type (e.g., r8g.xlarge.search, m8g.large.search)"],
    )
    var instanceType: String = Constants.OpenSearch.DEFAULT_INSTANCE_TYPE

    @Option(
        names = ["--opensearch.instance.count"],
        description = ["Number of OpenSearch data nodes"],
    )
    var instanceCount: Int = Constants.OpenSearch.DEFAULT_INSTANCE_COUNT

    @Option(
        names = ["--opensearch.version"],
        description = ["OpenSearch engine version (e.g., 2.11, 2.9)"],
    )
    var version: String = Constants.OpenSearch.DEFAULT_VERSION

    @Option(
        names = ["--opensearch.ebs.size"],
        description = ["EBS volume size in GB per node"],
    )
    var ebsSize: Int = Constants.OpenSearch.DEFAULT_EBS_SIZE_GB
}
