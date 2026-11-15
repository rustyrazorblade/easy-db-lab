package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import java.io.File

/**
 * Standard template variable contract for kit scaffold generation.
 *
 * All variables map to `__VARIABLE_NAME__` placeholders in template files.
 * The variable set is public and stable — kit templates depend on it.
 */
data class TemplateVariables(
    val clusterName: String,
    val bucketName: String,
    val accountBucket: String,
    val region: String,
    val dbNodeCount: Int,
    val appNodeCount: Int,
    val controlHostPublic: String,
    val controlHostPrivate: String,
    val storageClassWfc: String,
    val kitName: String,
    val storageSize: String,
    val kubeconfig: String,
    val dbNodeIps: String,
    val appNodeIps: String,
    val runningKits: String,
    val vpcCidr: String,
    val opensearchEndpoint: String,
    val pyroscopeUrl: String,
    val tenant: String,
) {
    fun toMap(): Map<String, String> =
        mapOf(
            "CLUSTER_NAME" to clusterName,
            "BUCKET_NAME" to bucketName,
            "ACCOUNT_BUCKET" to accountBucket,
            "REGION" to region,
            "DB_NODE_COUNT" to dbNodeCount.toString(),
            "APP_NODE_COUNT" to appNodeCount.toString(),
            "CONTROL_HOST" to controlHostPublic,
            "CONTROL_HOST_PUBLIC" to controlHostPublic,
            "CONTROL_HOST_PRIVATE" to controlHostPrivate,
            "STORAGE_CLASS_WFC" to storageClassWfc,
            "KIT_NAME" to kitName,
            "STORAGE_SIZE" to storageSize,
            "KUBECONFIG" to kubeconfig,
            "EASY_DB_LAB_EXEC" to resolveEasyDbLabExec(),
            "DB_NODE_IPS" to dbNodeIps,
            "APP_NODE_IPS" to appNodeIps,
            "RUNNING_KITS" to runningKits,
            "VPC_CIDR" to vpcCidr,
            "OPENSEARCH_ENDPOINT" to opensearchEndpoint,
            "PYROSCOPE_URL" to pyroscopeUrl,
            "TENANT" to tenant,
        )

    companion object {
        /**
         * Every variable [toMap] defines, plus the ones the kit runner adds after it (`BACKUP_NAME`).
         * A command arg may not use one of these names: the cluster value would silently replace
         * the argument. A test keeps this set equal to the keys of [toMap].
         */
        val RESERVED_NAMES: Set<String> =
            setOf(
                "CLUSTER_NAME",
                "BUCKET_NAME",
                "ACCOUNT_BUCKET",
                "REGION",
                "DB_NODE_COUNT",
                "APP_NODE_COUNT",
                "CONTROL_HOST",
                "CONTROL_HOST_PUBLIC",
                "CONTROL_HOST_PRIVATE",
                "STORAGE_CLASS_WFC",
                "KIT_NAME",
                "STORAGE_SIZE",
                "KUBECONFIG",
                "EASY_DB_LAB_EXEC",
                "DB_NODE_IPS",
                "APP_NODE_IPS",
                "RUNNING_KITS",
                "VPC_CIDR",
                "OPENSEARCH_ENDPOINT",
                "PYROSCOPE_URL",
                "TENANT",
                "BACKUP_NAME",
            )

        internal fun resolveEasyDbLabExec(fixedPath: File = File("/usr/local/bin/easy-db-lab")): String =
            if (fixedPath.exists()) {
                fixedPath.absolutePath
            } else {
                val appHome = System.getProperty("easydblab.apphome")
                if (!appHome.isNullOrBlank()) "$appHome/bin/easy-db-lab" else "easy-db-lab"
            }

        fun from(
            state: ClusterState,
            kitName: String,
            storageSize: String,
        ): TemplateVariables {
            val controlHost = state.getControlHost()
            val dbNodes = state.hosts[ServerType.Cassandra] ?: emptyList()
            val appNodes = state.hosts[ServerType.Stress] ?: emptyList()
            val region = state.initConfig?.region.orEmpty()
            return TemplateVariables(
                clusterName = state.name,
                bucketName = state.dataBucket.ifBlank { state.s3Bucket.orEmpty() },
                accountBucket = state.s3Bucket.orEmpty(),
                region = region,
                dbNodeCount = dbNodes.size,
                appNodeCount = appNodes.size,
                controlHostPublic = controlHost?.publicIp.orEmpty(),
                controlHostPrivate = controlHost?.privateIp.orEmpty(),
                storageClassWfc = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                kitName = kitName,
                storageSize = storageSize,
                kubeconfig = Constants.K3s.LOCAL_KUBECONFIG,
                dbNodeIps = dbNodes.joinToString(",") { it.privateIp },
                appNodeIps = appNodes.joinToString(",") { it.privateIp },
                runningKits = state.runningKits.joinToString(","),
                // cidr is null until 'up' resolves and persists it; DEFAULT_CIDR is a safe
                // fallback because templates using VPC_CIDR only execute post-up
                vpcCidr = state.initConfig?.cidr ?: Constants.Vpc.DEFAULT_CIDR,
                opensearchEndpoint = state.openSearchDomain?.endpoint.orEmpty(),
                // Where a kit's Pyroscope agent ships profiles: the control node's server, or the
                // external stack on a redirect cluster. Every write carries the cluster's tenant.
                pyroscopeUrl = pyroscopeIngestBaseUrl(controlHost?.privateIp.orEmpty(), state.initConfig?.telemetryRedirect),
                tenant = state.tenant(),
            )
        }
    }
}
