package com.rustyrazorblade.easydblab.configuration

/** The name of an EC2 key pair. */
typealias AwsKeyName = String

/**
 * One IAM policy document as setup and `show-iam-policies` print it: its name and its JSON body,
 * with the account id already filled in.
 */
data class Policy(
    val name: String,
    val body: String,
)
