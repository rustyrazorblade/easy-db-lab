package com.rustyrazorblade.easydblab.architecture.fixtures

/**
 * Deliberate violations and look-alikes for
 * [com.rustyrazorblade.easydblab.architecture.NoDefaultNamespaceLiteralTest]: the first group names the
 * `default` namespace as a constant, which the guard must report; the second uses `default` for
 * something else (the default ServiceAccount, a kit argument's default, the default tenant), which it
 * must leave alone.
 */
object DefaultNamespaceLiterals {
    const val KUBECTL = "kubectl get pods -n default"
    const val HELM = "helm install x --namespace default"
    const val HELM_EQUALS = "helm install x --namespace=default"
    const val SERVICE_HOST = "mimir.default.svc.cluster.local"
    const val API_PATH = "/api/v1/namespaces/default/pods"
    const val YAML = "namespace: default"
    const val NAMESPACE = "default"

    fun withDefault(namespace: String = "default"): String = namespace

    const val SERVICE_ACCOUNT = "serviceAccountName: default"
    const val KIT_ARG = "default: 3"
    const val TENANT = "tenant: default"
}
