package com.rustyrazorblade.easydblab

/**
 * Central location for all constants used throughout the application
 */
object Constants {
    // Container paths and directories
    object Paths {
        const val LOCAL_MOUNT = "/local"
        const val CREDENTIALS_MOUNT = "/credentials"
    }

    // Server types
    object Servers {
        const val DATABASE = "cassandra"
        const val STRESS = "stress"
        const val CONTROL = "control"
    }

    // Time-related constants
    object Time {
        const val SECONDS_PER_MINUTE = 60
        const val MILLIS_PER_SECOND = 1000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val THREAD_SLEEP_DELAY_MS = 10L
        const val THREAD_JOIN_TIMEOUT_MS = 1000L
    }

    // EMR configuration
    object EMR {
        const val POLL_INTERVAL_MS = 5000L
        const val MAX_POLL_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours
        const val LOG_INTERVAL_POLLS = 12 // Log every 12 polls (60 seconds at 5s interval)
        const val COMMAND_RUNNER_JAR = "command-runner.jar"
        const val SPARK_SUBMIT_COMMAND = "spark-submit"

        // Display formatting for spark jobs table
        const val JOB_NAME_MAX_LENGTH = 30
        const val TRUNCATION_SUFFIX_LENGTH = 3 // Length of "..."

        // Log display settings
        const val STDERR_TAIL_LINES = 100

        // S3 prefix for EMR logs (used by S3 notifications and Vector)
        const val S3_LOG_PREFIX = "spark/emr-logs/"

        // Log ingestion wait time (ms) - time to wait for logs to be ingested into Victoria Logs
        const val LOG_INGESTION_WAIT_MS = 5000L

        // Maximum log lines to display on job failure
        const val MAX_LOG_LINES = 100
    }

    // Retry configuration
    object Retry {
        // IAM operations: Higher retry count due to eventual consistency
        const val MAX_INSTANCE_PROFILE_RETRIES = 5

        // EC2 instance operations: Higher retry count due to eventual consistency
        // after instance creation, describeInstances may fail briefly
        const val MAX_EC2_INSTANCE_RETRIES = 5

        // Standard AWS operations (S3, EC2, EMR, etc.): 3 attempts with exponential backoff
        const val MAX_AWS_RETRIES = 3

        // Docker operations: Retry count for transient Docker API failures
        const val MAX_DOCKER_RETRIES = 3

        // Network operations: Generic retry count for network failures
        const val MAX_NETWORK_RETRIES = 3

        // SSH connection retries: High count for waiting on instance boot
        // 30 attempts × 10s = ~5 minutes max wait time
        const val MAX_SSH_CONNECTION_RETRIES = 30
        const val SSH_CONNECTION_RETRY_DELAY_MS = 10_000L

        // Base delay for exponential backoff (1s, 2s, 4s, 8s...)
        const val EXPONENTIAL_BACKOFF_BASE_MS = 1000L

        // S3 log retrieval: Logs may take time to appear after job completion
        // 10 attempts × 3s = ~30 seconds max wait time
        const val MAX_LOG_RETRIEVAL_RETRIES = 10
        const val LOG_RETRIEVAL_RETRY_DELAY_MS = 3000L
    }

    // Exit codes
    object ExitCodes {
        const val ERROR = 1
    }

    // Network configuration
    object Network {
        const val MIN_PORT = 0
        const val MAX_PORT = 65535
        const val SSH_PORT = 22
    }

    // HTTP Status Codes
    object HttpStatus {
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val SERVER_ERROR_MIN = 500
        const val SERVER_ERROR_MAX = 599
        const val CLIENT_ERROR_THRESHOLD = 500 // Errors >= 500 are server errors
    }

    // Docker configuration (for local container operations)
    object Docker {
        const val FRAME_REPORTING_INTERVAL = 100
    }

    // Packer configuration
    object Packer {
        const val CASSANDRA_VERSIONS_FILE = "cassandra_versions.yaml"
        const val AWS_CREDENTIALS_ENV = "AWS_SHARED_CREDENTIALS_FILE"
    }

    // Environment variables
    object Environment {
        const val USER_DIR = "EASY_DB_LAB_USER_DIR"
    }

    // AWS configuration
    object AWS {
        const val DEFAULT_CREDENTIALS_NAME = "awscredentials"
        const val SSH_KEY_ENV = "EASY_CASS_LAB_SSH_KEY"

        // AMI configuration
        const val AMI_PATTERN_TEMPLATE = "rustyrazorblade/images/easy-db-lab-cassandra-%s-*"

        // IAM Role Names
        object Roles {
            const val EC2_INSTANCE_ROLE = "EasyDBLabEC2Role"
            const val EMR_SERVICE_ROLE = "EasyDBLabEMRServiceRole"
            const val EMR_EC2_ROLE = "EasyDBLabEMREC2Role"
        }
    }

