package com.rustyrazorblade.easydblab.observability

/**
 * Type alias for span names to improve code readability.
 */
typealias SpanName = String

/**
 * Type alias for metric names to improve code readability.
 */
typealias MetricName = String

/**
 * Centralized constants for OpenTelemetry instrumentation names.
 *
 * This object provides type-safe, consistent naming for all spans, metrics,
 * and attributes used in telemetry throughout the application.
 */
object TelemetryNames {
    /**
     * Span names for distributed tracing.
     */
    object Spans {
        // Command spans
        const val COMMAND_EXECUTE: SpanName = "command.execute"
        const val COMMAND_UP: SpanName = "command.up"
        const val COMMAND_DOWN: SpanName = "command.down"
        const val COMMAND_INIT: SpanName = "command.init"
        const val COMMAND_STATUS: SpanName = "command.status"
        const val COMMAND_EXEC: SpanName = "command.exec"

        // SSH operation spans
        const val SSH_EXECUTE: SpanName = "ssh.execute"
        const val SSH_UPLOAD: SpanName = "ssh.upload"
        const val SSH_UPLOAD_DIRECTORY: SpanName = "ssh.upload_directory"
        const val SSH_DOWNLOAD: SpanName = "ssh.download"
        const val SSH_DOWNLOAD_DIRECTORY: SpanName = "ssh.download_directory"

        // Kubernetes operation spans
        const val K8S_APPLY_MANIFESTS: SpanName = "k8s.apply_manifests"
        const val K8S_DELETE_NAMESPACE: SpanName = "k8s.delete_namespace"
        const val K8S_GET_STATUS: SpanName = "k8s.get_status"
        const val K8S_WAIT_PODS_READY: SpanName = "k8s.wait_pods_ready"
        const val K8S_SCALE_STATEFULSET: SpanName = "k8s.scale_statefulset"
        const val K8S_CREATE_JOB: SpanName = "k8s.create_job"
        const val K8S_DELETE_JOB: SpanName = "k8s.delete_job"
        const val K8S_CREATE_CONFIG_MAP: SpanName = "k8s.create_config_map"
        const val K8S_APPLY_YAML: SpanName = "k8s.apply_yaml"
        const val K8S_LABEL_NODE: SpanName = "k8s.label_node"

        // Docker operation spans
        const val DOCKER_RUN: SpanName = "docker.run"
        const val DOCKER_BUILD: SpanName = "docker.build"
        const val DOCKER_PULL: SpanName = "docker.pull"

        // AWS operation spans (manual, auto-instrumentation handles SDK calls)
        const val AWS_CREATE_INSTANCES: SpanName = "aws.create_instances"
        const val AWS_TERMINATE_INSTANCES: SpanName = "aws.terminate_instances"
        const val AWS_WAIT_INSTANCES_READY: SpanName = "aws.wait_instances_ready"
    }

    /**
     * Metric names for observability.
     */
    object Metrics {
        const val COMMAND_DURATION: MetricName = "easydblab.command.duration"
        const val COMMAND_COUNT: MetricName = "easydblab.command.count"
        const val SSH_OPERATION_DURATION: MetricName = "easydblab.ssh.operation.duration"
        const val SSH_OPERATION_COUNT: MetricName = "easydblab.ssh.operation.count"
        const val K8S_OPERATION_DURATION: MetricName = "easydblab.k8s.operation.duration"
        const val K8S_OPERATION_COUNT: MetricName = "easydblab.k8s.operation.count"
    }

    /**
     * Attribute keys for span and metric attributes.
     */
    object Attributes {
        const val COMMAND_NAME = "command.name"
        const val CLUSTER_ID = "cluster.id"
        const val SERVER_TYPE = "server.type"
        const val HOST_TARGET = "host.target"
        const val HOST_ALIAS = "host.alias"
        const val K8S_NAMESPACE = "k8s.namespace"
        const val K8S_RESOURCE_NAME = "k8s.resource.name"
        const val SSH_COMMAND = "ssh.command"
        const val SSH_COMMAND_REDACTED = "ssh.command.redacted"
        const val FILE_PATH_LOCAL = "file.path.local"
        const val FILE_PATH_REMOTE = "file.path.remote"
        const val OPERATION_TYPE = "operation.type"
        const val SUCCESS = "success"
        const val ERROR_MESSAGE = "error.message"
    }

    /**
     * Service name for OpenTelemetry resource.
     */
    const val SERVICE_NAME = "easy-db-lab"
}
