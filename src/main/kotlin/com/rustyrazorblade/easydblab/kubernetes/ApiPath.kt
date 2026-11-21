package com.rustyrazorblade.easydblab.kubernetes

/**
 * The resource a Kubernetes API request path names: `/api/<version>/...` for the core group or
 * `/apis/<group>/<version>/...`, then an optional `namespaces/<ns>/`, the resource, an optional
 * name and an optional subresource.
 *
 * Exists because Fabric8's request getters on `KubernetesClientException` return null for a
 * core-group namespaced collection (`/api/v1/namespaces/lab/pods`), the commonest request there is.
 *
 * @property resource the resource, with its subresource when there is one (`pods/exec`)
 * @property namespace the namespace, or null at the cluster scope
 * @property name the object's name, when the path names one
 */
data class ApiPath(
    val resource: String,
    val namespace: String? = null,
    val name: String? = null,
) {
    companion object {
        private const val NAMESPACES = "namespaces"
        private const val CORE_PREFIX = 2
        private const val GROUP_PREFIX = 3

        /** The resource [path] names, or null when it is not a resource API path. */
        fun parse(path: String): ApiPath? {
            val segments = path.split('/').filter { it.isNotEmpty() }
            val prefix =
                when (segments.firstOrNull()) {
                    "api" -> CORE_PREFIX
                    "apis" -> GROUP_PREFIX
                    else -> return null
                }
            return resourceOf(segments.drop(prefix))
        }

        private fun resourceOf(rest: List<String>): ApiPath? {
            if (rest.isEmpty()) return null
            val namespaced = rest.first() == NAMESPACES && rest.size > 2
            val namespace = if (namespaced) rest[1] else null
            val (resource, name, subresource) = (if (namespaced) rest.drop(2) else rest) + listOf(null, null)
            return resource?.let {
                ApiPath(resource = subresource?.let { sub -> "$it/$sub" } ?: it, namespace = namespace, name = name)
            }
        }
    }
}
