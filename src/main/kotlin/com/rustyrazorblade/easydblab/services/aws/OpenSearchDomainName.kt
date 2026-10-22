package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants

/**
 * The one rule that derives an OpenSearch domain name from a cluster name.
 *
 * Both `opensearch start` and cluster provisioning use it, so they never name the same cluster's
 * domain differently. A domain name holds only `a-z`, `0-9` and `-` and is at most
 * [Constants.OpenSearch.DOMAIN_NAME_MAX_LENGTH] characters, while a cluster name may hold `_` and
 * run to 40 characters; so the rule converts every other character to `-` and trims the base, never
 * the `-os` suffix.
 */
object OpenSearchDomainName {
    private const val SUFFIX = "-os"
    private val NOT_ALLOWED = Regex("[^a-z0-9-]")

    /** The domain name of the cluster named [clusterName]. */
    fun of(clusterName: String): String {
        val base =
            clusterName
                .lowercase()
                .replace(NOT_ALLOWED, "-")
                .take(Constants.OpenSearch.DOMAIN_NAME_MAX_LENGTH - SUFFIX.length)
                .trimEnd('-')
        return "$base$SUFFIX"
    }
}
