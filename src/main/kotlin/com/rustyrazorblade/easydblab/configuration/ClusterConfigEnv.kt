package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.services.ClusterConfigData
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder

/**
 * An env var read from one [key] of the cluster-config ConfigMap ([ClusterConfigData]).
 *
 * Backends that expand their config file from the environment read the bucket, prefixes, cluster
 * name and tenant this way, so the value lives in one ConfigMap and the workload's config hash
 * covers it.
 */
fun clusterConfigEnv(
    name: String,
    key: String,
): EnvVar =
    EnvVarBuilder()
        .withName(name)
        .withNewValueFrom()
        .withNewConfigMapKeyRef()
        .withName(ClusterConfigData.NAME)
        .withKey(key)
        .endConfigMapKeyRef()
        .endValueFrom()
        .build()
