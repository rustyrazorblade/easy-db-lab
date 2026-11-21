package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.kernel.causeChain
import com.rustyrazorblade.easydblab.reason

/**
 * One line for a Kubernetes failure that a section of output reports in place instead of failing
 * the command: the status views' stress jobs and pods, a kit stop wait that could not confirm
 * the pods left, and the `metrics stream` stress job list.
 *
 * Those sites used to show the outermost message, so a missing exec auth plugin or an RBAC denial
 * kept as a cause reached the user as a generic wrapper. This asks [KubernetesErrorHandler] about
 * every cause, as the command reporter does, and gives the first description's detail and remedy;
 * a failure it does not describe keeps [reason].
 */
fun Throwable.kubernetesFailureReason(): String {
    val handler = KubernetesErrorHandler()
    val description = causeChain().firstNotNullOfOrNull { handler.describe(it) } ?: return reason()
    return "${description.detail}. ${description.remedy}"
}
