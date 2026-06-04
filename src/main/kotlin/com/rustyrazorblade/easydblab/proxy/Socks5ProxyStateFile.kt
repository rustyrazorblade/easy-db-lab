package com.rustyrazorblade.easydblab.proxy

import kotlinx.serialization.Serializable

/**
 * On-disk representation of the running SOCKS5 proxy process state.
 *
 * Written by [ProcessSocksProxyService] when the proxy is started and read by
 * [ProcessSocksProxyService] on subsequent invocations to detect a reusable process.
 * Also read by the `Down` command to kill the process on cluster teardown.
 *
 * The file is stored as `.socks5-proxy-state` in the cluster working directory.
 */
@Serializable
data class Socks5ProxyStateFile(
    val pid: Int,
    val port: Int,
    val controlHost: String,
    val controlIP: String,
    val clusterName: String,
    val startTime: String,
    val sshConfig: String,
)
