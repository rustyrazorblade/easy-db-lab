package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.kernel.ErrorDescription
import com.rustyrazorblade.easydblab.kernel.ProviderErrorHandler
import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import com.rustyrazorblade.easydblab.kernel.ProviderPolicy
import com.rustyrazorblade.easydblab.kernel.ProviderRequest
import io.fabric8.kubernetes.client.KubernetesClientException
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.URI
import java.net.URISyntaxException

/**
 * Tells the command layer that a Kubernetes API server refused a request, which request, and what
 * to do: an RBAC denial (`permission-denied`), a missing or ignored credential (`credentials`), or a
 * Pod Security, admission webhook or ValidatingAdmissionPolicy refusal (`rejected`).
 *
 * It claims a [KubernetesClientException] by its HTTP code and the API server's status message
 * alone, and has no collaborators, so it works during `init --kubeconfig`, before a workspace or
 * cluster state exists. The facts come from the server's message (the RBAC verb and the principal
 * it evaluated); when the message does not match, they come from the request URI and the HTTP
 * method of Fabric8's failure message. Any other answer (404, 409, a 422 that is not an admission
 * denial, a transport failure with code 0) is left to the cause-chain error. It also claims
 * [ExecPluginNotFoundException], a kubeconfig exec plugin that [ExecPluginCheck] could not find, as
 * `credentials`.
 *
 * The detail is the server's status message, never the request URI, whose query could carry a
 * token.
 */
class KubernetesErrorHandler : ProviderErrorHandler {
    override fun describe(error: Throwable): ErrorDescription? =
        when (error) {
            is KubernetesClientException -> describeApiFailure(error)
            is ExecPluginNotFoundException ->
                ErrorDescription(
                    provider = PROVIDER,
                    kind = ProviderErrorKind.CREDENTIALS,
                    detail = error.message.orEmpty(),
                    remedy = error.installHint ?: "Install ${error.plugin}, or put it on PATH, then run the command again.",
                )
            else -> null
        }

    private fun describeApiFailure(error: KubernetesClientException): ErrorDescription? {
        val message = error.status?.message?.takeIf { it.isNotBlank() } ?: error.messageWithoutQuery()
        return when (error.code) {
            HTTP_UNAUTHORIZED -> credentials(message, error.fallbackRequest())
            HTTP_FORBIDDEN -> forbidden(error, message)
            HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> AdmissionRejection.parse(message)?.let { rejected(error, message, it) }
            else -> null
        }
    }

    private fun forbidden(
        error: KubernetesClientException,
        message: String,
    ): ErrorDescription {
        val denial = RbacDenial.parse(message)
        val rejection = if (denial == null) AdmissionRejection.parse(message) else null
        return when {
            denial?.isAnonymous == true -> credentials(message, denial.toRequest())
            denial != null -> permissionDenied(message, denial.toRequest())
            rejection != null -> rejected(error, message, rejection)
            else -> permissionDenied(message, error.fallbackRequest())
        }
    }

    private fun credentials(
        message: String,
        request: ProviderRequest?,
    ) = ErrorDescription(PROVIDER, ProviderErrorKind.CREDENTIALS, message, CREDENTIALS_REMEDY, request = request)

    private fun permissionDenied(
        message: String,
        request: ProviderRequest?,
    ) = ErrorDescription(PROVIDER, ProviderErrorKind.PERMISSION_DENIED, message, grantRemedy(request?.principal), request = request)

    private fun rejected(
        error: KubernetesClientException,
        message: String,
        rejection: AdmissionRejection,
    ): ErrorDescription {
        val request = error.fallbackRequest()?.let { it.copy(name = rejection.name ?: it.name) }
        return ErrorDescription(
            provider = PROVIDER,
            kind = ProviderErrorKind.REJECTED,
            detail = message,
            remedy = rejectionRemedy(rejection, request?.namespace),
            request = request,
            policy = ProviderPolicy(rejection.policy, (rejection as? AdmissionRejection.PodSecurity)?.level, rejection.violations),
        )
    }

