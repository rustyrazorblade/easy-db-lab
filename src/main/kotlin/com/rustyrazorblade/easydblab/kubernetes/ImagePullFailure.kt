package com.rustyrazorblade.easydblab.kubernetes

import com.rustyrazorblade.easydblab.Constants
import io.fabric8.kubernetes.api.model.Pod

/**
 * A pod container whose image the kubelet cannot pull: a missing tag, an unknown repository, a
 * malformed reference, or a registry that refused the node's credentials. A wait for the pod to
 * run fails at once on one, because the pod will not start until the image is changed.
 *
 * @property container The init or main container that cannot start
 * @property image The image reference the container asked for
 * @property reason The kubelet's waiting reason, one of [Constants.K8s.IMAGE_PULL_FAILURE_REASONS]
 * @property message The kubelet's message, which names the registry's error; empty when it gave none
 */
data class ImagePullFailure(
    val container: String,
    val image: String,
    val reason: String,
    val message: String,
)

/** The first init or main container of this pod that cannot pull its image, or null when there is none. */
fun Pod.imagePullFailure(): ImagePullFailure? =
    (status?.initContainerStatuses.orEmpty() + status?.containerStatuses.orEmpty())
        .firstNotNullOfOrNull { container ->
            container.state
                ?.waiting
                ?.takeIf { it.reason in Constants.K8s.IMAGE_PULL_FAILURE_REASONS }
                ?.let { waiting ->
                    ImagePullFailure(
                        container = container.name.orEmpty(),
                        image = container.image.orEmpty(),
                        reason = waiting.reason,
                        message = waiting.message.orEmpty(),
                    )
                }
        }
