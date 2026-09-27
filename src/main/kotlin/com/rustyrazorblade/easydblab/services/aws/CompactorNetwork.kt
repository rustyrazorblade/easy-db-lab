package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.InfrastructureConfig
import com.rustyrazorblade.easydblab.providers.aws.RegionalClients
import com.rustyrazorblade.easydblab.providers.aws.VpcInfrastructure

/**
 * The account compactor's VPC, `easy-db-lab-compactor`, in the account bucket's region: one public
 * subnet, an internet gateway and a security group with no ingress. It is found or created by the
 * same path as the packer VPC, with an EC2 client for that region.
 */
class CompactorNetwork(
    private val regionalClients: RegionalClients,
    private val emrService: EMRService,
    private val openSearchService: OpenSearchService,
    private val eventBus: EventBus,
) {
    /** Finds or creates the compactor's VPC in [region]. */
    fun ensure(region: String): VpcInfrastructure =
        regionalClients.ec2(region).use { ec2 ->
            AwsInfrastructureService(EC2VpcService(ec2, eventBus), emrService, openSearchService, eventBus)
                .ensureReusableInfrastructure(InfrastructureConfig.forCompactor())
        }
}
