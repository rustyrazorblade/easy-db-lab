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
 * @property prefix The store prefix in front of every key; empty for none.
 */
sealed interface StoreBackend {
    val bucket: String
    val prefix: String

    companion object {
        /**
         * The store of [clusterState]. Every cluster stores on S3 today; this is the one place
         * that knows it.
         *
         * @param defaultRegion the region used when the cluster state records none.
         */
        fun from(
            clusterState: ClusterState,
            defaultRegion: String,
        ): StoreBackend =
            S3Backend(
                bucket = clusterState.s3Bucket.orEmpty(),
                region = clusterState.initConfig?.region ?: defaultRegion,
            )
    }
}

/**
 * Amazon S3, reached at its regional endpoint.
 *
 * @property region The bucket's region.
 * @property endpoint The host the backends talk to.
 */
data class S3Backend(
    override val bucket: String,
    override val prefix: String = "",
    val region: String,
    val endpoint: String = "s3.$region.amazonaws.com",
) : StoreBackend
