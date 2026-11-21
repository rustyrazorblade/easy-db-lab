package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.kernel.causeChain
import io.fabric8.kubernetes.client.KubernetesClientException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * An RBAC denial as the API server words it:
 * `<resource> ["<name>"] is forbidden: User "<principal>" cannot <verb> resource "<resource>" in API group "<group>"`
 * followed by `in the namespace "<ns>"` or `at the cluster scope`.
 *
 * The verb is the RBAC verb the server evaluated (`list`, `watch`), not the HTTP method, and the
 * principal is the identity it evaluated, which is what a grant must name.
 *
 * @property principal the user, group member or ServiceAccount the server evaluated
 * @property verb the RBAC verb
 * @property resource the resource, with its subresource when there is one (`pods/exec`)
 * @property namespace the namespace, or null at the cluster scope
 * @property name the object's name, when the server gave one
 */
data class RbacDenial(
    val principal: String,
    val verb: String,
    val resource: String,
    val namespace: String? = null,
    val name: String? = null,
) {
    /** The server evaluated no credential at all: the client sent none, or the server ignored it. */
    val isAnonymous: Boolean get() = principal == ANONYMOUS

    companion object {
        private const val ANONYMOUS = "system:anonymous"

        private val FORM =
            Regex(
                """(?:\S+ "(?<name>[^"]*)" is forbidden: )?User "(?<principal>[^"]+)" cannot (?<verb>\S+) """ +
                    """resource "(?<resource>[^"]+)" in API group "[^"]*"(?: in the namespace "(?<namespace>[^"]+)")?""",
            )

        /** The denial in [message], or null when it is not an RBAC denial. */
        fun parse(message: String): RbacDenial? =
            FORM.find(message)?.groups?.let { groups ->
                RbacDenial(
                    principal = groups.value("principal"),
                    verb = groups.value("verb"),
                    resource = groups.value("resource"),
                    namespace = groups["namespace"]?.value,
                    name = groups["name"]?.value,
                )
            }
    }
}

/**
 * An admission controller's refusal of a request's content: Pod Security, an admission webhook,
 * or a ValidatingAdmissionPolicy. Granting RBAC does not fix any of these; changing the object or
 * the policy does.
 */
sealed interface AdmissionRejection {
    /** The policy's name, as the user would look it up. */
    val policy: String

    /** The rejected object's name, when the server gave one. */
    val name: String?

    /** One entry per reason the policy gave. */
    val violations: List<String>

    /**
     * `violates PodSecurity "<level>:<version>": <violation>, <violation>, ...`.
     *
     * @property level the Pod Security level and version, for example "restricted:latest"
     */
    data class PodSecurity(
        val level: String,
        override val violations: List<String>,
        override val name: String? = null,
    ) : AdmissionRejection {
        override val policy: String get() = POD_SECURITY
    }

    /** `admission webhook "<webhook>" denied the request: <reason>`. */
    data class Webhook(
        val webhook: String,
        val reason: String,
        override val name: String? = null,
    ) : AdmissionRejection {
        override val policy: String get() = webhook
        override val violations: List<String> get() = listOf(reason)
    }

    /** `ValidatingAdmissionPolicy '<policy>' with binding '<binding>' denied request: <reason>`. */
    data class ValidatingPolicy(
        override val policy: String,
        val reason: String,
        override val name: String? = null,
    ) : AdmissionRejection {
        override val violations: List<String> get() = listOf(reason)
    }

    companion object {
        const val POD_SECURITY = "PodSecurity"

        private val OBJECT_NAME = Regex("""^\S+ "(?<name>[^"]*)" is forbidden: """)
        private val POD_SECURITY_FORM = Regex("""violates PodSecurity "(?<level>[^"]+)": (?<violations>.+)""")
        private val WEBHOOK_FORM = Regex("""admission webhook "(?<webhook>[^"]+)" denied the request: (?<reason>.+)""")
        private val POLICY_FORM =
            Regex("""ValidatingAdmissionPolicy '(?<policy>[^']+)' with binding '[^']*' denied request: (?<reason>.+)""")

        /** The rejection in [message], or null when no admission controller refused the request. */
        fun parse(message: String): AdmissionRejection? {
            val name =
                OBJECT_NAME
                    .find(message)
                    ?.groups
                    ?.get("name")
                    ?.value
            POD_SECURITY_FORM.find(message)?.groups?.let {
                return PodSecurity(it.value("level"), splitTopLevel(it.value("violations")), name)
            }
            WEBHOOK_FORM.find(message)?.groups?.let {
                return Webhook(it.value("webhook"), it.value("reason"), name)
            }
            return POLICY_FORM.find(message)?.groups?.let {
                ValidatingPolicy(it.value("policy"), it.value("reason"), name)
            }
        }

        /**
         * Splits Pod Security's violation list at the `, ` between violations. A violation's
         * parenthesised explanation can hold commas of its own (`containers "a", "b" must ...`).
         */
        private fun splitTopLevel(list: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            list.forEachIndexed { index, char ->
                when (char) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' ->
                        if (depth == 0) {
                            parts += list.substring(start, index)
                            start = index + 1
                        }
                }
            }
            parts += list.substring(start)
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

/**
 * True when a Kubernetes API answer in this failure's cause chain is a 401 or 403. Asking again
 * gets the same answer, so a poll must stop on the first one instead of retrying to its deadline.
 */
fun Throwable.isKubernetesAuthRejection(): Boolean =
    causeChain().any { it is KubernetesClientException && (it.code == HTTP_UNAUTHORIZED || it.code == HTTP_FORBIDDEN) }

private fun MatchGroupCollection.value(group: String): String = get(group)?.value.orEmpty()
