package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.ServerType
import java.io.File

/**
 * Standard template variable contract for workload scaffold generation.
 *
 * All variables map to `__VARIABLE_NAME__` placeholders in template files.
 * The variable set is public and stable — workload templates depend on it.
 */
data class TemplateVariables(
    val clusterName: String,
    val bucketName: String,
    val region: String,
    val dbNodeCount: Int,
    val appNodeCount: Int,
    val controlHostPublic: String,
    val controlHostPrivate: String,
    val storageClassWfc: String,
    val workloadName: String,
    val storageSize: String,
    val kubeconfig: String,
    val dbNodeIps: String,
) {
    fun toMap(): Map<String, String> =
        mapOf(
            "CLUSTER_NAME" to clusterName,
            "BUCKET_NAME" to bucketName,
            "REGION" to region,
            "DB_NODE_COUNT" to dbNodeCount.toString(),
            "APP_NODE_COUNT" to appNodeCount.toString(),
            "CONTROL_HOST" to controlHostPublic,
            "CONTROL_HOST_PUBLIC" to controlHostPublic,
            "CONTROL_HOST_PRIVATE" to controlHostPrivate,
            "STORAGE_CLASS_WFC" to storageClassWfc,
            "WORKLOAD_NAME" to workloadName,
            "STORAGE_SIZE" to storageSize,
            "KUBECONFIG" to kubeconfig,
            "EASY_DB_LAB_EXEC" to resolveEasyDbLabExec(),
            "DB_NODE_IPS" to dbNodeIps,
        )

    companion object {
        fun from(
            state: ClusterState,
            workloadName: String,
            storageSize: String,
        ): TemplateVariables {
            val controlHost = state.hosts[ServerType.Control]?.firstOrNull()
            val dbNodes = state.hosts[ServerType.Cassandra] ?: emptyList()
            val appNodeCount = state.hosts[ServerType.Stress]?.size ?: 0
            val region = state.initConfig?.region ?: ""
            return TemplateVariables(
                clusterName = state.name,
                bucketName = state.dataBucket.ifBlank { state.s3Bucket ?: "" },
                region = region,
                dbNodeCount = dbNodes.size,
                appNodeCount = appNodeCount,
                controlHostPublic = controlHost?.publicIp ?: "",
                controlHostPrivate = controlHost?.privateIp ?: "",
                storageClassWfc = Constants.K8s.LOCAL_STORAGE_WFC_CLASS,
                workloadName = workloadName,
                storageSize = storageSize,
                kubeconfig = Constants.K3s.LOCAL_KUBECONFIG,
                dbNodeIps = dbNodes.joinToString(",") { it.privateIp },
            )
        }
    }
}

fun resolveEasyDbLabExec(): String {
    val fixed = File("/usr/local/bin/easy-db-lab")
    return if (fixed.exists()) {
        fixed.absolutePath
    } else {
        val appHome = System.getProperty("easydblab.apphome") ?: ""
        "$appHome/bin/easy-db-lab"
    }
}
