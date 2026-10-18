package com.rustyrazorblade.easydblab

/**
 * Central location for all constants used throughout the application
 */
object Constants {
    // Container paths and directories
    object Paths {
        const val LOCAL_MOUNT = "/local"
        const val CREDENTIALS_MOUNT = "/credentials"
        const val SSH_KEY_MOUNT = "/secret.pem"
    }

    // Server types
    object Servers {
        const val DATABASE = "cassandra"
        const val STRESS = "stress"
        const val CONTROL = "control"
    }

    // Kubernetes labels
    const val NODE_ORDINAL_LABEL = "easydblab.com/node-ordinal"

    /** The node label naming the node's pool (`db`, `app`, `control`), set when the node joins. */
    const val NODE_TYPE_LABEL = "type"

    /** The label on a platform local PV naming the kit instance it is for; the kit's PVCs select it. */
    const val PV_KIT_LABEL = "app.kubernetes.io/name"

    // Time-related constants
    object Time {
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 3600L
        const val MILLIS_PER_SECOND = 1000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val THREAD_SLEEP_DELAY_MS = 10L
        const val THREAD_JOIN_TIMEOUT_MS = 1000L
        const val DEFAULT_STATUS_REFRESH_SECONDS = 30L
    }

    // EMR configuration
    object EMR {
        const val POLL_INTERVAL_MS = 5000L
        const val MAX_POLL_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours
        const val LOG_INTERVAL_POLLS = 12 // Log every 12 polls (60 seconds at 5s interval)
        const val COMMAND_RUNNER_JAR = "command-runner.jar"

        /** Directory, relative to the working directory, that downloaded EMR logs are saved under. */
        const val LOCAL_LOGS_DIR = "logs"
        const val SPARK_SUBMIT_COMMAND = "spark-submit"

        // The Java agent's service.name for a job is this prefix and the job's (and its step's) name
        const val SERVICE_NAME_PREFIX = "spark-"

        // Display formatting for spark jobs table
        const val JOB_NAME_MAX_LENGTH = 30
        const val TRUNCATION_SUFFIX_LENGTH = 3 // Length of "..."

        // Log display settings
        const val STDERR_TAIL_LINES = 100

        const val DEFAULT_RELEASE_LABEL = "emr-spark-8.0.0"

        // S3 prefix for EMR logs
        const val S3_LOG_PREFIX = "spark/emr-logs/"

        // Log ingestion wait time (ms) - time to wait for logs to be ingested into Loki
        const val LOG_INGESTION_WAIT_MS = 5000L

        // Maximum log lines to display on job failure
        const val MAX_LOG_LINES = 100

        // After a step fails, how often to check for the stderr EMR uploads once the step has ended,
        // and how many checks to make. EMR uploads a step's logs every five minutes while it runs
        // and once more after it ends; the final copy has been seen five minutes after the failure.
        const val FINAL_STDERR_POLL_INTERVAL_MS = 15_000L
        const val FINAL_STDERR_MAX_ATTEMPTS = 28
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

        // VPC teardown: Retries on DependencyViolation errors during resource deletion
        // 5 attempts with exponential backoff: 5s, 10s, 20s, 40s
        const val MAX_VPC_TEARDOWN_RETRIES = 5
        const val VPC_TEARDOWN_BACKOFF_BASE_MS = 5000L
    }

    // Exit codes
    object ExitCodes {
        const val ERROR = 1
    }

    // Help topics
    object Help {
        // Classpath package scanned for packaged help-topic markdown resources.
        const val RESOURCE_PACKAGE = "com.rustyrazorblade.easydblab.help"
    }

    // Network configuration
    object Network {
        const val MIN_PORT = 0
        const val MAX_PORT = 65535
        const val SSH_PORT = 22

        /** EC2 IP protocol name for ICMP security group rules. */
        const val ICMP_PROTOCOL = "icmp"

        /** EC2 port value meaning "all ICMP types/codes" when used as both fromPort and toPort. */
        const val ALL_ICMP_TYPES = -1

        /** The service that answers with the caller's public IPv4 address as plain text. */
        const val EXTERNAL_IP_URL = "https://api.ipify.org/"

        /** How long public IP detection waits to connect before it fails. */
        const val EXTERNAL_IP_CONNECT_TIMEOUT_MS = 5000

        /** How long public IP detection waits for the answer once connected before it fails. */
        const val EXTERNAL_IP_READ_TIMEOUT_MS = 10000
    }

    // HTTP Status Codes
    object HttpStatus {
        const val OK = 200
        const val NO_CONTENT = 204
        const val MULTIPLE_CHOICES = 300
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404

        /** S3's answer to a write whose conditional request (`If-Match`, `If-None-Match`) is concurrent with another. */
        const val CONFLICT = 409

        /** S3's answer to a conditional write whose condition does not hold. */
        const val PRECONDITION_FAILED = 412
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

        // Packer's communicator interface for reaching the builder over SSM Session Manager
        const val SESSION_MANAGER_INTERFACE = "session_manager"

        // The Packer image used under the `ssm` SSH transport: built locally from SSM_DOCKERFILE_RESOURCE.
        // "localhost/" keeps the name identical under Docker and Podman, which prefixes local builds with it.
        const val SSM_IMAGE_NAME = "localhost/easy-db-lab/packer-ssm"
        const val SSM_DOCKERFILE_RESOURCE = "/com/rustyrazorblade/easydblab/containers/packer-ssm.Dockerfile"

        // Hex characters of the Dockerfile's SHA-256 used as the SSM image tag
        const val SSM_IMAGE_TAG_LENGTH = 12
    }

    // Event Bus configuration
    object EventBus {
        const val REDIS_URL_ENV_VAR = "EASY_DB_LAB_REDIS_URL"
        const val DEFAULT_CHANNEL = "easydblab-events"
    }

    // Environment variables
    object Environment {
        const val USER_DIR = "EASY_DB_LAB_USER_DIR"
        const val S3_BUCKET = "EASY_DB_LAB_S3_BUCKET"

        // When truthy, AMI builds leave the instance running on failure (packer -on-error=abort),
        // regardless of how the build was invoked (e.g. via profile setup, which has no flag).
        const val BUILD_KEEP_ON_FAILURE = "EASY_DB_LAB_BUILD_KEEP_ON_FAILURE"
    }

    // AWS configuration
    object AWS {
        const val DEFAULT_CREDENTIALS_NAME = "awscredentials"

        // The shared-credentials file the tool writes holds one profile under this name; the AWS CLI
        // (Packer, SSM sessions) is pointed at the file with SHARED_CREDENTIALS_FILE_ENV.
        const val CREDENTIALS_FILE_PROFILE = "default"
        const val SHARED_CREDENTIALS_FILE_ENV = "AWS_SHARED_CREDENTIALS_FILE"

        // Where the AWS CLI reads its config file. Static-key SSM sessions point it at
        // NO_CONFIG_FILE, which the CLI reads as no file at all, so ~/.aws/config cannot apply.
        const val CONFIG_FILE_ENV = "AWS_CONFIG_FILE"
        const val NO_CONFIG_FILE = "/dev/null"
        const val SSH_KEY_ENV = "EASY_DB_LAB_SSH_KEY"

        // AMI configuration
        const val AMI_PATTERN_TEMPLATE = "rustyrazorblade/images/easy-db-lab-cassandra-%s-*"

        // EC2 error code for instance ids it does not know yet; right after RunInstances this is
        // eventual consistency, not a missing instance.
        const val EC2_INSTANCE_NOT_FOUND = "InvalidInstanceID.NotFound"

        // IAM Role Names
        object Roles {
            const val EC2_INSTANCE_ROLE = "EasyDBLabEC2Role"
            const val EMR_SERVICE_ROLE = "EasyDBLabEMRServiceRole"
            const val EMR_EC2_ROLE = "EasyDBLabEMREC2Role"
        }

        // Inline policy names on the EC2 instance role
        object InlinePolicies {
            const val S3_ACCESS = "S3Access"
            const val SESSION_MANAGER = "SessionManagerInstance"
        }
    }

    /**
     * The account compactor: one ECS Fargate service per AWS account, in the account bucket's
     * region, that compacts the shared observability store.
     */
    object Compactor {
        const val ECS_CLUSTER = "easy-db-lab"
        const val SERVICE = "easy-db-lab-compactor"
        const val TASK_FAMILY = "easy-db-lab-compactor"
        const val LOG_GROUP = "/easy-db-lab/compactor"
        const val TASK_ROLE = "EasyDBLabCompactorTaskRole"
        const val EXECUTION_ROLE = "EasyDBLabCompactorExecutionRole"

        /** The ECS task-definition tag holding the hash of the configuration it was built from. */
        const val CONFIG_HASH_TAG = "easydblab.com/config-hash"

        /** Fargate task size: 2 vCPU, 8 GiB, 100 GiB of ephemeral storage. */
        const val CPU_UNITS = "2048"
        const val MEMORY_MIB = "8192"
        const val EPHEMERAL_STORAGE_GIB = 100
        const val STOP_TIMEOUT_SECONDS = 120

        /** How many of the newest log lines `observability compactor status` prints. */
        const val STATUS_LOG_LINES = 20

        /** How many of the service's newest events `observability compactor status` prints. */
        const val STATUS_SERVICE_EVENTS = 5

