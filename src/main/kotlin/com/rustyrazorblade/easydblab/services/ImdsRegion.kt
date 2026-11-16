package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.kernel.CommandFailedException

/**
 * Reads the AWS region of the instance a command runs on from the instance metadata service.
 *
 * Every easy-db-lab instance launches with IMDSv2 required (see `RunInstancesRequests`), so a plain
 * GET of the identity document is answered with 401. [COMMAND] fetches a session token first and
 * sends it with the read. [parse] fails fast on anything that is not a region: the earlier
 * unauthenticated read produced the string `null`, which was written into `stress.env` as the
 * stress default datacenter without anyone noticing.
 */
object ImdsRegion {
    private const val TOKEN_TTL_SECONDS = 60
    private val REGION_PATTERN = Regex("^[a-z]{2}(-[a-z]+)+-\\d+$")

    /** Shell command that prints the region of the host it runs on. */
    const val COMMAND =
        "TOKEN=\$(curl -sf -X PUT http://169.254.169.254/latest/api/token " +
            "-H 'X-aws-ec2-metadata-token-ttl-seconds: $TOKEN_TTL_SECONDS') && " +
            "curl -sf -H \"X-aws-ec2-metadata-token: \$TOKEN\" " +
            "http://169.254.169.254/latest/dynamic/instance-identity/document | yq .region"

    /** Returns the region in [output], or throws when [output] is not a region name. */
    fun parse(output: String): String {
        val region = output.trim()
        if (!REGION_PATTERN.matches(region)) {
            throw CommandFailedException("Could not read the AWS region from instance metadata, got: '$region'")
        }
        return region
    }
}
