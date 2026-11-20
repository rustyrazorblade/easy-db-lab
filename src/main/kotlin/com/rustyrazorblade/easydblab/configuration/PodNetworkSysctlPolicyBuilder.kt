package com.rustyrazorblade.easydblab.configuration

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicy
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicyBinding
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicyBindingBuilder
import io.fabric8.kubernetes.api.model.admissionregistration.v1beta1.MutatingAdmissionPolicyBuilder

/**
 * The one cluster-wide admission policy that gives every pod in the pod network the tuned
 * per-namespace network sysctls (pod-network-tuning, design D1), and its binding.
 *
 * It exists so that every kit, every operator-built pod and every later kit runs with the host's
 * network values without a sysctls block of its own. On pod `CREATE` in any namespace, a CEL
 * JSONPatch adds `spec.securityContext` when the pod has none, `securityContext.sysctls` when that
 * is absent or empty, and otherwise only the keys the pod does not set, so a key a pod sets keeps
 * its value. A JSONPatch is used, not an apply configuration, because `sysctls` is an atomic list.
 * Host-network pods are skipped: they run in the tuned host namespace, and the kubelet rejects a
 * `net.*` sysctl on one. `failurePolicy: Fail`, so a pod is never created untuned in silence.
 *
 * The API is `v1beta1`, the version K3s 1.35 serves once `start-k3s-server.sh` enables it; it moves
 * to `v1` when the AMI reaches K3s 1.36.
 *
 * @param sysctls the keys and values to inject, from `PodNetworkSysctls`
 * @throws IllegalArgumentException when [sysctls] is empty, or a key or value holds a character a
 *   CEL string literal would need to escape
 */
class PodNetworkSysctlPolicyBuilder(
    private val sysctls: Map<String, String>,
) {
    init {
        require(sysctls.isNotEmpty()) { "The pod network sysctl policy needs at least one sysctl" }
        sysctls.forEach { (key, value) ->
            require(CEL_SAFE.matches(key) && CEL_SAFE.matches(value)) {
                "Sysctl $key = '$value' cannot be written as a CEL string literal"
            }
        }
    }

    /** The policy, then its binding, in the order they are applied. */
    fun buildAll(): List<HasMetadata> = listOf(buildPolicy(), buildBinding())

    /** The policy: which pods it matches and the patch it adds. */
    fun buildPolicy(): MutatingAdmissionPolicy =
        MutatingAdmissionPolicyBuilder()
            .withNewMetadata()
            .withName(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME)
            .endMetadata()
            .withNewSpec()
            .withFailurePolicy("Fail")
            .withReinvocationPolicy("Never")
            .withNewMatchConstraints()
            .addNewResourceRule()
            .withApiGroups("")
            .withApiVersions("v1")
            .withOperations("CREATE")
            .withResources("pods")
            .endResourceRule()
            .endMatchConstraints()
            .addNewMatchCondition()
            .withName("pod-network")
            .withExpression("!(has(object.spec.hostNetwork) && object.spec.hostNetwork)")
            .endMatchCondition()
            .addNewVariable()
            .withName("desired")
            .withExpression(desiredExpression())
            .endVariable()
            .addNewMutation()
            .withPatchType("JSONPatch")
            .withNewJsonPatch()
            .withExpression(PATCH_EXPRESSION)
            .endJsonPatch()
            .endMutation()
            .endSpec()
            .build()

    /** The binding that puts the policy in effect for every namespace. */
    fun buildBinding(): MutatingAdmissionPolicyBinding =
        MutatingAdmissionPolicyBindingBuilder()
            .withNewMetadata()
            .withName(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME)
            .endMetadata()
            .withNewSpec()
            .withPolicyName(Constants.NodeTuning.POD_SYSCTL_POLICY_NAME)
            .endSpec()
            .build()

    /**
     * The injected sysctls as a CEL list of `{name, value}` maps. Plain maps, not typed
     * `Object.spec...` values: the API server declares those types only in a mutation, not in a
     * variable, and a JSONPatch value takes a map as it is.
     */
    private fun desiredExpression(): String =
        sysctls.entries.joinToString(", ", prefix = "[", postfix = "]") { (key, value) ->
            "{\"name\": \"$key\", \"value\": \"$value\"}"
        }

    private companion object {
        /** Keys and values are sysctl names and numbers: nothing a CEL string literal must escape. */
        val CEL_SAFE = Regex("""[A-Za-z0-9_.\- ]+""")

        /**
         * No security context: add one holding every sysctl. No sysctls (absent or empty): add the
         * list. Otherwise append each sysctl whose name the pod does not set.
         *
         * The first branch is defensive only. The API server defaults `spec.securityContext` to `{}`
         * before mutating admission, so a real pod takes the second branch. The first is kept because,
         * under `failurePolicy: Fail`, reading `securityContext.sysctls` from an absent context would
         * refuse the pod.
         */
        val PATCH_EXPRESSION =
            """
            !has(object.spec.securityContext) ?
              [JSONPatch{op: "add", path: "/spec/securityContext", value: {"sysctls": variables.desired}}] :
            !has(object.spec.securityContext.sysctls) || size(object.spec.securityContext.sysctls) == 0 ?
              [JSONPatch{op: "add", path: "/spec/securityContext/sysctls", value: variables.desired}] :
              variables.desired
                .filter(d, !object.spec.securityContext.sysctls.exists(s, s.name == d.name))
                .map(d, JSONPatch{op: "add", path: "/spec/securityContext/sysctls/-", value: d})
            """.trimIndent()
    }
}