        /** Availability zone IDs where Fargate does not run; the compactor's subnet is never placed in one. */
        val NON_FARGATE_ZONE_IDS = setOf("use1-az3")

        /** The busybox image of the init container that writes the configuration files. */
        const val CONFIG_IMAGE = "busybox:1.37.0"

        /** The operator's IAM policy that grants the compactor's ECS, IAM and Logs actions. */
        const val OPERATOR_POLICY = "EasyDBLabCompactor"

        /** The error code ECS answers with when the caller's IAM does not allow the action. */
        const val ECS_ACCESS_DENIED = "AccessDeniedException"

        /** What the idle Tempo backend worker logs on every poll that finds no job: its normal state. */
        const val TEMPO_WORKER_IDLE_LOG =
            "msg=\"error calling scheduler\" err=\"rpc error: code = NotFound desc = no jobs found\""
    }

    // S3 configuration
    object S3 {
        /** Prefix for all easy-db-lab S3 buckets */
        const val BUCKET_PREFIX = "easy-db-lab-"

        /** Prefix for per-cluster data buckets */
        const val DATA_BUCKET_PREFIX = "easy-db-lab-data-"

        /** ID for the S3 bucket metrics configuration used for CloudWatch request metrics */
        const val METRICS_CONFIGURATION_ID = "easy-db-lab-request-metrics"

        /** Top-level prefix for all cluster data in the account bucket */
        const val CLUSTERS_PREFIX = "clusters"

        /** Maximum length for S3 metrics configuration IDs */
        const val MAX_METRICS_CONFIG_ID_LENGTH = 32

        /** S3's error code for a 409 that a concurrent conditional write to the same key caused. */
        const val CONDITIONAL_REQUEST_CONFLICT = "ConditionalRequestConflict"

        /** S3's error code for a request naming an object that does not exist. */
        const val NO_SUCH_KEY = "NoSuchKey"
    }

    /**
     * The observability store: the tenant every cluster belongs to, and the layout of its data in the
     * account bucket. Each backend's root sits at the top level of the bucket and is named for the
     * tool whose file format it holds. Mimir, Loki, Tempo and Pyroscope lay out their own tenant
     * directories under their root; the annotation backups carry the tenant in the path.
     */
    object Observability {
        /** Mimir's storage prefix in the account bucket; Mimir makes one directory per tenant under it. */
        const val METRICS_ROOT = "mimir"

        /** Loki's object prefix; Loki lays out its tenants' chunks and its index tables (`index/`) under it. */
        const val LOGS_ROOT = "loki"

        /** Tempo's backend prefix; Tempo makes one directory per tenant under it. */
        const val TRACES_ROOT = "tempo"

        /** The Pyroscope server's backend prefix; Pyroscope v2 lays out its own directories under it. */
        const val PROFILES_ROOT = "pyroscope"

        /** Root of the Grafana data easy-db-lab saves itself, such as the annotation backups. */
        const val GRAFANA_ROOT = "grafana"

        /** Directory of the Grafana annotation backups under [GRAFANA_ROOT]; the tenant follows it. */
        const val ANNOTATIONS_DIR = "annotations"

        /** Root of the reports: `reports/<tenant>/<experiment>/`, one folder per experiment. */
        const val REPORTS_ROOT = "reports"

        /**
         * The rule every segment of every path in a report must match. The path travels through a
         * URL, the documents web server and a signing proxy, and these characters need no encoding
         * in any of them.
         */
        const val REPORT_PATH_SEGMENT_PATTERN = "[A-Za-z0-9._-]+"

        /** The file list `report upload` generates at the root of every upload; no report may hold one there. */
        const val REPORT_FILE_LIST = "_files.html"

        /** The front page of an upload that holds `index.md` or `index.html` at its root. */
        const val REPORT_FRONT_PAGE = "index.html"

        /** The pointer at the root of an experiment's report folder; it leads to the newest upload. */
        const val REPORT_POINTER = "index.html"

        /** What the pointer of an experiment with no upload yet says. */
        const val REPORT_PLACEHOLDER_TEXT = "No report yet"

        /** The prefix of each upload's zip and folder: `report-<yyyyMMdd-HHmmss>`. */
        const val REPORT_UPLOAD_PREFIX = "report-"

        /**
         * How many times an upload re-reads and rewrites a pointer that other writers keep
         * changing between its read and its conditional write, before it fails.
         */
        const val REPORT_POINTER_MAX_ATTEMPTS = 10

        /**
         * How many later seconds an upload tries when the zip key of its second is taken, before it
         * fails. Each try is one conditional write.
         */
        const val REPORT_ZIP_KEY_MAX_ATTEMPTS = 60

        /**
         * How many times an upload looks at a key whose write was refused while the key holds
         * nothing (a write in flight there, possibly its own earlier attempt, or one that aborted)
         * before it gives the key up: each look reads the key back and, while it holds nothing,
         * writes it again. The pointer waits as many looks for a refused pointer to change.
         */
        const val REPORT_IN_FLIGHT_MAX_LOOKS = 10

        /** How long an upload waits between looks at a key with a write in flight. */
        const val REPORT_IN_FLIGHT_LOOK_INTERVAL_MS = 1000L

        /**
         * The start of every report zip's comment, which ends with its upload's random id: two
         * uploads of the same directory never build the same bytes.
         */
        const val REPORT_ZIP_COMMENT_PREFIX = "easy-db-lab report upload "

        /** The suffix of a report file that gets an HTML copy. */
        const val REPORT_MARKDOWN_SUFFIX = ".md"

        /** The tenant of a cluster that was initialized without `--tenant`. */
        const val DEFAULT_TENANT = "default"

        /** The rule every tenant name must match; valid as a directory in every backend. */
        const val TENANT_PATTERN = "^[a-z][a-z0-9_-]{0,62}$"

        /**
         * A tenant name [TENANT_PATTERN] allows but no cluster may use: a Loki tenant named `index`
         * would put its chunks under Loki's index path.
         */
        const val RESERVED_TENANT = "index"

        /** A cluster name, unanchored; [CLUSTER_NAME_PATTERN] and [CLUSTER_ID_PATTERN] anchor it. */
        private const val CLUSTER_NAME_CHARS = "[a-z][a-z0-9_-]{0,39}"

        /**
         * The rule every cluster name must match. The name flows raw into configuration files, Loki
         * index file names, S3 keys and metric labels, so it holds only characters all of them accept.
         * `_` is allowed, so a cluster can be named for the variant it tests (`unthrottled_cursor`);
         * a derived name that does not accept `_`, such as the OpenSearch domain name, converts it.
         */
        const val CLUSTER_NAME_PATTERN = "^$CLUSTER_NAME_CHARS$"

        /**
         * The rule every cluster id, the `cluster` label's `<name>-<id>`, matches: a cluster name, then
         * `-` and the cluster's lower-case UUID. A `--cluster` value goes into PromQL, LogQL and TraceQL
         * selectors, so anything else is refused.
         */
        const val CLUSTER_ID_PATTERN =
            "^$CLUSTER_NAME_CHARS-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

        /**
         * The rule every experiment name must match. A name may start with a digit, so a
         * `YYYY-MM-DD` prefix sorts experiments by date. No `/`: the picker value joins tenant and
         * name with one.
         */
        const val EXPERIMENT_PATTERN = "^[a-z0-9][a-z0-9_-]{0,79}$"

        /** The label every signal carries naming its experiment. */
        const val EXPERIMENT_LABEL = "experiment"

        /**
         * The label every signal carries naming its tenant. Not `tenant`: Tempo's own metrics
         * already carry a `tenant` label that the collector scrapes.
         */
        const val TENANT_LABEL = "edl_tenant"

        /** The header Tempo and Pyroscope read the tenant from. */
        const val TENANT_HEADER = "X-Scope-OrgID"
    }

    // Byte-size units for human-readable sizes
    object Size {
        const val BYTES_PER_MIB = 1024L * 1024
    }

    // Configuration file paths
    object ConfigPaths {
        // Local config files
        const val CASSANDRA_PATCH_FILE = "cassandra.patch.yaml"

        // Per-profile user settings, written under the active profile directory
        const val PROFILE_SETTINGS_FILE = "settings.yaml"
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
        const val NAMESPACE = "default"
        const val MANIFEST_DIR = "k8s"
        const val LOCAL_STORAGE_CLASS = "local-storage"
        const val LOCAL_STORAGE_WFC_CLASS = "local-storage-wfc"
        const val GRAFANA_PORT = 3000
        const val S3MANAGER_PORT = 8080
        const val REGISTRY_PORT = 5000
        const val TEMPO_PORT = 3200
        const val PYROSCOPE_PORT = 4040

        /** Tempo's `app.kubernetes.io/name` label; the collector finds the Tempo pod by it. */
        const val TEMPO_APP_LABEL = "tempo"

        /** The OTel collector's DaemonSet name and `app.kubernetes.io/name` label. */
        const val OTEL_COLLECTOR_APP_LABEL = "otel-collector"

        /** The Pyroscope server's `app.kubernetes.io/name` label; the collector finds its pod by it. */
        const val PYROSCOPE_APP_LABEL = "pyroscope"

        /** Mimir's `app.kubernetes.io/name` label and Service name; the collector finds its pod by it. */
        const val MIMIR_APP_LABEL = "mimir"

