package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.InfrastructureConfig
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AvailabilityZone
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesRequest
import software.amazon.awssdk.services.ec2.model.Filter

/**
 * The account compactor's VPC, `easy-db-lab-compactor`, in the account bucket's region: one public
 * subnet in a zone where Fargate runs, an internet gateway and a security group with no ingress. It
 * is found or created by the same path as the packer VPC, with an EC2 client for that region.
 */
class CompactorNetwork(
    private val regionalClients: RegionalClients,
    private val emrService: EMRService,
    private val openSearchService: OpenSearchService,
    private val eventBus: EventBus,
) {
    companion object {
        /** The first available zone, by name, that is not one Fargate leaves out. */
        fun fargateZone(zones: List<AvailabilityZone>): String =
            checkNotNull(
                zones
                    .filterNot { it.zoneId() in Constants.Compactor.NON_FARGATE_ZONE_IDS }
                    .minByOrNull { it.zoneName() },
            ) { "No availability zone where Fargate runs among ${zones.map { it.zoneName() }}" }.zoneName()
    }

    /** Finds or creates the compactor's VPC in [region]. */
    fun ensure(region: String): VpcInfrastructure =
        regionalClients.ec2(region).use { ec2 ->
            AwsInfrastructureService(EC2VpcService(ec2, eventBus), emrService, openSearchService, eventBus)
                .ensureReusableInfrastructure(InfrastructureConfig.forCompactor(fargateZone(availableZones(ec2))))
        }

    private fun availableZones(ec2: Ec2Client): List<AvailabilityZone> =
        ec2
            .describeAvailabilityZones(
                DescribeAvailabilityZonesRequest
                    .builder()
                    .filters(
                        Filter
                            .builder()
                            .name("state")
                            .values("available")
                            .build(),
                        Filter
                            .builder()
                            .name("zone-type")
                            .values("availability-zone")
                            .build(),
                    ).build(),
            ).availabilityZones()
}
