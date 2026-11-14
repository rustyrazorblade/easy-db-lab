package com.rustyrazorblade.easydblab.configuration.otel

/**
 * Where the core OTel collector reads pod stdout and stderr from.
 *
 * The core collector runs on the pod network and unprivileged. Reading pod logs is the one thing in
 * it that needs the node: the kubelet writes them under `/var/log/pods`, which only a hostPath
 * volume reaches. An environment that cannot mount hostPaths still runs the core, just without its
 * pod-log pipeline. The builder takes this choice as a value, so it never reads the environment's
 * capabilities itself; the caller decides it from `HOST_PATH`.
 */
sealed interface PodLogSource {
    /** Tail the kubelet's pod log files from a read-only `/var/log/pods` hostPath. */
    data object NodeFiles : PodLogSource

    /** Collect no pod logs: no pod-log pipeline, no hostPath, a restricted security context. */
    data object None : PodLogSource
}