        /** Mimir's HTTP port: remote write (`/api/v1/push`), PromQL (`/prometheus`), `/ingester/shutdown`. */
        const val MIMIR_HTTP_PORT = 9009

        /** Mimir's gRPC port; not Tempo's 9096 or Pyroscope's 9095, since all share the control node's network. */
        const val MIMIR_GRPC_PORT = 9097

        /** Mimir's gossip listener, bound to loopback; Pyroscope holds the default 7946. */
        const val MIMIR_MEMBERLIST_PORT = 7947

        /** The memcached sidecar in the Mimir pod, on the control node's loopback: Mimir's caches. */
        const val MIMIR_MEMCACHED_PORT = 11211

        /** The memcached sidecar's image, a released version. */
        const val MIMIR_MEMCACHED_IMAGE = "memcached:1.6.34-alpine"

        /** Loki's `app.kubernetes.io/name` label and Service name; the collector finds its pod by it. */
        const val LOKI_APP_LABEL = "loki"

        /** Loki's HTTP port: OTLP push (`/otlp`), LogQL (`/loki/api/v1`), `/ingester/shutdown`. */
        const val LOKI_HTTP_PORT = 3100

        /** Loki's gRPC port; clear of Pyroscope's 9095, Tempo's 9096 and Mimir's 9097 on the control node. */
        const val LOKI_GRPC_PORT = 9098
        const val BEYLA_METRICS_PORT = 9400
        const val EBPF_EXPORTER_METRICS_PORT = 9435
        const val OTEL_GRPC_PORT = 4317
        const val OTEL_HTTP_PORT = 4318
        const val OTEL_HEALTH_PORT = 13133
        const val TEMPO_OTLP_GRPC_PORT = 4320
        const val TEMPO_OTLP_HTTP_PORT = 4321

        // Jaeger Thrift Compact (UDP) — used by TiDB's OpenTracing reporter to export traces
        // via the OTel Collector Jaeger receiver, since TiDB v8.5.x has no native OTel support.
        const val JAEGER_THRIFT_COMPACT_PORT = 6831
        const val CLEANUP_JOB_TTL_SECONDS = 300

        // Max time `up` waits for every observability pod in the `default` namespace to reach Ready
        // after the stack is applied. Generous because Grafana plugin installs and first-boot image
        // pulls are slow; the wait is fail-fast on CrashLoopBackOff/ImagePullBackOff, so a broken
        // stack aborts well before this ceiling.
        const val OBSERVABILITY_READY_TIMEOUT_SECONDS = 300
        const val DB_MOUNT_PATH = "/mnt/db1"

        // Labels used by the metrics registry system.
        // OtelManifestBuilder reads ConfigMaps with WORKLOAD_METRICS_LABEL=true.
        // MetricsRegistryService writes both labels; deregister deletes by both.
        const val WORKLOAD_METRICS_LABEL = "easydblab.com/workload-metrics"
        const val KIT_LABEL = "easydblab.com/kit"

        /** Pod-template annotation carrying a hash of the ConfigMaps a workload reads; see ConfigHashAnnotator. */
        const val CONFIG_HASH_ANNOTATION = "easydblab.com/config-hash"
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

        // CreateDomain's refusal while OpenSearch cannot yet see a just-created service-linked
        // role. Both the code and this part of the message must match before it is retried.
        const val VALIDATION_ERROR_CODE = "ValidationException"
        const val SERVICE_LINKED_ROLE_NOT_VISIBLE = "you must enable a service-linked role"

        // CreateDomain right after this run created the service-linked role: OpenSearch does
        // not see the new role at once. 12 attempts, 5s apart (about a minute in all).
        const val SERVICE_LINKED_ROLE_MAX_ATTEMPTS = 12
        const val SERVICE_LINKED_ROLE_RETRY_INTERVAL_MS = 5_000L
    }

    // Kit configuration
    object Kit {
        const val CONFIG_FILE = "kit.yaml"

        // Lifecycle phase names
        const val PHASE_INSTALL = "install"
        const val PHASE_START = "start"
        const val PHASE_STOP = "stop"
        const val PHASE_UNINSTALL = "uninstall"
        const val PHASE_BACKUP = "backup"
        const val PHASE_RESTORE = "restore"

        /** Lines of a failed shell step's output repeated in its failure report. */
        const val SHELL_STEP_OUTPUT_TAIL_LINES = 20

        // File written by kit install to record the resolved arg values; read by subsequent
        // phases (start, stop, etc.) so they use the installed values instead of kit defaults.
        const val RESOLVED_ARGS_FILE = "kit-resolved-args.env"

        /** The variable that holds the absolute path of the workspace's kit shell library, for every kit process. */
        const val LIBRARY_ENV = "EDL_KIT_LIB"

        // After the stop of a kit that declares a runtime, how often and how many times to look for its
        // pods before reporting the stop incomplete: 2s x 150 = 5 minutes, enough for a
        // StatefulSet's or operator's cascading delete plus each pod's termination grace period.
        val STOP_WAIT_POLL_INTERVAL: java.time.Duration = java.time.Duration.ofSeconds(2)
        const val STOP_WAIT_MAX_POLLS = 150

        val SHELL_VAR_PATTERN = Regex("""\$\{(\w+)}""")
    }

    // YACE (Yet Another CloudWatch Exporter) configuration
    object Yace {
        const val IMAGE = "quay.io/prometheuscommunity/yet-another-cloudwatch-exporter:v0.63.0"
        const val PORT = 5001
        const val CONFIGMAP_NAME = "yace-config"
        const val DEPLOYMENT_NAME = "yace"
    }

    // Cilium CNI (the default; `init --cni=flannel` opts out; see CiliumService and PlatformCni)
    object Cilium {
        const val VERSION = "1.19.4"
        const val NAMESPACE = "kube-system"

        // The agent and the operator both run hostNetwork, so their metrics ports are node ports.
        // The agent is a DaemonSet: every collector scrapes its own node's agent at localhost.
        // The operator runs on exactly one node, so it is discovered through pod SD instead.
        const val AGENT_METRICS_PORT = 9962
        const val OPERATOR_METRICS_PORT = 9963
        const val HUBBLE_METRICS_PORT = 9965

        // Pod label the Cilium chart stamps on the operator Deployment's pods.
        const val OPERATOR_POD_LABEL = "io.cilium/app"
        const val OPERATOR_POD_LABEL_VALUE = "operator"

        // Hubble UI is exposed as a NodePort so it is reachable at <node private IP>:<port> over
        // the tailnet or the SOCKS tunnel with no port-forward. Chosen outside every NodePort a kit
        // declares (grep `nodePort` under kits/ before changing it).
        const val HUBBLE_UI_NODE_PORT = 31234

        // Name of the ConfigMap the Cilium chart renders its effective agent configuration into.
        const val CONFIG_MAP_NAME = "cilium-config"

        // Tag on the Grafana annotations that mark the Cilium install window.
        const val ANNOTATION_TAG = "cilium"

        // The node fixes packer/base/install/configure_cilium_eni_networkd.sh bakes into the base
        // AMI. A node launched from an older AMI lacks them, and Cilium's runtime-attached ENIs
        // then take the node off the network. `up` checks for them before installing Cilium.
        val NODE_FIX_FILES =
            listOf(
                "/etc/systemd/network/05-cilium-eni-primary.network",
                "/etc/systemd/network/06-cilium-eni-unmanaged.network",
                "/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg",
            )
    }

    // kube-state-metrics (K8s object state as Prometheus metrics, scraped by the OTel collector)
    object KubeStateMetrics {
        const val IMAGE = "registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.20.0"
        const val NAME = "kube-state-metrics"
        const val METRICS_PORT = 8080
        const val TELEMETRY_PORT = 8081
    }

    // OTel Java Agent configuration (for EMR Spark JVMs)
    object OtelJavaAgent {
        const val VERSION = "2.31.1"
        const val DOWNLOAD_URL =
            "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$VERSION/opentelemetry-javaagent.jar"
        const val INSTALL_PATH = "/opt/otel/opentelemetry-javaagent.jar"
    }

    // OTel Collector version: the cluster DaemonSet, the stress-job sidecar, and the EMR binary.
    object OtelCollector {
        const val VERSION = "0.161.0"
        const val DOWNLOAD_URL =
            "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v$VERSION/otelcol-contrib_${VERSION}_linux_amd64.tar.gz"
        const val INSTALL_PATH = "/opt/otel/otelcol-contrib"
        const val CONFIG_PATH = "/opt/otel/config.yaml"
        const val SERVICE_NAME = "otel-collector"
    }

    // Pyroscope Java Agent configuration (for EMR Spark JVMs)
    //
    // Cassandra nodes no longer use this agent — they profile at runtime with async-profiler, see
    // Constants.Profiling. The remaining JVM workloads (EMR/Spark, Trino, the sidecar, stress) still
    // do, and they install the jar at two different paths: EMR bootstraps it to /opt/pyroscope,
    // while the node AMI installs it to /usr/local/pyroscope (hostPath-mounted into stress pods by
    // StressJobService). The name says EMR so one constant cannot be mistaken for both.
    object PyroscopeJavaAgent {
        const val VERSION = "2.3.0"
        const val DOWNLOAD_URL =
            "https://github.com/grafana/pyroscope-java/releases/download/v$VERSION/pyroscope.jar"
        const val EMR_INSTALL_PATH = "/opt/pyroscope/pyroscope.jar"
    }

