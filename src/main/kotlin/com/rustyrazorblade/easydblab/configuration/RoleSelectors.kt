package com.rustyrazorblade.easydblab.configuration

import kotlinx.serialization.json.Json

/**
 * The labels a node must carry to run one role's pods. An empty selector constrains nothing: the
 * pods run on any schedulable node.
 *
 * It renders in the two forms its consumers read: [toJson] for a manifest's `nodeSelector`
 * (`__DB_NODE_SELECTOR__`, a kubectl `--overrides`) and [toLabelSelector] for `kubectl -l` and the
 * Kubernetes API. Both list the keys sorted, so a rendered manifest is stable.
 *
 * A data class, not a value class: it is recorded in `state.json`, which Jackson writes.
 *
 * @property labels the label keys and values a node must carry.
 */
data class NodeSelector(
    val labels: Map<String, String> = emptyMap(),
) {
    /** The selector as a JSON object, `{}` when unconstrained. */
    fun toJson(): String = Json.encodeToString(labels.toSortedMap() as Map<String, String>)

    /** The selector as `key=value[,key=value]`, empty when unconstrained. */
    fun toLabelSelector(): String = labels.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }

    companion object {
        /** No constraint. */
        val NONE = NodeSelector()

        /** The `type=<role>` label easy-db-lab puts on the nodes it builds. */
        fun ownedNode(role: ServerType): NodeSelector = NodeSelector(mapOf(TYPE_LABEL to role.serverType))

        private const val TYPE_LABEL = "type"
    }
}

/**
 * Where each role's pods run, as a value the environment resolves once from cluster state. Kits,
 * the kit shell library and stress jobs place their pods by it instead of naming `type=db` or
 * `type=app`, so one kit runs on a cluster easy-db-lab built and on one it did not.
 *
 * @property db the database role's selector.
 * @property app the client (stress, benchmark) role's selector.
 * @property control the control role's selector, which picks the core collector pod for gauges.
 */
data class RoleSelectors(
    val db: NodeSelector = NodeSelector.NONE,
    val app: NodeSelector = NodeSelector.NONE,
    val control: NodeSelector = NodeSelector.NONE,
) {
    /** The selector of [role]. */
    fun of(role: ServerType): NodeSelector =
        when (role) {
            ServerType.Cassandra -> db
            ServerType.Stress -> app
            ServerType.Control -> control
        }

    /** These selectors with each role in [mappings] replaced by its mapped selector. */
    fun withMappings(mappings: Map<ServerType, NodeSelector>): RoleSelectors =
        RoleSelectors(
            db = mappings[ServerType.Cassandra] ?: db,
            app = mappings[ServerType.Stress] ?: app,
            control = mappings[ServerType.Control] ?: control,
        )

    companion object {
        /** The option a user maps a role with. */
        const val OPTION = "--role-nodes"

        private const val EXPECTED_FORM = "<role>=<key=value>[,<key=value>...]"

        /** The nodes easy-db-lab builds (AWS and local): one `type=<role>` label per node. */
        val OWNED_NODES =
            RoleSelectors(
                db = NodeSelector.ownedNode(ServerType.Cassandra),
                app = NodeSelector.ownedNode(ServerType.Stress),
                control = NodeSelector.ownedNode(ServerType.Control),
            )

        /** A cluster the user brings with no mapping: every role runs on any node. */
        val ANYWHERE = RoleSelectors()

        /**
         * The selectors [state] records. A state that records none (written before they existed, or
         * by an environment easy-db-lab builds) reads as [OWNED_NODES].
         */
        fun from(state: ClusterState): RoleSelectors = state.roleSelectors ?: OWNED_NODES

        /**
         * Parses `--role-nodes` values, each `<role>=<key=value>[,<key=value>...]` with `<role>` one
         * of `db`, `app` or `control`.
         *
         * @throws IllegalArgumentException on a malformed value, an unknown role, or a role mapped
         *   twice; the message names the value and the expected form.
         */
        fun parse(values: List<String>): Map<ServerType, NodeSelector> {
            val mappings = linkedMapOf<ServerType, NodeSelector>()
            for (value in values) {
                val (role, selector) = parseOne(value)
                require(mappings.put(role, selector) == null) {
                    "Role '${role.serverType}' is mapped twice in $OPTION; give it once."
                }
            }
            return mappings
        }

        private fun parseOne(value: String): Pair<ServerType, NodeSelector> {
            val roleName = value.substringBefore("=")
            val role = ServerType.entries.firstOrNull { it.serverType == roleName }
            val pairs = value.substringAfter("=", "").split(",")
            val labels = pairs.map(::parseLabel)
            require(role != null && "=" in value && labels.all { it != null }) {
                "Invalid $OPTION '$value': expected $EXPECTED_FORM, with <role> one of " +
                    "${ServerType.entries.joinToString(", ") { it.serverType }}."
            }
            return role to NodeSelector(labels.filterNotNull().toMap())
        }

        private fun parseLabel(pair: String): Pair<String, String>? {
            val parts = pair.split("=")
            return parts.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0].trim() to it[1].trim() }
        }
    }
}
