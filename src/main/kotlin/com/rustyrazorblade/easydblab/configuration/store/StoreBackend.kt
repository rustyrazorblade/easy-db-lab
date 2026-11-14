package com.rustyrazorblade.easydblab.configuration.store

import com.rustyrazorblade.easydblab.configuration.ClusterState

/**
 * The object store a cluster keeps its data in, described in storage-neutral terms.
 *
 * Everything that has to differ by store — the object-store block of each backend's
 * configuration ([StoreBlockRenderer]), the `store_*` keys of `cluster-config`, the store prefix
 * in front of every key — is derived from this one value, so a second store plugs in by adding a
 * case here, an `ObjectStore` adapter and one fragment per backend. It holds plain strings and
 * imports nothing from the AWS SDK, so it sits outside the provider boundary.
 *
 * Extension points owned by #1035 (per-environment observability storage), deliberately absent
 * until then:
 * - a volume case, for a filesystem or PVC store with no object API;
 * - a credential source on the object-store case (a Secret, IRSA or EKS Pod Identity, and the
 *   separate writer and compactor identities). Today every client uses the node's instance role.
 *
 * @property bucket The bucket every key lives in.
 * @property prefix The store prefix in front of every key; empty for none. Every case checks it
 *   with [StorePrefix.requireValid] when it is built.
 */
sealed interface StoreBackend {
    val bucket: String
    val prefix: String

    companion object {
        /**
         * The store prefix of a path that belongs to no one cluster: profile setup, the AMI
         * builder and the `observability compactor` commands, which run outside a workspace.
         */
        const val NO_PREFIX = ""

        /**
         * The store prefix of [clusterState], the prefix its [from] backend carries. It reads
         * neither the bucket nor the region, so it can be read before `up` sets the bucket. No
         * cluster records a prefix yet (#1035 adds the setting), so every cluster has none.
         */
        fun prefixOf(clusterState: ClusterState): String = NO_PREFIX

        /**
         * The store of [clusterState]. Every cluster stores on S3 today; this is the one place
         * that knows it.
         *
         * @param defaultRegion the region used when the cluster state records none.
         * @throws IllegalStateException when the cluster has no account bucket yet: `up` sets it, and
         *   a blank bucket would render configs that write nowhere.
         */
        fun from(
            clusterState: ClusterState,
            defaultRegion: String,
        ): StoreBackend =
            S3Backend(
                bucket =
                    checkNotNull(clusterState.s3Bucket) {
                        "S3 bucket not configured for cluster '${clusterState.name}'. Run 'easy-db-lab up' first."
                    },
                prefix = prefixOf(clusterState),
                region = clusterState.initConfig?.region ?: defaultRegion,
            )
    }
}

/**
 * Amazon S3, reached at its regional endpoint.
 *
 * Built through [invoke], which checks the prefix and stores it without the slashes around it,
 * so two backends with the same store are equal however their prefix was written. `copy()` is
 * private like the constructor, so no copy skips the check.
 *
 * @property region The bucket's region.
 * @property endpoint The host the backends talk to.
 */
@ConsistentCopyVisibility
data class S3Backend private constructor(
    override val bucket: String,
    override val prefix: String,
    val region: String,
    val endpoint: String,
) : StoreBackend {
    companion object {
        /** The S3 store of [bucket] in [region], under [prefix] (checked by [StorePrefix.requireValid]). */
        operator fun invoke(
            bucket: String,
            prefix: String = StoreBackend.NO_PREFIX,
            region: String,
            endpoint: String = "s3.$region.amazonaws.com",
        ): S3Backend = S3Backend(bucket, StorePrefix.requireValid(prefix), region, endpoint)
    }
}