    // Probe timings for the in-cluster Pyroscope 2.x server.
    //
    // Pyroscope 2.3.1 answers /ready with 503 through two fixed waits that run back to back. First the
    // metastore's min_ready_duration, 15 s (pkg/metastore/metastore.go:50, checked at :292). Then the
    // segment writer's, which the single binary overrides to 30 s (pkg/pyroscope/pyroscope.go:371).
    // They add rather than overlap: the /ready handler returns at the first component that is not
    // ready (pkg/pyroscope/pyroscope.go:893-911), and the segment writer's dskit lifecycler starts its
    // 30 s count only on its first passing check (dskit ring/lifecycler.go:271-277). Measured from
    // container start: 48 s on a fresh data directory, 47 s on a restart over existing data. The
    // Pyroscope 1.x liveness probe (30 s delay, then 3 x 15 s) killed it during that window.
    object PyroscopeProbes {
        /** How often the startup probe polls /ready while the server comes up. */
        const val STARTUP_PERIOD_SECONDS = 5

        /**
         * Startup probe failures allowed: 36 x 5 s = 180 s, 3.75x the measured 48 s, for a control
         * node that is also starting the rest of the observability stack. Liveness and readiness
         * do not run until this probe has passed, so neither needs an initial delay.
         */
        const val STARTUP_FAILURE_THRESHOLD = 36

        /**
         * Liveness polls /ready once startup has passed. Pyroscope 2.3.1 documents no separate
         * liveness endpoint (its Helm chart sets only a /ready readiness probe), and past startup
         * /ready carries no timed wait: the segment writer's readiness latches and the metastore's
         * min-ready clock is set once.
         */
        const val LIVENESS_PERIOD_SECONDS = 15

        /** How often readiness polls /ready once startup has passed. */
        const val READINESS_PERIOD_SECONDS = 10
    }

    // Runtime async-profiler control for Cassandra nodes.
    //
    // Capture is driven by a node-resident reconciler (`edl-profiling-reconcile`, run by a systemd
    // timer) which attaches async-profiler to the live Cassandra JVM, ships completed JFR chunks to
    // Pyroscope, and prunes the profile directory. The CLI only writes DESIRED_STATE_PATH; the
    // reconciler owns everything else.
    object Profiling {
        /** Where completed and in-flight JFR chunks live. Deliberately NOT the 777 artifacts dir. */
        const val PROFILE_DIR = "/mnt/db1/cassandra/profiles"

        /** Directory holding DESIRED_STATE_PATH; created by the CLI before the first write. */
        const val DESIRED_STATE_DIR = Cassandra.NODE_CONFIG_DIR

        /** Desired state, written atomically by the CLI and read by the reconciler. */
        const val DESIRED_STATE_PATH = "$DESIRED_STATE_DIR/profiling.json"

        /** Effective state, rewritten by the reconciler at the end of every pass. */
        const val EFFECTIVE_STATE_PATH = "$PROFILE_DIR/effective-state.json"

        const val JFRCONV_BIN = "/usr/local/async-profiler/bin/jfrconv"

        /** Systemd timer driving the reconciler. */
        const val TIMER_UNIT = "edl-profiling-reconcile.timer"

        /** How often that timer fires. Mirrors `OnUnitActiveSec` in `edl-profiling-reconcile.timer`. */
        const val RECONCILE_INTERVAL_SECONDS = 60L

        /**
         * Age past which a node's effective-state document stops describing the present.
         *
         * The document is rewritten every pass, so with a healthy timer it is never more than one
         * interval old. Five intervals is also `TimeoutStartSec` on the reconcile unit, so a pass
         * being killed at that timeout is the shortest real fault this threshold can see — anything
         * older means the timer is masked, disabled, or the unit is wedged.
         */
        const val STALE_AFTER_SECONDS = 5 * RECONCILE_INTERVAL_SECONDS

        /**
         * JFR rotation interval handed to `asprof --loop`.
         *
         * Each rotation is one Pyroscope upload. On a test cluster, Pyroscope rejected 60-second
         * chunks of the seeded CPU, allocation and lock events, so no profile reached it; 15-second
         * chunks are accepted. Mirrors `FALLBACK_LOOP_INTERVAL` in `edl-profiling-reconcile`.
         */
        const val DEFAULT_LOOP_INTERVAL = "15s"

        /** Age bound on shipped chunks, in minutes. Unshipped and rejected chunks are never pruned. */
        const val DEFAULT_RETENTION_MINUTES = 60

        /** Byte ceiling on the profile directory. Generous: if it engages, that is itself a signal. */
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * Smallest retention window the node can honour. Zero puts the prune cutoff at the current
         * instant, deleting every chunk as it lands; negative makes the reconciler discard the whole
         * desired-state document without attaching.
         */
        const val MIN_RETENTION_MINUTES = 1

        /**
         * Smallest byte ceiling the node can honour.
         *
         * One MiB rather than one byte, because the byte bound loses data the same way a too-short
         * retention window does and needs the same floor. A JFR chunk carries its own constant pool
         * and runs to hundreds of kilobytes, so any ceiling below this cannot hold a single chunk:
         * every pass prunes what the previous one collected, and profiling can never produce a
         * profile. A one-byte floor accepted exactly that configuration.
         */
        const val MIN_MAX_BYTES = 1L * 1024 * 1024

        /**
         * Margin the node adds to the rotation interval before a chunk counts as complete.
         *
         * Mirrors `GRACE_EXTRA_SECONDS` in `edl-profiling-reconcile`; the two must stay equal. It
         * lives here as well as there because [requireProfilingBounds] uses it to refuse a
         * retention window shorter than the window a chunk needs in order to be shipped at all.
         */
        const val SHIP_GRACE_SECONDS = 10L

        /**
         * The most chunks one reconcile pass uploads.
         *
         * Mirrors `SHIP_MAX_CHUNKS_PER_PASS` in `edl-profiling-reconcile`; the two must stay equal.
         * The cap is what keeps a pass inside the unit's `TimeoutStartSec` of 300 seconds — six
         * uploads at the node's 30-second upload timeout is 180 seconds worst case, leaving room for
         * the metrics export and the directory walk — so it cannot simply be raised to match a
         * faster rotation. [MIN_LOOP_SECONDS] is the other side of that trade.
         */
        const val SHIP_MAX_CHUNKS_PER_PASS = 6L

        /**
         * Fastest rotation the shipper can keep up with.
         *
         * A pass ships at most [SHIP_MAX_CHUNKS_PER_PASS] chunks and runs every
         * [RECONCILE_INTERVAL_SECONDS], while the profiler produces one chunk per rotation. Any
         * interval below this ratio produces more chunks per pass than a pass can drain, so the
         * queue grows forever until the directory reaches its byte bound and the node stops
         * recording. Until then nothing reports a fault: the per-pass truncation warning is
         * indistinguishable from a backlog that is draining normally.
         *
         * Refused at the CLI rather than on the node, for the same reason as [SHIP_GRACE_SECONDS]:
         * the failure is silent until profiling stops, and the CLI is the one place every route
         * passes through.
         */
        const val MIN_LOOP_SECONDS = RECONCILE_INTERVAL_SECONDS / SHIP_MAX_CHUNKS_PER_PASS

        /**
         * Event set seeded at cluster-up, so a fresh cluster profiles with no operator action.
         *
         * CPU, allocation and lock contention together — the same three the Pyroscope Java agent
         * this replaced produced unconditionally, and what `dashboards/profiling.json`'s Memory,
         * Lock and Mutex panels read. Only wall-clock sampling is exclusive of a CPU event; alloc
         * and lock coexist with cpu in one session, so this is a single recording, not a compromise.
         */
        val DEFAULT_ASPROF_ARGS = listOf("-e", "cpu", "--alloc", "512k", "--lock", "10ms")

        /** Default number of completed chunks `fetch` and `flamegraph` operate on. */
        const val DEFAULT_LAST_CHUNKS = 5

        /** Local workspace directory downloads land in. */
        const val LOCAL_DOWNLOAD_DIR = "profiles"

        /** Where node-side flame-graph conversions are written; world-writable, never auto-pruned. */
        const val ARTIFACTS_DIR = "/mnt/db1/cassandra/artifacts"

        /** Default flame-graph output format handed to `jfrconv -o`. */
        const val DEFAULT_FLAMEGRAPH_FORMAT = "html"
    }

    // Cassandra node configuration
    object Cassandra {
        /** UID of the cassandra OS user — must match the cassandra-sidecar container image */
        const val USER_ID = 999L

        /** Python version cqlsh runs under when a version declares none — matches every shipped entry */
        const val DEFAULT_PYTHON_VERSION = "3.11.9"

        /** Node-side directory easy-db-lab writes its own configuration into. */
        const val NODE_CONFIG_DIR = "/etc/easy-db-lab"

        /**
         * JMX Metric Insight rules the OTel Java agent reads on each Cassandra node.
         *
         * The file is a classpath resource, extracted into the cluster workspace by `init` and
         * uploaded by `setup-instances`. `cassandra.in.sh` names the same path in
         * `-Dotel.jmx.config`, so the two must not drift.
         *
         * It is the agent's only rule source: no built-in target is selected, so every metric
         * name the dashboards query is pinned in this repo.
         */
        const val JMX_RULES_FILE = "cassandra-jmx-rules.yaml"

