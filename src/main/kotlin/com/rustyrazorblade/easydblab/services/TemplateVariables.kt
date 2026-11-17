package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.KitStorage
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.configuration.agents.JavaAgent
import com.rustyrazorblade.easydblab.configuration.otel.OtelEndpoints
import com.rustyrazorblade.easydblab.profiling.pyroscopeIngestBaseUrl
import java.io.File

/**
 * Standard template variable contract for kit scaffold generation.
 *
 * All variables map to `__VARIABLE_NAME__` placeholders in template files.
 * The variable set is public and stable — kit templates depend on it.
 *
 * `KIT_STORAGE_CLASS` and `KIT_STORAGE_SIZE` come from the environment's [KitStorage]: a kit's claims
 * name the class, and a kit's size arg defaults to `${KIT_STORAGE_SIZE}`.
 *
 * The role variables (`DB_NODE_SELECTOR`, `APP_NODE_SELECTOR`, `DB_AFFINITY`, `*_NODE_LABELS`, `DB_NODE_COUNT`,
 * `APP_NODE_COUNT`) come from [placement]. Kit installs and kit processes pass one built by
 * [RoleNodes] from the Kubernetes API; the default counts the hosts `state.json` records.
 *
 * `CLIENT_NETWORKS_JSON` (a JSON list of CIDRs) and `CLIENT_NETWORKS_HBA_JSON` (one `pg_hba` trust
 * line per CIDR) come from [clientNetworks].
 */
data class TemplateVariables(
    val clusterName: String,
    val bucketName: String,
    val accountBucket: String,
    val region: String,
    val placement: KitPlacementVariables,
    val clientNetworks: ClientNetworks,
    val controlHostPublic: String,
    val controlHostPrivate: String,
    val kitStorageClass: String,
    val kitStorageSize: String,
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
            "CONTROL_HOST" to controlHostPublic,
            "CONTROL_HOST_PUBLIC" to controlHostPublic,
            "CONTROL_HOST_PRIVATE" to controlHostPrivate,
            "KIT_STORAGE_CLASS" to kitStorageClass,
            "KIT_STORAGE_SIZE" to kitStorageSize,
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
            // The core OTel collector's in-cluster Service addresses (`.svc` form), so a kit's pods
            // and scripts push telemetry to their own node's core pod instead of a node IP.
            "OTEL_COLLECTOR_HOST" to OtelEndpoints.serviceHost(),
            "OTEL_OTLP_HTTP_ENDPOINT" to OtelEndpoints.otlpHttpEndpoint(),
            "OTEL_OTLP_GRPC_ENDPOINT" to OtelEndpoints.otlpGrpcEndpoint(),
            // The Java agent images init containers copy agents from: one source for kit templates
            // and Kotlin (JavaAgent), pinned to the versions the AMI ships.
            "OTEL_AGENT_IMAGE" to JavaAgent.OTEL.image,
            "PYROSCOPE_AGENT_IMAGE" to JavaAgent.PYROSCOPE.image,
        ) +
            // Role selectors and pools: where each role's pods go and how many nodes it has.
            placement.toMap() +
            // The networks a passwordless database kit accepts clients from.
            mapOf(
                "CLIENT_NETWORKS_JSON" to clientNetworks.toJson(),
                "CLIENT_NETWORKS_HBA_JSON" to clientNetworks.toHbaJson(),
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
                "KIT_STORAGE_CLASS",
                "KIT_STORAGE_SIZE",
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
                "OTEL_COLLECTOR_HOST",
                "OTEL_OTLP_HTTP_ENDPOINT",
                "OTEL_OTLP_GRPC_ENDPOINT",
                "OTEL_AGENT_IMAGE",
                "PYROSCOPE_AGENT_IMAGE",
                "DB_NODE_SELECTOR",
                "APP_NODE_SELECTOR",
                "DB_NODE_LABELS",
                "APP_NODE_LABELS",
                "CONTROL_NODE_LABELS",
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
            placement: KitPlacementVariables = KitPlacementVariables.fromRecordedHosts(state),
        ): TemplateVariables {
            val controlHost = state.getControlHost()
            val dbNodes = state.hosts[ServerType.Cassandra] ?: emptyList()
            val appNodes = state.hosts[ServerType.Stress] ?: emptyList()
            val region = state.initConfig?.region.orEmpty()
            val kitStorage = KitStorage.from(state)
            return TemplateVariables(
                clusterName = state.name,
                bucketName = state.dataBucket.ifBlank { state.s3Bucket.orEmpty() },
                accountBucket = state.s3Bucket.orEmpty(),
                region = region,
                placement = placement,
                clientNetworks = ClientNetworks.from(state),
                controlHostPublic = controlHost?.publicIp.orEmpty(),
                controlHostPrivate = controlHost?.privateIp.orEmpty(),
                // No kit name, no kit: a blank class rather than the class of a kit named "".
                kitStorageClass = if (kitName.isBlank()) "" else kitStorage.storageClassFor(kitName),
                kitStorageSize = kitStorage.defaultSize.toString(),
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
                // Where a kit's Pyroscope agent ships profiles: the Pyroscope Service, or the external
                // stack on a redirect cluster. Every write carries the cluster's tenant.
                pyroscopeUrl = pyroscopeIngestBaseUrl(state.initConfig?.telemetryRedirect),
                tenant = state.tenant(),
            )
        }
    }
}
