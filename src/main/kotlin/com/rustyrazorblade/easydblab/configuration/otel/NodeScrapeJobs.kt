package com.rustyrazorblade.easydblab.configuration.otel

/**
 * The scrape-job shapes both collector builders share: a node-local pod-discovery job and a
 * `localhost` job. Both collectors are DaemonSets, so a target that exists once must be scraped only
 * by the collector on its node, and every job names that node the same way.
 *
 * Node identity is the `K8S_NODE_NAME` env var (the downward API's `spec.nodeName`), which both
 * collectors set. The OS hostname is not used: on the pod network it is the collector pod's name.
 */
object NodeScrapeJobs {
    /** The collector's node name, expanded by the collector at runtime. */
    const val NODE_NAME = "\${env:K8S_NODE_NAME}"

    /** The collector's node IP, expanded by the collector at runtime. */
    const val NODE_IP = "\${env:K8S_NODE_IP}"

    /** The cluster label every job carries. */
    const val CLUSTER_NAME = "\${env:CLUSTER_NAME}"

    /** The scrape interval of every job that does not name its own. */
    const val SCRAPE_INTERVAL = "15s"

    /** The relabel rule that stamps the `cluster` label. */
    val clusterRelabel = PrometheusRelabelConfig(targetLabel = "cluster", replacement = CLUSTER_NAME)

    /**
     * A pod-discovery job for a singleton pod selected by one label, scraped only by the collector
     * on the pod's own node so one series set exists. Pod SD yields one target per declared
     * container port, so the job keeps only [port] and then addresses the pod IP on it directly;
     * `instance` is the pod name unless [instance] names a fixed value.
     */
    fun nodeLocalPodJob(
        jobName: String,
        namespace: String,
        podLabel: String,
        podLabelValue: String,
        port: Int,
        scrapeInterval: String = SCRAPE_INTERVAL,
        scrapeTimeout: String? = null,
        instance: String? = null,
    ) = PrometheusScrapeJob(
        jobName = jobName,
        scrapeInterval = scrapeInterval,
        scrapeTimeout = scrapeTimeout,
        kubernetesSdConfigs = listOf(podDiscovery(namespace)),
        metricsPath = "/metrics",
        relabelConfigs =
            listOf(
                keep(podLabelMetaKey(podLabel), podLabelValue),
                onThisNode(),
                keep("__meta_kubernetes_pod_container_port_number", port.toString()),
                podAddress(port),
                instanceRelabel(instance),
                clusterRelabel,
            ),
    )

    /** A static job on `localhost:<port>`, the per-node endpoint of a host-network process. */
    fun localhostJob(
        jobName: String,
        port: Int,
    ) = PrometheusScrapeJob(
        jobName = jobName,
        scrapeInterval = SCRAPE_INTERVAL,
        staticConfigs = listOf(PrometheusStaticConfig(targets = listOf("localhost:$port"))),
        metricsPath = "/metrics",
        relabelConfigs =
            listOf(
                PrometheusRelabelConfig(targetLabel = "instance", replacement = "$NODE_NAME:$port"),
                clusterRelabel,
            ),
    )

    /** Pod discovery limited to [namespace]. */
    fun podDiscovery(namespace: String) =
        PrometheusKubernetesSdConfig(role = "pod", namespaces = PrometheusSdNamespaces(names = listOf(namespace)))

    /** Keeps only pods on the scraping collector's node, so a DaemonSet never duplicates a series. */
    fun onThisNode() = keep("__meta_kubernetes_pod_node_name", NODE_NAME)

    /** Targets the pod IP on [port]. OTel expands `$$` to a literal `$`, leaving Prometheus its `$1`. */
    fun podAddress(port: Int) =
        PrometheusRelabelConfig(
            sourceLabels = listOf("__meta_kubernetes_pod_ip"),
            targetLabel = "__address__",
            replacement = "\$\$1:$port",
        )

    /** A `keep` rule on one source label. */
    fun keep(
        sourceLabel: String,
        regex: String,
    ) = PrometheusRelabelConfig(sourceLabels = listOf(sourceLabel), regex = regex, action = "keep")

    /**
     * Converts a K8s pod label key (e.g. `app.kubernetes.io/component`) into the Prometheus
     * pod-discovery meta-label (`__meta_kubernetes_pod_label_app_kubernetes_io_component`).
     * Prometheus sanitizes label names by replacing every non-alphanumeric character with `_`.
     */
    fun podLabelMetaKey(key: String): String {
        val sanitized = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "__meta_kubernetes_pod_label_$sanitized"
    }

    /** `instance` is the pod name unless the job names a fixed value, such as `<node>:<port>`. */
    private fun instanceRelabel(instance: String?) =
        when (instance) {
            null -> PrometheusRelabelConfig(sourceLabels = listOf("__meta_kubernetes_pod_name"), targetLabel = "instance")
            else -> PrometheusRelabelConfig(targetLabel = "instance", replacement = instance)
        }
}