        /** Where [JMX_RULES_FILE] lands on a Cassandra node. */
        const val JMX_RULES_PATH = "$NODE_CONFIG_DIR/$JMX_RULES_FILE"
    }

    // Cassandra stress testing configuration
    object Stress {
        const val NAMESPACE = "default"
        const val IMAGE = "ghcr.io/apache/cassandra-easy-stress:latest"
        const val LABEL_KEY = "app.kubernetes.io/name"
        const val LABEL_VALUE = "cassandra-easy-stress"
        const val DEFAULT_CASSANDRA_PORT = 9042
        const val PROMETHEUS_PORT = 9500

        /**
         * The share of stress requests the OpenTelemetry agent traces. At 100% a stress job sent
         * 5,000 spans/s, and Tempo used about 1.5 of the control node's 4 cores to take them in.
         * Span-metric request rates for cassandra-easy-stress therefore read at this ratio.
         */
        const val TRACE_SAMPLE_RATIO = "0.01"
    }

    /** The flush of Loki and Mimir that `down` runs before any infrastructure is torn down. */
    object TeardownFlush {
        /** A backend's synchronous `/ingester/shutdown` may take this long to flush everything. */
        const val SHUTDOWN_TIMEOUT_SECONDS = 600L

        /** A backend's pod may take this long to go once scaled to 0; its grace period is 600s. */
        const val SCALE_DOWN_TIMEOUT_SECONDS = 660L

        /** How often a wait on a backend's pods looks again. */
        const val POLL_INTERVAL_SECONDS = 5L

        /** The OTel collector's pods may take this long to go once its DaemonSet is deleted. */
        const val SENDERS_STOP_TIMEOUT_SECONDS = 120L

        /** Tempo may take this long to hold no live trace and to upload every local block. */
        const val TEMPO_DRAIN_TIMEOUT_SECONDS = 300L

        /** The least time between the two readings of Tempo's created-traces counter that must agree. */
        const val TEMPO_STABLE_READING_GAP_SECONDS = 2L
    }

    /** Loki, the logs backend. */
    object Loki {
        /** The `source` stream label of the Grafana annotations mirrored into Loki. */
        const val ANNOTATION_SOURCE = "annotation"

        /**
         * The `logs query --source` value for Cassandra's application logs. They arrive only over
         * OTLP from the Java agent, so it selects [CASSANDRA_SERVICE_NAME], not a `source` label.
         */
        const val CASSANDRA_SOURCE = "cassandra"

        /** The `service_name` the Cassandra JVM's Java agent logs under (`-Dotel.service.name`). */
        const val CASSANDRA_SERVICE_NAME = "cassandra"

        /** Loki refuses an entry older than this many hours (`reject_old_samples_max_age` in `loki.yaml`). */
        const val MAX_ENTRY_AGE_HOURS = 8760L

        /** Loki refuses an entry more than this many hours in the future (`creation_grace_period` in `loki.yaml`). */
        const val MAX_ENTRY_AHEAD_HOURS = 24L

        /** How far inside Loki's window an entry must fall to be pushed, so the clock between the tool and Loki cannot tip it out. */
        const val ENTRY_WINDOW_MARGIN_MINUTES = 1L

        /**
         * Age beyond which a pushed entry may be outside the window Loki's querier asks its ingester
         * about (`query_ingesters_within`, Loki's default 3h, left unset in `loki.yaml`). An entry
         * outside that window is answered from the store alone, so it is invisible until its chunk is
         * flushed. 2h is deliberately earlier than the 3h cutoff, so a flush happens whenever an
         * entry might be outside the ingester window.
         */
        const val INGESTER_QUERY_WINDOW_HOURS = 2L

        /** How many times the mirror looks for a flushed backdated annotation before it fails. */
        const val QUERYABLE_LOOKS = 60

        /** Seconds between two looks for a flushed backdated annotation. */
        const val QUERYABLE_INTERVAL_SECONDS = 1L
    }

    /** The MCP server's live metrics stream. */
    object LiveMetrics {
        /** Seconds between two collections of the live metrics stream. */
        const val COLLECTION_INTERVAL_SECONDS = 5L
    }

    // Grafana configuration
    object Grafana {
        /**
         * Path, relative to the dashboard tree root, of the core dashboard Grafana opens on
         * (`GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH`). The catalog fails unless exactly this
         * path is present; the same file name in another folder does not count.
         */
        const val HOME_DASHBOARD_PATH = "infrastructure/system-overview.json"

        /**
         * Grafana's server admin. Every Grafana this tool runs lets anyone in as an org admin, but the
         * provisioning reload API (`/api/admin/provisioning/...`) needs the server admin, by basic auth.
         */
        const val ADMIN_USER = "admin"

        /** [ADMIN_USER]'s password. The Grafanas are ephemeral and bound to private or loopback addresses. */
        const val ADMIN_PASSWORD = "admin"

        /**
         * Tag auto-applied to every GLOBAL (unscoped) annotation created via `grafana annotate`, in
         * addition to any operator-supplied tags. The core dashboards' annotation query filters on
         * this tag so global markers render on their timelines. Scoped annotations
         * (`--dashboard`/`--panel`) are NOT auto-tagged; they render on their target dashboard. See
         * design in `openspec/changes/issue-939`.
         */
        const val GLOBAL_ANNOTATION_TAG = "easydblab"

        /**
         * The `limit` sent on `GET /api/annotations` when backing up annotations. Grafana defaults
         * this to 100 and gives no pagination cursor, so a backup that relied on the default would
         * silently capture at most 100 annotations. The fetch asks for this high explicit limit
         * instead, and fails loudly if the response fills it exactly, rather than reporting a
         * truncated capture as a complete backup. See design in `openspec/changes/issue-939`.
         */
        const val ANNOTATION_FETCH_LIMIT = 5000

        /** Grafana refuses a datasource UID longer than this. */
        const val MAX_UID_LENGTH = 40

        /**
         * The environment variable that sets `disable_sanitize_html`. Grafana reads the setting from
         * `[panels]`, so a Text panel keeps the documents iframe only when this is `true`.
         */
        const val DISABLE_SANITIZE_HTML_ENV = "GF_PANELS_DISABLE_SANITIZE_HTML"

        /**
         * The uids of the provisioned datasources. Dashboards, links between datasources and the
         * annotation queries name a datasource by its uid, so these are part of every dashboard.
         */
        object DatasourceUid {
            const val MIMIR = "mimir"
            const val LOKI = "loki"
            const val TEMPO = "tempo"
            const val PYROSCOPE = "pyroscope"
        }

        /**
         * The test documents Grafana shows: a read-only web server on the control node's host
         * network, and the signing proxy it forwards to, bound to loopback. 8081 is the image
         * renderer's.
         */
        object Documents {
            /** Host port of the read-only documents web server, reachable from the browser like Grafana. */
            const val WEB_PORT = 3080

            /** Loopback port of the `aws-sigv4-proxy` that signs the web server's S3 requests. */
            const val PROXY_PORT = 3081

            /** The signing proxy, a released version. */
            const val SIGV4_PROXY_IMAGE = "public.ecr.aws/aws-observability/aws-sigv4-proxy:1.13"

            /** The read-only web server, a released stable nginx that runs as a non-root user. */
            const val WEB_SERVER_IMAGE = "nginxinc/nginx-unprivileged:1.30.5-alpine"
        }
    }

    /**
     * The local observability stack: a docker compose project on the operator's machine that reads
     * the shared store (`observability local create`), and the export's temporary stacks.
     */
    object LocalStack {
        /** The address every published port of a local stack binds, and the address this tool reaches it on. */
        const val LOOPBACK = "127.0.0.1"

        /**
         * The read gate's web server, which the cluster does not run: a released stable nginx that
         * runs as a non-root user. The store check runs in it too, for its `curl`.
         */
        const val GATE_IMAGE = "nginxinc/nginx-unprivileged:1.30.5-alpine"

        /**
         * A statically linked busybox, mounted into the signer for its healthcheck: the signer's
         * image is built from scratch and has no shell or HTTP client of its own.
         */
        const val HEALTHCHECK_TOOLS_IMAGE = "busybox:1.37.0-musl"

        /** thanos, which rewrites Mimir blocks for a query export; a released version. */
        const val THANOS_IMAGE = "quay.io/thanos/thanos:v0.42.4"

        /** The base compose file; a user's `compose.override.yml` beside it merges automatically. */
        const val COMPOSE_FILE = "compose.yml"

        /** The user's own compose file. The tool never opens, writes or removes it. */
        const val OVERRIDE_FILE = "compose.override.yml"

        /** The manifest of the files the tool owns in a stack directory. */
        const val MANIFEST_FILE = ".easy-db-lab-local-stack.json"

        /** Directories that hold data or state, which the tool never opens, writes or removes. */
        val PROTECTED_DIRS = listOf("data", "cache", "grafana-data")

        /** The directory the components keep their working state in, one subdirectory each. */
        const val CACHE_DIR = "cache"

        /** Grafana's data directory (its database and installed plugins). */
        const val GRAFANA_DATA_DIR = "grafana-data"

