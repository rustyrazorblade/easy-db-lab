package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
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
        )

    companion object {
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
            )
        }
    }
}
