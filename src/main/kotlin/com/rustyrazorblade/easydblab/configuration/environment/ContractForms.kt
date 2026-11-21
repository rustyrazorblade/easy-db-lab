package com.rustyrazorblade.easydblab.configuration.environment

/**
 * How cluster tooling (`helm`, `kubectl`, `cilium`) runs. Koin maps each form to an implementation,
 * so this package names no service.
 */
enum class ToolingForm {
    /** Over SSH on the control node, where the tools are baked into the image. */
    Ssh,

    /** On this machine, through the workspace `bin/` wrappers. */
    Local,
}

/** How easy-db-lab runs a command on a cluster node (#1039 implements the non-SSH forms). */
enum class NodeAccessForm {
    /** SSH to the node. */
    Ssh,

    /** `docker exec` into the node's container. */
    ContainerExec,

    /** The nodes are not easy-db-lab's to run commands on. */
    None,
}

/** Where the observability backends keep their data. Nothing renders [Volume] yet; #1035 adds it. */
enum class StoreKind {
    /** An object store bucket. */
    ObjectStore,

    /** A volume in the cluster. */
    Volume,
}

/** Where the networks a passwordless database kit accepts clients from come from (`ClientNetworks`). */
enum class ClientNetworkOrigin {
    /** The networks easy-db-lab built: the VPC CIDR and the K3s cluster CIDR, known without asking the cluster. */
    BuiltNetworks,

    /**
     * Only what `state.json` records: the networks read from the cluster's nodes, or the `--client-network`
     * values. The node network is not known until the cluster exists.
     */
    Recorded,
}