        /** The directory holding the provisioned dashboard tree, and the staging trees beside it. */
        const val DASHBOARDS_DIR = "dashboards"

        /** Where [DASHBOARDS_DIR] is mounted in the Grafana container. */
        const val DASHBOARDS_MOUNT = "/var/lib/easy-db-lab/dashboards"

        /** The provisioned tree under [DASHBOARDS_DIR], which Grafana's dashboard provider sweeps. */
        const val DASHBOARDS_TREE = "current"

        /** The port the read gate and the signer listen on inside the project network. */
        const val STORE_PORT = 8080

        /**
         * The signer's `HOME`. Its image sets none, and aws-sdk-go v1 finds `~/.aws` from `$HOME`:
         * without it the read-only mount at `/root/.aws` is never read and every request fails with
         * `NoCredentialProviders`.
         */
        const val SIGNER_HOME = "/root"

        /** How often the signer's healthcheck reads the bucket, so expired credentials show as unhealthy. */
        const val SIGNER_HEALTHCHECK_INTERVAL = "60s"

        /**
         * aws-sdk-go-v2's switches for the checksum headers it adds to S3 requests. Loki 3.7.8 uses that
         * SDK, and by default asks for response checksum validation with an `x-amz-checksum-mode` header
         * on every GetObject; the signer (aws-sigv4-proxy 1.13) does not sign that header, so S3 refuses
         * the request. Set to [AWS_CHECKSUM_WHEN_REQUIRED], the SDK sends checksum headers only for an
         * operation that requires one, and no read does.
         */
        const val AWS_RESPONSE_CHECKSUM_VALIDATION_ENV = "AWS_RESPONSE_CHECKSUM_VALIDATION"

        /** See [AWS_RESPONSE_CHECKSUM_VALIDATION_ENV]: the request side of the same SDK switch. */
        const val AWS_REQUEST_CHECKSUM_CALCULATION_ENV = "AWS_REQUEST_CHECKSUM_CALCULATION"

        /** The value of both checksum switches: only when the operation requires a checksum. */
        const val AWS_CHECKSUM_WHEN_REQUIRED = "when_required"

        /**
         * How long a cold read over older data may take in a local stack or the export's reader: a
         * local Grafana's wait for a datasource (`GF_DATAPROXY_TIMEOUT`) and the local Tempo's server
         * and querier timeouts (`TempoLocalConfig`). Such a read fetches every block and chunk it
         * needs from S3 and outlasted Grafana's 30-second default and Tempo 3.0.3's 30s (10s by id),
         * which showed as 502s in Grafana and 500s to the export. A cluster keeps the defaults.
         */
        const val COLD_READ_TIMEOUT_SECONDS = 300

        /**
         * The stack's memcached, which holds Mimir's caches and the live Tempo's block reads. A
         * trace-by-id fetch reads every page of the block holding the trace (an 83 MB block took
         * about 183 range reads and 57 MB from S3, per fetch); cached, the next fetches from that
         * block read nothing from the store. memcached takes memory only as it fills.
         */
        const val MEMCACHED_MEMORY_MB = 4096

        /**
         * memcached's largest item. Tempo reads a block in 1 MiB buffers and stores each one as an
         * item; at the cluster's 1m limit memcached refused every one of them.
         */
        const val MEMCACHED_MAX_ITEM_MB = 16

        /** The connections memcached accepts, and its threads: the cluster's sidecar settings. */
        const val MEMCACHED_MAX_CONNECTIONS = 4096
        const val MEMCACHED_THREADS = 4

        /**
         * The largest item the live Tempo stores, below [MEMCACHED_MAX_ITEM_MB], whose limit also
         * counts the key and item header, so Tempo skips a larger one instead of logging a refused set.
         */
        const val TEMPO_CACHE_MAX_ITEM_BYTES = (MEMCACHED_MAX_ITEM_MB - 1) * 1024 * 1024

        /**
         * The live Tempo's idle connections to memcached. At Tempo's default of 16, eight parallel
         * trace fetches opened and closed connections until the container ran out of local ports, and
         * those reads went to the store.
         */
        const val TEMPO_CACHE_MAX_IDLE_CONNECTIONS = 512

        /** How long the live Tempo waits for memcached before it reads the store instead (its default is 100ms). */
        const val TEMPO_CACHE_TIMEOUT = "1s"

        /** Grafana's localhost port, unless `create` is given another. */
        const val DEFAULT_GRAFANA_PORT = 3000

        /** The documents web server's localhost port, unless `create` is given another. */
        const val DEFAULT_DOCUMENTS_PORT = 3080

        /** The compose service names, which are also the host names inside the project network. */
        object Services {
            const val MIMIR = "mimir"
            const val MEMCACHED = "memcached"
            const val LOKI = "loki"
            const val TEMPO = "tempo"
            const val GRAFANA = "grafana"
            const val DOCUMENTS = "documents-web"
            const val STORE_GATE = "store-gate"
            const val STORE_SIGNER = "store-signer"
            const val STORE_CHECK = "store-check"

            /** No service answers it until profiles join the stack (issue 1016); the datasource set names it. */
            const val PYROSCOPE = "pyroscope"
        }

        /**
         * The networks of a stack that reads the live store. The signer signs any request with the
         * operator's credentials, so it shares a network only with the gate and the store check.
         */
        object Networks {
            /** The project's own network, which every service but the signer and the store check joins. */
            const val DEFAULT = "default"

            /** The signer, the gate and the store check; internal, so it has no way out. */
            const val STORE_ACCESS = "store-access"

            /** The signer's way out to S3, which no other service joins. */
            const val STORE_EGRESS = "store-egress"
        }
    }

    /** `observability export`: a zip of a tenant's or some clusters' data that opens offline as a local stack. */
    object Export {
        /** The only extension an export's path may have; the zip's one directory is the name without it. */
        const val ZIP_EXTENSION = ".zip"

        /** Between the path and a random suffix: the name the zip is written under until it is complete. */
        const val PARTIAL_INFIX = ".partial-"

        /** The buffer one object is streamed into the zip through. */
        const val COPY_BUFFER_BYTES = 1024 * 1024

        /** The directory of the stack the copied store sits under, mounted read-only into every backend. */
        const val DATA_DIR = "data"

        /** The file at the root of the stack that says how to open it and which images it needs. */
        const val README_FILE = "README.txt"

        /** The issue that brings profiles to the local stack and the export. */
        const val PROFILES_ISSUE = 1016

        /** How many times one Loki index table is copied again after an object listed in it went away mid-copy. */
        const val LOKI_TABLE_ATTEMPTS = 5

        /** Between the zip's path and a random suffix: the export's work directory, kept afterwards. */
        const val WORK_INFIX = ".work-"

        /** A tenant export's split of Loki's shared index files, by Loki's own compactor in docker (owner decision 22). */
        object LokiSplit {
            /**
             * What Loki 3.7.8 logs once its one compaction (`-compactor.run-once`) over every table has
             * ended, whether it succeeded or not; the process then waits for a signal and never exits.
             */
            const val RUN_DONE = "msg=\"single compaction finished\""

            /** What Loki 3.7.8 logs, before [RUN_DONE], when its one compaction failed. */
            const val RUN_FAILED = "msg=\"compaction encountered an error\""

            /** How long the one compaction may take. */
            const val TIMEOUT_MINUTES = 60L

            /** The gap between two looks at the compactor's log. */
            const val POLL_SECONDS = 2L

            /**
             * Where Loki's memberlist binds and advertises. With no network the container has no
             * interface address, and Loki exits at start ("no useable address found").
             */
            const val MEMBERLIST_ADDRESS = "127.0.0.1"
        }

        /** Exports by query (`--cluster`, `--experiment`): the temporary reader and writer stacks. */
        object Query {
            /** Each temporary stack's `docker compose logs`, saved in its directory before the stack is removed. */
            const val STACK_LOG_FILE = "compose.log"

            /** Joins the tenants of a federated request in `X-Scope-OrgID`. */
            const val TENANT_SEPARATOR = "|"

            /** The label a federated Mimir answer gives each series, naming its tenant. */
            const val TENANT_ID_LABEL = "__tenant_id__"

            /** The lines one forward page of a log export asks Loki for. */
            const val LOG_PAGE_LINES = 5000

            /**
             * The most lines Loki answers in one query, `max_entries_limit_per_query` in `loki.yaml`:
             * what a log export asks for when a whole page shares one nanosecond.
             */
            const val LOG_MAX_LINES = 100_000

            /** The Loki request header that asks for each line's structured metadata apart from the stream labels. */
            const val LOKI_ENCODING_FLAGS_HEADER = "X-Loki-Response-Encoding-Flags"

            /** The value of [LOKI_ENCODING_FLAGS_HEADER] that categorizes the labels. */
            const val LOKI_CATEGORIZE_LABELS = "categorize-labels"

            /**
             * The traces one search of a trace export asks Tempo for; a window that returns this many is
             * split. A search costs about the same whatever its window, since Tempo scans every block the
             * window overlaps (a live export took about 5s per search, from a 100s window to a 7h one), so
             * the number of searches is the cost: at 1000, 146k traces took 475 searches and 43 minutes.
             * Tempo 3.0.3 answers up to `query_frontend.search.max_result_limit`, 262144.
             */
            const val TRACE_SEARCH_LIMIT = 20_000

