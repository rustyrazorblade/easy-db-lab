package com.rustyrazorblade.easydblab.configuration.environment

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.rustyrazorblade.easydblab.configuration.NodeCapabilities
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The execution environment a workspace targets, recorded once in `state.json` by `init`.
 *
 * The record names only the provisioner and that provisioner's own parameters. It never holds
 * capability data: what a consumer needs (the tooling form, the network mode, the placement, the
 * declared capabilities) is resolved from it by [ClusterContract.of], so a new environment is a
 * new variant and one new branch there, not a branch in every consumer.
 *
 * Each variant's type id is its name in `state.json` (the Jackson name, kept equal to the kotlinx
 * one). A state that records no environment predates the field and reads as [Ec2K3s].
 */
@Serializable
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = Environment.TYPE_PROPERTY)
@JsonSubTypes(
    JsonSubTypes.Type(Environment.Ec2K3s::class, name = Environment.EC2_K3S),
    JsonSubTypes.Type(Environment.K3sDocker::class, name = Environment.K3S_DOCKER),
    JsonSubTypes.Type(Environment.Kubeconfig::class, name = Environment.KUBECONFIG),
)
sealed interface Environment {
    /** How messages name this environment to the user; never used to decide anything. */
    @get:JsonIgnore
    val displayName: String

    /** The capabilities this environment provides when `init` recorded none. */
    @get:JsonIgnore
    val declaredCapabilities: NodeCapabilities

    /** EC2 nodes running K3s, provisioned by `up` in the operator's AWS account. */
    @Serializable
    @SerialName(EC2_K3S)
    @JsonTypeName(EC2_K3S)
    data object Ec2K3s : Environment {
        override val displayName: String get() = "EC2 + K3s"
        override val declaredCapabilities: NodeCapabilities get() = NodeCapabilities.AWS
    }

    /**
     * K3s in Docker on this machine.
     *
     * @property dataDir the absolute directory the observability data lives in; nothing in
     *   easy-db-lab deletes it.
     */
    @Serializable
    @SerialName(K3S_DOCKER)
    @JsonTypeName(K3S_DOCKER)
    data class K3sDocker(
        val dataDir: String,
    ) : Environment {
        override val displayName: String get() = "K3s in Docker"
        override val declaredCapabilities: NodeCapabilities get() = NodeCapabilities.LOCAL_DOCKER
    }

    /**
     * A Kubernetes cluster the user brings. Its capabilities are probed (#1096), so it declares none.
     *
     * @property source the kubeconfig the cluster was named by.
     */
    @Serializable
    @SerialName(KUBECONFIG)
    @JsonTypeName(KUBECONFIG)
    data class Kubeconfig(
        val source: String,
    ) : Environment {
        override val displayName: String get() = "your Kubernetes cluster"
        override val declaredCapabilities: NodeCapabilities get() = NodeCapabilities.NONE
    }

    companion object {
        /** The `state.json` property that holds a variant's type id. */
        const val TYPE_PROPERTY = "type"
        const val EC2_K3S = "ec2-k3s"
        const val K3S_DOCKER = "k3s-docker"
        const val KUBECONFIG = "kubeconfig"

        /** Every type id this easy-db-lab reads, in declaration order. */
        val TYPE_IDS: List<String> = listOf(EC2_K3S, K3S_DOCKER, KUBECONFIG)

        /**
         * The environment a plain `init` records, and the one a state that records none reads as. Code
         * outside this package names it as this, never as [Ec2K3s], so reading the EC2 object always
         * means a branch on the variant, which only [ClusterContract.of] takes.
         */
        val DEFAULT: Environment = Ec2K3s
    }
}