    // S3 configuration
    object S3 {
        /** Prefix for all easy-db-lab S3 buckets */
        const val BUCKET_PREFIX = "easy-db-lab-"
    }

    // Configuration file paths
    object ConfigPaths {
        // Cassandra node configs
        const val CASSANDRA_SIDECAR_CONFIG = "cassandra/cassandra-sidecar.yaml"
        const val CASSANDRA_REMOTE_SIDECAR_DIR = "/etc/cassandra-sidecar"
        const val CASSANDRA_REMOTE_SIDECAR_CONFIG = "$CASSANDRA_REMOTE_SIDECAR_DIR/cassandra-sidecar.yaml"

        // Local config files
        const val CASSANDRA_PATCH_FILE = "cassandra.patch.yaml"
    }

    // K3s configuration
    object K3s {
        const val REMOTE_KUBECONFIG = "/etc/rancher/k3s/k3s.yaml"
        const val LOCAL_KUBECONFIG = "kubeconfig"
        const val NODE_TOKEN_PATH = "/var/lib/rancher/k3s/server/node-token"
        const val DEFAULT_SERVER_URL = "https://127.0.0.1:6443"
    }

    // K8s observability configuration
    object K8s {
        const val PATH_PREFIX = "/k8s/"
        const val NAMESPACE = "default"
        const val MANIFEST_DIR = "k8s"
        const val RESOURCE_PACKAGE = "com.rustyrazorblade.easydblab.commands.k8s"
        const val GRAFANA_PORT = 3000
        const val VICTORIAMETRICS_PORT = 8428
        const val VICTORIALOGS_PORT = 9428
        const val S3MANAGER_PORT = 8080
        const val REGISTRY_PORT = 5000
    }

    // OpenSearch configuration
    object OpenSearch {
        const val DEFAULT_VERSION = "2.11"
        const val DEFAULT_INSTANCE_TYPE = "t3.small.search"
        const val DEFAULT_INSTANCE_COUNT = 1
        const val DEFAULT_EBS_SIZE_GB = 100
        const val POLL_INTERVAL_MS = 30_000L // 30 seconds - domains take 10-30 min to create
        const val MAX_POLL_TIMEOUT_MS = 45 * 60 * 1000L // 45 minutes max wait
        const val LOG_INTERVAL_POLLS = 2 // Log every 2 polls (60 seconds at 30s interval)
        const val DOMAIN_NAME_MAX_LENGTH = 28 // AWS OpenSearch domain names must be 3-28 lowercase chars
    }

    // ClickHouse configuration
    object ClickHouse {
        const val NAMESPACE = "default"
        const val HTTP_PORT = 8123
        const val NATIVE_PORT = 9000
        const val MINIMUM_NODES_REQUIRED = 3
        const val S3_SECRET_NAME = "clickhouse-s3-credentials"
    }

    // Cassandra stress testing configuration
    object Stress {
        const val NAMESPACE = "default"
        const val IMAGE = "ghcr.io/apache/cassandra-easy-stress:latest"
        const val JOB_PREFIX = "stress"
        const val LABEL_KEY = "app.kubernetes.io/name"
        const val LABEL_VALUE = "cassandra-stress"
        const val PROFILE_MOUNT_PATH = "/profiles"
        const val DEFAULT_CASSANDRA_PORT = 9042
    }

    // Proxy configuration
    object Proxy {
        const val DEFAULT_SOCKS5_PORT = 1080
    }

    // Container Registry configuration
    object Registry {
        /** Default registry port */
        const val PORT = 5000

        /** Directory on control node where TLS certificates are stored */
        const val CERT_DIR = "/opt/registry/certs"

        /** S3 path for the CA certificate */
        const val S3_CERT_PATH = "registry/ca.crt"
    }

    // VPC and tagging configuration
    object Vpc {
        /** Default VPC CIDR block */
        const val DEFAULT_CIDR = "10.0.0.0/16"

        /** Tag key used to identify easy-db-lab resources */
        const val TAG_KEY = "easy_cass_lab"

        /** Tag value used to identify easy-db-lab resources */
        const val TAG_VALUE = "1"

        /** VPC name for packer infrastructure */
        const val PACKER_VPC_NAME = "easy-db-lab-packer"

        /** SOCKS5 proxy state file name */
        const val SOCKS5_PROXY_STATE_FILE = ".socks5-proxy-state"

        /**
         * Generates a subnet CIDR block for a given index within the VPC.
         * Uses the pattern 10.0.{index+1}.0/24
         */
        fun subnetCidr(index: Int): String = "10.0.${index + 1}.0/24"
    }
}