            /** A trace export reports its fetch progress after this many more traces... */
            const val TRACE_PROGRESS_EVERY_TRACES = 1000

            /** ...or this many seconds after its last report, whichever comes first. */
            const val TRACE_PROGRESS_INTERVAL_SECONDS = 30L

            /**
             * How much longer a trace export waits for the reader's Tempo than Tempo waits for a cold
             * read (`LocalStack.COLD_READ_TIMEOUT_SECONDS`), so Tempo's own answer arrives first.
             */
            const val TEMPO_ANSWER_MARGIN_SECONDS = 30L

            /** How many trace searches, and how many fetches and replays, a trace export runs at once. */
            const val TRACE_EXPORT_PARALLELISM = 8

            /** The longest trace search window, Tempo's `query_frontend.search.max_duration` (spike S3). */
            const val TRACE_SEARCH_MAX_HOURS = 168L

            /** How long one tenant's thanos block rewrite may run. */
            const val REWRITE_TIMEOUT_HOURS = 6L

            /** How long one `docker compose` command of a temporary stack may take, image pulls included. */
            const val COMPOSE_COMMAND_TIMEOUT_MINUTES = 20L

            /** How long a temporary stack's backend may take to answer `/ready`. */
            const val READY_TIMEOUT_MINUTES = 10L

            /** How long the export writer's Tempo may take to write every span into a flushed block. */
            const val TEMPO_DRAIN_TIMEOUT_MINUTES = 15L
        }
    }

    // Proxy configuration
    object Proxy {
        /**
         * The ports a new SOCKS5 tunnel picks its local port from, at random, with none preferred. The
         * range lies below every OS ephemeral range (Linux 32768-60999, macOS 49152-65535). The OS gives
         * outgoing connections their local port from the ephemeral range, so an outgoing loopback
         * connection could take an ephemeral tunnel port while the tunnel script waits to restart ssh,
         * and the restart would then find its port taken and end the tunnel.
         */
        val LOCAL_PORT_RANGE: IntRange = TUNNEL_PORT_MIN..TUNNEL_PORT_MAX

        /** The first port of [LOCAL_PORT_RANGE]. */
        const val TUNNEL_PORT_MIN = 20000

        /** The last port of [LOCAL_PORT_RANGE]. */
        const val TUNNEL_PORT_MAX = 29999

        /**
         * How many random ports of [LOCAL_PORT_RANGE] a tunnel start tries before it fails. It never
         * falls back to an OS-assigned port, which would be an ephemeral one.
         */
        const val LOCAL_PORT_ATTEMPTS = 20

        /**
         * JVM system property used to publish the active SOCKS5 proxy port to the clients that need
         * the tunnel (fabric8 K8s, OkHttp). Deliberately NOT the standard `socksProxyHost`/
         * `socksProxyPort`: setting those makes java.net route every socket (including the AWS SDK)
         * through the tunnel. This private name carries only the port, so cluster clients can opt in
         * explicitly while AWS and all other traffic stays direct.
         */
        const val PORT_PROPERTY = "easydblab.socks5Port"

        /**
         * The address the SOCKS5 tunnel listens on, `ssh -D 127.0.0.1:<port>`, and the one every client
         * of the tunnel connects to. It is IPv4 loopback only, never `localhost`: an ssh that also
         * listened on `::1` keeps running with only that address bound when another process holds
         * 127.0.0.1 on the port, and a client of `localhost` could then reach that other process.
         */
        const val TUNNEL_LISTEN_ADDRESS = "127.0.0.1"

        /**
         * The sourceable file in the workspace that holds the proxy state shell-side tools read: the
         * tool wrappers in `bin/`, the tunnel script and `env.sh`. It holds only [ENV_TAILSCALE_ACTIVE],
         * [ENV_SOCKS_PORT] and [ENV_SOCKS_PID], one `KEY=value` line each, so no shell code ever parses JSON.
         */
        const val ENV_FILE = ".socks5-proxy.env"

        /**
         * The lock file beside [ENV_FILE]. Every update of the env file holds an exclusive lock on it
         * while it reads, changes and replaces the file, so two CLI processes never lose each other's keys.
         */
        const val ENV_LOCK_FILE = ".socks5-proxy.env.lock"

        /** The env file key that says whether the cluster reaches its nodes over Tailscale (`true`/`false`). */
        const val ENV_TAILSCALE_ACTIVE = "EDL_TAILSCALE_ACTIVE"

        /** The env file key that holds the verified tunnel's local port; absent while no tunnel is recorded. */
        const val ENV_SOCKS_PORT = "EDL_SOCKS_PORT"

        /**
         * The env file key that holds the PID of the verified tunnel's `edl-socks-tunnel` script,
         * written and removed with [ENV_SOCKS_PORT]. Shell-side tools use the port only while it is live.
         */
        const val ENV_SOCKS_PID = "EDL_SOCKS_PID"

        /**
         * Filename of the SOCKS5 tunnel log: the `ssh -v` transcript of every ssh the tunnel script
         * runs and the script's own timestamped lines. It lives under the workspace `logs/` directory
         * (alongside `logs/info.log`). Each launch appends a timestamped header and never truncates
         * it, so it keeps the history of every tunnel. A failed start reads back only its own part,
         * to surface the real ssh error (e.g. a changed host key or refused connection).
         */
        const val SOCKS5_PROXY_LOG_FILE = "socks5-proxy.log"

        /**
         * How many times a SOCKS5 proxy start launches `ssh -D`, each on a freshly selected port,
         * when the previous attempt died because its local port was already bound. Two workspaces
         * starting a proxy at the same moment can both find the same port free; the loser's ssh
         * exits with "cannot listen to port" and the next attempt picks another port.
         */
        const val PORT_BIND_MAX_ATTEMPTS = 3

        /** Pause between those attempts, long enough for the winning ssh to hold its port. */
        const val PORT_BIND_RETRY_INTERVAL_MS = 100L

        /**
         * How many times, 500ms apart, a fresh SOCKS tunnel is probed before it counts as failed,
         * when ssh dials the node directly: about 5s, and a direct tunnel is up in well under one.
         */
        const val DIRECT_TUNNEL_VERIFY_ATTEMPTS = 10

        /**
         * The same budget under the `ssm` SSH transport: about 30s. ssh first waits on
         * `aws ssm start-session` (CLI start-up, the StartSession call, the plugin's WebSocket) and
         * then runs key exchange and auth through it, which takes several seconds. The loop returns
         * on the first success and stops the moment ssh dies, so the longer budget costs time only
         * when a live ssh never produces a tunnel.
         */
        const val SSM_TUNNEL_VERIFY_ATTEMPTS = 60

        /**
         * How many times, 500ms apart, a running tunnel is probed before it is reused. A tunnel
         * that is up answers the first probe; a second covers one dropped packet. One that fails
         * both is stopped and replaced.
         */
        const val REUSE_TUNNEL_VERIFY_ATTEMPTS = 2

        /**
         * The whole budget, in milliseconds, of one end-to-end tunnel probe: the connect, the SOCKS5
         * handshake and CONNECT reply, and the read of the control node's `sshd` banner together. A
         * working tunnel answers in milliseconds; a dead one is given up on after this, every attempt.
         */
        const val PROBE_ATTEMPT_TIMEOUT_MS = 1000

        /**
         * How long after a tunnel last passed the end-to-end probe this process reuses its in-memory
         * tunnel without probing again. Cluster HTTP clients ask for the tunnel on every request, so
         * a burst of requests probes once. Across CLI invocations the state-file path always probes.
         */
        val REUSE_PROBE_FRESHNESS: java.time.Duration = java.time.Duration.ofSeconds(REUSE_PROBE_FRESHNESS_SECONDS)

        /** [REUSE_PROBE_FRESHNESS] in seconds. */
        const val REUSE_PROBE_FRESHNESS_SECONDS = 10L

        /** Seconds `edl-socks-tunnel` waits after its ssh exits before it starts ssh again on the same port. */
        const val TUNNEL_RESTART_BACKOFF_SECONDS = 2
    }

    // Tailscale VPN configuration
    object Tailscale {
        const val OAUTH_TOKEN_ENDPOINT = "https://api.tailscale.com/api/v2/oauth/token"
        const val AUTH_KEYS_ENDPOINT = "https://api.tailscale.com/api/v2/tailnet/-/keys"

        /** Device endpoint; `DELETE {DEVICE_ENDPOINT}/{nodeId}` removes a device from the tailnet. */
        const val DEVICE_ENDPOINT = "https://api.tailscale.com/api/v2/device"

        /** OAuth scope a client needs to delete devices, named in the error when it lacks it. */
        const val DEVICES_SCOPE = "devices:core"

        /** OAuth scope a client needs to create the control node's auth key in `tailscale start`. */
        const val AUTH_KEYS_SCOPE = "auth_keys"
        const val CONNECTION_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val AUTH_KEY_EXPIRY_SECONDS = 604800
        const val DAEMON_STARTUP_DELAY_MS = 2000L
        const val DEFAULT_DEVICE_TAG = "tag:easy-db-lab"
        const val STATUS_TIMEOUT_SECONDS = 5L

