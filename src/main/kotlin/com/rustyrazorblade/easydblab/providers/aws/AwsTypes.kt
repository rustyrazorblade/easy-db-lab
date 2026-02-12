package com.rustyrazorblade.easydblab.providers.aws

/*
 * Type aliases for AWS resource identifiers and configuration values.
 *
 * These type aliases provide semantic meaning to String values used in AWS operations,
 * improving type safety and code clarity.
 */

/**
 * AWS VPC identifier (e.g., "vpc-123abc456def")
 */
typealias VpcId = String

/**
 * AWS Subnet identifier (e.g., "subnet-123abc456def")
 */
typealias SubnetId = String

/**
 * AWS Internet Gateway identifier (e.g., "igw-123abc456def")
 */
typealias InternetGatewayId = String

/**
 * AWS Security Group identifier (e.g., "sg-123abc456def")
 */
typealias SecurityGroupId = String

/**
 * AWS Route Table identifier (e.g., "rtb-123abc456def")
 */
typealias RouteTableId = String

/**
 * CIDR block notation (e.g., "10.0.0.0/16" or "0.0.0.0/0")
 */
typealias Cidr = String

/**
 * Resource name used for AWS Name tags
 */
typealias ResourceName = String

/**
 * Resource description text
 */
typealias ResourceDescription = String

/**
 * AWS EC2 instance identifier (e.g., "i-123abc456def")
 */
typealias InstanceId = String

/**
 * AWS EMR cluster identifier (e.g., "j-123ABC456DEF")
 */
typealias ClusterId = String

/**
 * AWS NAT Gateway identifier (e.g., "nat-123abc456def")
 */
typealias NatGatewayId = String

/**
 * AWS Network Interface identifier (e.g., "eni-123abc456def")
 */
typealias NetworkInterfaceId = String
