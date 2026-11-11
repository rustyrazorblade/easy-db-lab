package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.providers.aws.InstanceCreationConfig
import com.rustyrazorblade.easydblab.providers.aws.withAwsRetry
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo
import software.amazon.awssdk.services.ec2.model.LocationType
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest
import java.util.UUID

/**
 * [AccountCapacity] read from EC2 and Service Quotas. Every listing reads all its pages, and
 * every call runs under the standard AWS retry.
 */
class Ec2AccountCapacity(
    private val ec2Client: Ec2Client,
    private val serviceQuotasClient: ServiceQuotasClient,
) : AccountCapacity {
    override fun instanceTypes(instanceTypes: Collection<String>): Map<String, InstanceTypeFacts> {
        if (instanceTypes.isEmpty()) return emptyMap()
        val request =
            DescribeInstanceTypesRequest
                .builder()
                .filters(filter("instance-type", instanceTypes))
                .build()
        return withAwsRetry("describe-instance-types") {
            ec2Client
                .describeInstanceTypesPaginator(request)
                .instanceTypes()
                .associate { it.instanceTypeAsString() to facts(it) }
        }
    }

    private fun facts(info: InstanceTypeInfo): InstanceTypeFacts =
        InstanceTypeFacts(
            vcpus = info.vCpuInfo()?.defaultVCpus() ?: 0,
            maxNetworkInterfaces = info.networkInfo()?.maximumNetworkInterfaces() ?: 1,
            ipv4AddressesPerInterface = info.networkInfo()?.ipv4AddressesPerInterface() ?: 1,
        )

    override fun standardVcpuQuota(): Int = quota(Constants.Capacity.EC2_SERVICE_CODE, Constants.Capacity.STANDARD_VCPU_QUOTA_CODE)

    override fun standardVcpusInUse(): Int {
        val request =
            DescribeInstancesRequest
                .builder()
                .filters(filter("instance-state-name", listOf("pending", "running")))
                .build()
        val instances =
            withAwsRetry("describe-instances-in-use") {
                ec2Client
                    .describeInstancesPaginator(request)
                    .reservations()
                    .flatMap { it.instances() }
                    // Spot instances count against the Spot quota, not the On-Demand one.
                    .filter { it.instanceLifecycleAsString() == null && InstanceFamily.isStandard(it.instanceTypeAsString()) }
            }
        if (instances.isEmpty()) return 0
        val vcpus = instanceTypes(instances.map { it.instanceTypeAsString() }.toSet())
        return instances.sumOf { vcpus[it.instanceTypeAsString()]?.vcpus ?: 0 }
    }

    override fun zonesOffering(
        instanceType: String,
        zones: Collection<String>,
    ): Set<String> {
        val request =
            DescribeInstanceTypeOfferingsRequest
                .builder()
                .locationType(LocationType.AVAILABILITY_ZONE)
                .filters(filter("instance-type", listOf(instanceType)), filter("location", zones))
                .build()
        return withAwsRetry("describe-instance-type-offerings-$instanceType") {
            ec2Client
                .describeInstanceTypeOfferingsPaginator(request)
                .instanceTypeOfferings()
                .map { it.location() }
                .toSet()
        }
    }

    override fun networkInterfaceQuota(): Int = quota(Constants.Capacity.VPC_SERVICE_CODE, Constants.Capacity.NETWORK_INTERFACE_QUOTA_CODE)

    override fun networkInterfacesInUse(): Int =
        withAwsRetry("describe-network-interfaces") {
            ec2Client
                .describeNetworkInterfacesPaginator(DescribeNetworkInterfacesRequest.builder().build())
                .networkInterfaces()
                .count()
        }

    override fun freeAddressesByZone(vpcId: String): Map<String, Int> {
        val request =
            DescribeSubnetsRequest
                .builder()
                .filters(filter("vpc-id", listOf(vpcId)))
                .build()
        return withAwsRetry("describe-subnets-$vpcId") {
            ec2Client
                .describeSubnetsPaginator(request)
                .subnets()
                .groupBy { it.availabilityZone() }
                .mapValues { (_, subnets) -> subnets.sumOf { it.availableIpAddressCount() ?: 0 } }
        }
    }

    override fun dryRunLaunch(config: InstanceCreationConfig): LaunchDryRun {
        val request = RunInstancesRequests.build(config, config.subnetIds.first(), 1, UUID.randomUUID().toString(), dryRun = true)
        return try {
            ec2Client.runInstances(request)
            // A dry run always answers with an error; a success would mean an instance was launched.
            error("RunInstances with DryRun returned without DryRunOperation; check the instances of ${config.clusterName}.")
        } catch (e: Ec2Exception) {
            val code = e.awsErrorDetails()?.errorCode().orEmpty()
            if (code == DRY_RUN_OPERATION) {
                LaunchDryRun.Permitted
            } else {
                LaunchDryRun.Refused(code, e.awsErrorDetails()?.errorMessage() ?: e.message.orEmpty())
            }
        }
    }

    private fun quota(
        serviceCode: String,
        quotaCode: String,
    ): Int {
        val request =
            GetServiceQuotaRequest
                .builder()
                .serviceCode(serviceCode)
                .quotaCode(quotaCode)
                .build()
        return withAwsRetry("get-service-quota-$quotaCode") {
            serviceQuotasClient
                .getServiceQuota(request)
                .quota()
                .value()
                .toInt()
        }
    }

    private fun filter(
        name: String,
        values: Collection<String>,
    ): Filter =
        Filter
            .builder()
            .name(name)
            .values(values)
            .build()

    private companion object {
        const val DRY_RUN_OPERATION = "DryRunOperation"
    }
}