    private fun RbacDenial.toRequest() =
        ProviderRequest(verb = verb, resource = resource, namespace = namespace, name = name, principal = principal)

    /**
     * The request as Fabric8 recorded it in its `Failure executing: <METHOD> at: <URI>` message: the
     * HTTP method and the resource, namespace and name of the URI's path. The path is parsed here
     * because Fabric8's own getters return null for a core-group namespaced collection
     * (`/api/v1/namespaces/lab/pods`). Null when the exception carries no request.
     */
    private fun KubernetesClientException.fallbackRequest(): ProviderRequest? {
        val match = message?.let { FAILURE_EXECUTING.find(it) } ?: return null
        val path = rawPathOf(match.groupValues[2]) ?: return null
        return ApiPath.parse(path)?.let {
            ProviderRequest(verb = match.groupValues[1], resource = it.resource, namespace = it.namespace, name = it.name)
        }
    }

    /**
     * The exception's own message, for an answer whose status carries no message. Fabric8's
     * `Failure executing: <METHOD> at: <URI>` form becomes the method, the URI's path and the HTTP
     * code; any other message keeps its text with every URI's query cut off. Either way the detail
     * never holds a query, which could carry a token.
     */
    private fun KubernetesClientException.messageWithoutQuery(): String {
        val text = message.orEmpty()
        val match = FAILURE_EXECUTING.find(text) ?: return URI_QUERY.replace(text, "$1")
        val path = rawPathOf(match.groupValues[2]) ?: URI_QUERY.replace(match.groupValues[2], "$1")
        return "The API server answered HTTP $code with no message to ${match.groupValues[1]} $path"
    }

    private fun rawPathOf(uri: String): String? =
        try {
            URI(uri).rawPath
        } catch (_: URISyntaxException) {
            null
        }

    private fun grantRemedy(principal: String?): String {
        val subject =
            when {
                principal == null -> "--user=<your kubeconfig user>"
                principal.startsWith(SERVICE_ACCOUNT_PREFIX) -> "--serviceaccount=${principal.removePrefix(SERVICE_ACCOUNT_PREFIX)}"
                else -> "--user=$principal"
            }
        return "easy-db-lab needs cluster-admin on this cluster. Ask a cluster administrator to grant it " +
            "${principal ?: "to the kubeconfig user"}, for example:\n" +
            "  kubectl create clusterrolebinding easy-db-lab-admin --clusterrole=cluster-admin $subject"
    }

    private fun rejectionRemedy(
        rejection: AdmissionRejection,
        namespace: String?,
    ): String {
        val where = namespace?.let { "namespace $it" } ?: "the namespace"
        return when (rejection) {
            is AdmissionRejection.PodSecurity ->
                "$where enforces Pod Security level ${rejection.level} (label pod-security.kubernetes.io/enforce). " +
                    "easy-db-lab's workloads need the privileged level: ask a cluster administrator to label $where " +
                    "pod-security.kubernetes.io/enforce=privileged, or use a namespace that allows it."
            is AdmissionRejection.Webhook ->
                "The admission webhook ${rejection.webhook} refused the request. Ask a cluster administrator to " +
                    "exempt $where from it, or change the policy it enforces."
            is AdmissionRejection.ValidatingPolicy ->
                "The ValidatingAdmissionPolicy ${rejection.policy} refused the request. Ask a cluster administrator to " +
                    "exempt $where from its binding, or change the policy."
        }
    }

    private companion object {
        const val PROVIDER = "Kubernetes"
        const val HTTP_UNPROCESSABLE = 422
        const val SERVICE_ACCOUNT_PREFIX = "system:serviceaccount:"
        val FAILURE_EXECUTING = Regex("""^Failure executing: (\S+) at: (\S+?)\.(?: |$)""")
        val URI_QUERY = Regex("""(\w+://[^\s?#]+)[?#]\S*""")
        const val CREDENTIALS_REMEDY =
            "Re-authenticate the kubeconfig user (log in again, or refresh its token or certificate). " +
                "If the user runs an exec auth plugin, it may have returned no credential: run it by hand to see why."
    }
}
