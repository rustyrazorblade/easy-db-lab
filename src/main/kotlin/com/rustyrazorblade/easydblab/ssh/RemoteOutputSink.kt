package com.rustyrazorblade.easydblab.ssh

import com.rustyrazorblade.easydblab.output.NodeStream

/**
 * Receives a remote command's output one whole, redacted line at a time, while the command runs.
 *
 * [ISSHClient] knows nothing about which node it is talking to by alias, so the caller that does
 * (`DefaultRemoteOperationsService`) builds the sink and routes its lines to that node's output.
 */
fun interface RemoteOutputSink {
    fun line(
        stream: NodeStream,
        text: String,
    )

    /**
     * The command returned while some of its lines were still waiting to be handed over (an
     * interrupted command stops waiting for a stalled console). They are delivered later.
     */
    fun stalled() = Unit
}
