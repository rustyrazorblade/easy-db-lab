package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.json.Json

/**
 * The labels a node must carry to run one role's pods. An empty selector constrains nothing: the
 * pods run on any schedulable node.
 *
 * It renders in the two forms its consumers read: [toJson] for a manifest's `nodeSelector`
 * (`__DB_NODE_SELECTOR__`, a kubectl `--overrides`) and [toLabelSelector] for `kubectl -l` and the
 * Kubernetes API. Both list the keys sorted, so a rendered manifest is stable.
 *
 * A data class, not a value class: it is recorded in `state.json`, which `ClusterStateManager`
 * still reads and writes with Jackson (deprecated here, but not yet migrated), and Jackson's
 * Kotlin module cannot map a value class property.
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

    /** The selector as people read it: [toLabelSelector], or `<any node>` when unconstrained. */
    fun describe(): String = toLabelSelector().ifEmpty { ANY_NODE }

    companion object {
        /** No constraint. */
        val NONE = NodeSelector()

        /** The `type=<role>` label easy-db-lab puts on the nodes it builds. */
        fun ownedNode(role: ServerType): NodeSelector = NodeSelector(mapOf(TYPE_LABEL to role.serverType))

        private const val TYPE_LABEL = "type"
        private const val ANY_NODE = "<any node>"
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

        private const val EXPECTED_FORM = "<role>=<key=value>[,<key=value>...] (or <role>= for any node)"

        /** The nodes easy-db-lab builds (AWS and local): one `type=<role>` label per node. */
        val OWNED_NODES =
            RoleSelectors(
                db = NodeSelector.ownedNode(ServerType.Cassandra),
                app = NodeSelector.ownedNode(ServerType.Stress),
                control = NodeSelector.ownedNode(ServerType.Control),
            )

        /** A cluster the user brings with no mapping: every role runs on any node. */
        val ANYWHERE = RoleSelectors()

        /** The selectors a new workspace starts from, before `--role-nodes`, for nodes of [origin]. */
        fun base(origin: NodeOrigin): RoleSelectors =
            when (origin) {
                NodeOrigin.OWNED -> OWNED_NODES
                NodeOrigin.BROUGHT -> ANYWHERE
            }

        /**
         * The selectors [state] records. A state that records none (written before they existed, or
         * by an environment easy-db-lab builds) reads as [OWNED_NODES].
         */
        fun from(state: ClusterState): RoleSelectors = state.roleSelectors ?: OWNED_NODES

        /**
         * Parses `--role-nodes` values, each `<role>=<key=value>[,<key=value>...]` with `<role>` one
         * of `db`, `app` or `control`. A pair's value may be empty (`key=`).
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
            val role = ServerType.entries.firstOrNull { it.serverType == value.substringBefore("=") }
            require(role != null && "=" in value) { malformed(value, pairNote = "") }
            val selector = value.substringAfter("=")
            // `<role>=` with nothing after it unmaps the role: its pods run on any node.
            val pairs = if (selector.isEmpty()) emptyList() else selector.split(",")
            val labels = pairs.map(::parseLabel)
            val badPair = pairs.zip(labels).firstOrNull { (_, label) -> label == null }?.first
            require(badPair == null) { malformed(value, pairNote = " (label '$badPair' is not one key=value)") }
            val parsed = labels.filterNotNull()
            parsed.forEach { (key, labelValue) -> requireLabel(value, key, labelValue) }
            return role to NodeSelector(parsed.toMap())
        }

        private fun malformed(
            value: String,
            pairNote: String,
        ): String =
            "Invalid $OPTION '$value'$pairNote: expected $EXPECTED_FORM, with <role> one of " +
                "${ServerType.entries.joinToString(", ") { it.serverType }}."

        /**
         * One `key=value` pair, or null when it is not one. The key is required; the value may be
         * empty (`key=`), which Kubernetes allows and which selects nodes whose label is empty.
         */
        private fun parseLabel(pair: String): Pair<String, String>? {
            val parts = pair.split("=")
            return parts.takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() }
        }

        /**
         * Refuses a key or value Kubernetes would refuse as a label, so a bad mapping fails at
         * `init` instead of leaving pods unschedulable. A key is an optional DNS-subdomain prefix
         * and `/`, then a name; a name and a value are alphanumerics with `-`, `_` and `.` inside.
         */
        private fun requireLabel(
            value: String,
            key: String,
            labelValue: String,
        ) {
            val prefix = key.substringBefore("/", "")
            val name = key.substringAfter("/")
            val validKey =
                key.count { it == '/' } <= 1 &&
                    isLabelName(name) &&
                    (key.indexOf('/') < 0 || (prefix.length <= Constants.K8s.LABEL_PREFIX_MAX_LENGTH && DNS_SUBDOMAIN.matches(prefix)))
            require(validKey) {
                "Invalid $OPTION '$value': '$key' is not a valid Kubernetes label key " +
                    "(an optional DNS-subdomain prefix and '/', then up to ${Constants.K8s.LABEL_NAME_MAX_LENGTH} " +
                    "alphanumerics, '-', '_' or '.', beginning and ending alphanumeric)."
            }
            require(labelValue.isEmpty() || isLabelName(labelValue)) {
                "Invalid $OPTION '$value': '$labelValue' is not a valid Kubernetes label value " +
                    "(empty, or up to ${Constants.K8s.LABEL_NAME_MAX_LENGTH} alphanumerics, '-', '_' or '.', " +
                    "beginning and ending alphanumeric)."
            }
        }

        private fun isLabelName(text: String): Boolean = text.length <= Constants.K8s.LABEL_NAME_MAX_LENGTH && LABEL_NAME.matches(text)

        private val LABEL_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9_.-]*[A-Za-z0-9])?")
        private val DNS_SUBDOMAIN = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")
    }
}

/**
 * Where a workspace's nodes come from, which decides the role selectors it starts from: nodes
 * easy-db-lab builds (AWS, local) carry one `type=<role>` label each; a cluster the user brings
 * carries no label easy-db-lab knows, so each role runs anywhere until `init --role-nodes` maps it.
 */
enum class NodeOrigin {
    OWNED,
    BROUGHT,
}