        /**
         * sysctl drop-in that turns on IP forwarding on the control node. A subnet router needs
         * it, and `tailscale up --advertise-routes` checks it: without it Tailscale warns that
         * subnet routing will not work. It is written before `tailscale up`, not left to K3s,
         * which enables forwarding only once it starts, after Tailscale has authenticated.
         */
        const val IP_FORWARDING_SYSCTL_FILE = "/etc/sysctl.d/99-tailscale.conf"

        /** How long to wait for the local `tailscale status` process before killing it. */
        const val LOCAL_STATUS_TIMEOUT_SECONDS = 5L

        /**
         * Connect timeout for one attempt of the pre-K3s probe of the control node over the
         * tailnet. Short by design: the target is already listening, so anything slower than a
         * local network round-trip means the route is not there yet.
         */
        const val REACHABILITY_TIMEOUT_MS = 2000

        /**
         * How many times `up` probes the control node over the tailnet before it gives up, and
         * how long it waits between probes. The subnet route the control node advertises takes a
         * while to reach this machine after `tailscale up`, so `up` waits for it: 24 attempts at
         * 5 seconds is about two minutes.
         */
        const val REACHABILITY_MAX_ATTEMPTS = 24
        const val REACHABILITY_RETRY_INTERVAL_MS = 5000L

        /** `BackendState` the Tailscale client reports when it is logged in and routing. */
        const val BACKEND_STATE_RUNNING = "Running"

        /** Stand-in `BackendState` for a client whose status could not be read at all. */
        const val BACKEND_STATE_UNKNOWN = "unknown"

        /** Stand-in `BackendState` for a `tailscale status` that never returned. */
        const val BACKEND_STATE_TIMED_OUT = "timed out"
    }

    // OpenSSH options the generated sshConfig sets for every SSH transport
    object Ssh {
        /**
         * ssh keepalive interval for every host, direct or over SSM: well inside Session Manager's
         * 20-minute idle timeout, and short enough that a tunnel whose connection died is noticed.
         */
        const val KEEPALIVE_INTERVAL_SECONDS = 30

        /** Unanswered keepalives before ssh gives up on a dropped connection (about 90s at the interval above). */
        const val KEEPALIVE_COUNT_MAX = 3

        /**
         * OpenSSH's ConnectTimeout in the generated sshConfig, for every transport. Keepalives start
         * only after authentication, so this bounds the wait for the server's banner, ProxyCommand
         * included (verified on OpenSSH 9.6 and 10.3); it does not bound key exchange once the banner
         * has arrived. It matches [Ssm.SSH_AUTH_TIMEOUT_SECONDS].
         */
        const val CONNECT_TIMEOUT_SECONDS = 30

        /** The personal OpenSSH config `ssh` reads first without `-F`; the generated sshConfig includes it for other hosts. */
        const val PERSONAL_CONFIG = "~/.ssh/config"

        /** The system OpenSSH config `ssh` reads after the personal one without `-F`. */
        const val SYSTEM_CONFIG = "/etc/ssh/ssh_config"
    }

    // AWS Systems Manager Session Manager, used as the SSH transport when a profile selects `ssm`
    object Ssm {
        /** The AWS CLI executable. Session Manager sessions are started through it. */
        const val AWS_CLI = "aws"

        /** The Session Manager plugin the AWS CLI hands each session to. */
        const val SESSION_MANAGER_PLUGIN = "session-manager-plugin"

        /** AWS-owned document that bridges a session's stdin/stdout to a port on the instance. */
        const val SSH_SESSION_DOCUMENT = "AWS-StartSSHSession"

        /** AWS-owned document that forwards a local port to a port on the instance. */
        const val PORT_FORWARD_DOCUMENT = "AWS-StartPortForwardingSession"

        /**
         * How long SSH key exchange and authentication may take through an SSM port forward. A
         * healthy one takes about 1-1.5s over the loopback forward; a forward that accepts TCP and
         * then carries nothing would otherwise hold each attempt for MINA's 120s default.
         */
        const val SSH_AUTH_TIMEOUT_SECONDS = 30L

        /** Prefix of the line the AWS CLI prints with a new session's ID, which TerminateSession needs. */
        const val SESSION_ID_MARKER = "Starting session with SessionId: "

        /**
         * How long stopping forwards waits, in total, for their TerminateSession calls (they run in
         * parallel), and the SSM client's overall API call timeout. 20s because HTTPS to SSO and
         * SSM can take 5-8s on a slow network, and an unended session stays open for Session
         * Manager's 20-minute idle timeout. Still bounded, so a call that never answers cannot hold
         * up the JVM shutdown hook for longer than this.
         */
        const val TERMINATE_SESSION_TIMEOUT_SECONDS = 20L

        /**
         * The SSM client's timeout for one TerminateSession attempt. Below the 20s total so the
         * SDK's retries (three attempts in its standard mode) fit inside it, instead of one slow
         * attempt using the whole budget.
         */
        const val TERMINATE_SESSION_ATTEMPT_TIMEOUT_SECONDS = 6L

        /** Line the Session Manager plugin prints once a port-forward listener is accepting connections. */
        const val PORT_FORWARD_READY_MARKER = "Waiting for connections"

        /** Address a port-forwarding session listens on. */
        const val LOCAL_FORWARD_ADDRESS = "127.0.0.1"

        /** How long a port-forwarding session gets to report ready before it is killed. */
        const val PORT_FORWARD_READY_TIMEOUT_SECONDS = 60L

        /** How long a forwarding process gets to exit after SIGTERM before it is killed outright. */
        const val PROCESS_STOP_GRACE_SECONDS = 5L

        /** How long a local `--version` check of the AWS CLI or the plugin may run. */
        const val TOOL_CHECK_TIMEOUT_SECONDS = 10L

        /** Most recent plugin output lines kept for error messages. */
        const val TRANSCRIPT_MAX_LINES = 50
        const val AWS_CLI_INSTALL_HINT =
            "brew install awscli (or https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)"

        const val PLUGIN_INSTALL_HINT =
            "brew install --cask session-manager-plugin " +
                "(or https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)"
    }

    /**
     * The workspace tool wrappers: one packaged POSIX script written into `<workspace>/bin/` once per
     * wrapped tool. Each copy picks its tool from its own file name and routes it through the SOCKS
     * tunnel recorded in [Proxy.ENV_FILE], so kit shell steps, hooks and `env.sh` shells all reach the
     * cluster the same way.
     */
    object ToolWrappers {
        /** The workspace directory the wrappers are written to. */
        const val DIRECTORY = "bin"

        /** The marker file that says a `bin/` directory holds easy-db-lab's wrappers. */
        const val MARKER = ".easy-db-lab-tool-wrappers"

        /** The packaged wrapper script. */
        const val RESOURCE = "/com/rustyrazorblade/easydblab/configuration/tool-wrapper.sh"

        /** The tools whose wrapper routes them to the cluster's private network: the SOCKS tunnel or Tailscale. */
        val TOOLS = listOf("kubectl", "helm", "cilium", "curl", "skopeo", "k9s")

        /**
         * The tools whose wrapper reaches the nodes over SSH with the workspace `sshConfig`, and leaves the
         * proxy variables and `KUBECONFIG` alone.
         */
        val SSH_TOOLS = listOf("ssh", "scp", "sftp", "rsync")

        /** Every tool that gets a wrapper in `bin/`; the names are also the wrapper file names. */
        val WRAPPED_TOOLS = TOOLS + SSH_TOOLS

        /** The tunnel script written into `bin/` with the wrappers, which keeps the SOCKS tunnel's ssh running. */
        const val TUNNEL_SCRIPT = "edl-socks-tunnel"

        /**
         * The shell the CLI runs [TUNNEL_SCRIPT] with, `/bin/sh <script>`, instead of exec'ing it: macOS
         * scans the first exec of a new or rewritten executable, and a shell reads the script as data.
         */
        const val TUNNEL_SHELL = "/bin/sh"

        /** The packaged tunnel script. */
        const val TUNNEL_RESOURCE = "/com/rustyrazorblade/easydblab/configuration/edl-socks-tunnel.sh"

        /** The shared kit shell library written into `bin/` once, and never rewritten, which kit scripts source. */
        const val KIT_LIBRARY = "edl-kit-lib.sh"

        /** The packaged kit shell library; outside `kits/`, so the built-in kit scan never lists it as a kit. */
        const val KIT_LIBRARY_RESOURCE = "/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
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

        /** VPC name of the account compactor; tagged [TAG_KEY] but never [BUCKET_TAG_KEY]. */
        const val COMPACTOR_VPC_NAME = "easy-db-lab-compactor"

        /** VPC tag key for the S3 bucket name */
        const val BUCKET_TAG_KEY = "bucket"

        /**
         * Attempts at creating a VPC on an auto-selected CIDR; each retry picks a new random
         * unused block, excluding the ones that already failed.
         */
        const val CIDR_AUTO_SELECT_MAX_ATTEMPTS = 3

        /** SOCKS5 proxy state file name */
        const val SOCKS5_PROXY_STATE_FILE = ".socks5-proxy-state"

        /**
         * Generates a subnet CIDR block for a given index within the VPC.
         * Uses the pattern 10.0.{index+1}.0/24
         */
        fun subnetCidr(index: Int): String = "10.0.${index + 1}.0/24"
    }
}
