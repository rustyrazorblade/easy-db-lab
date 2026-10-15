package com.rustyrazorblade.easydblab.localstack

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet

/**
 * What a local observability stack is built from: where its data comes from, which tenants Grafana
 * gets datasources for, and the localhost ports the browser reaches it on.
 *
 * @property mode where the data comes from: the live shared store, or an exported copy on disk.
 * @property tenants every tenant in the store; the home tenant is added if it is missing.
 * @property grafanaPort Grafana's port on `127.0.0.1`.
 * @property documentsPort the documents web server's port on `127.0.0.1`.
 */
data class LocalStackSpec(
    val mode: Mode,
    val tenants: List<String>,
    val grafanaPort: Int = Constants.LocalStack.DEFAULT_GRAFANA_PORT,
    val documentsPort: Int = Constants.LocalStack.DEFAULT_DOCUMENTS_PORT,
) {
    /** The tenants Grafana's datasources are built from, with [Mode.home] as the stable datasources' tenant. */
    val tenantSet: TenantSet get() = TenantSet.of(mode.home, tenants)

    /** Where a local stack's data comes from. */
    sealed interface Mode {
        /** The tenant the stable `mimir`, `loki` and `tempo` datasources read. */
        val home: String

        /** The name the manifest records the mode under. */
        val manifestName: String

        /**
         * The live shared store in S3, read with the operator's own AWS profile through a read gate
         * that refuses every write.
         *
         * @property bucket the account bucket.
         * @property region the bucket's region, which the signer signs for.
         * @property awsProfile the profile in `~/.aws` the signer reads the bucket with.
         */
        data class Live(
            val bucket: String,
            val region: String,
            val awsProfile: String,
            override val home: String,
        ) : Mode {
            override val manifestName: String get() = "live"
        }

        /**
         * An exported copy of a tenant's data, read from disk with no network and no credentials.
         *
         * @property dataDir the directory, relative to the stack, that holds the copy.
         */
        data class Offline(
            val dataDir: String,
            override val home: String,
        ) : Mode {
            override val manifestName: String get() = "offline"
        }
    }
}
