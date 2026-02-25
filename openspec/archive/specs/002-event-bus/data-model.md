# Data Model: Event Bus Output System

**Branch**: `002-event-bus` | **Date**: 2026-02-23

## Core Types

### Event (sealed interface)

The root of the event hierarchy. Events are **pure domain data** — they carry only fields relevant to the specific event. No metadata (timestamp, command name) lives on the event itself.

Every concrete event implements `toDisplayString(): String` returning the human-readable console output.

### Design Rule: Data-Only Constructors

Event constructors MUST carry only structured domain data fields — never pre-formatted display strings. The `toDisplayString()` method constructs human-readable output internally from those data fields.

**Good** — structured data in constructor, display logic in method:
```kotlin
data class JobStarted(val jobName: String, val image: String, val promPort: Int) : Stress {
    override fun toDisplayString(): String = "Stress job started: $jobName (image: $image, port: $promPort)"
}
```

**Bad** — passthrough string in constructor:
```kotlin
data class JobStarted(val message: String) : Stress {
    override fun toDisplayString(): String = message  // violates data-only rule
}
```

This ensures Redis/MCP consumers receive queryable structured JSON like `{"jobName": "kv-1", "image": "...", "promPort": 9501}` rather than an opaque message string.

For events with no meaningful data fields, use `data object`:
```kotlin
data object CredentialsValidating : Setup {
    override fun toDisplayString(): String = "Validating AWS credentials..."
}
```

### EventContext

Ambient context stored in a stack-based `ThreadLocal`. Automatically managed by the command execution layer.

| Field | Type | Description |
|-------|------|-------------|
| commandName | `String` | The name of the currently executing command |

**Stack behavior**: Each command pushes on entry, pops on exit. When `Init` calls `Up`, the stack is `[init, up]` — the EventBus reads the top (`up`). When `Up` finishes and pops, the stack returns to `[init]`.

**Lifecycle**: `PicoBaseCommand.run()` (or `CommandExecutor`) calls `EventContext.push(commandName)` before `execute()` and `EventContext.pop()` in a `finally` block.

### EventEnvelope

Created by the EventBus at dispatch time. Wraps an event with automatically-injected metadata.

| Field | Type | Description |
|-------|------|-------------|
| event | `Event` | The domain event |
| timestamp | `Instant` | When the event was emitted (auto-populated by EventBus) |
| commandName | `String?` | Read from top of EventContext stack (null if no command context) |

### EventListener (interface)

```
interface EventListener {
    fun onEvent(envelope: EventEnvelope)
    fun close()
}
```

Listeners receive `EventEnvelope`, not raw `Event`. They can access `envelope.event` for domain data and `envelope.timestamp` / `envelope.commandName` for metadata.

Implementations: `ConsoleEventListener`, `McpEventListener`, `RedisEventListener`

### EventBus

Central dispatcher. On `emit(event)`:
1. Reads `EventContext.current()` from the thread-local stack
2. Creates `EventEnvelope(event, Instant.now(), context?.commandName)`
3. Dispatches envelope to all registered `EventListener` instances

Thread-safe addition/removal of listeners. Dispatches synchronously (Redis listener handles async internally).

## Event Hierarchy

### Event.Infra — AWS Infrastructure

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| VpcCreating | name: String | "Creating VPC: $name" |
| VpcCreated | vpcId: String | "VPC created: $vpcId" |
| VpcUsing | name: String | "Using existing VPC: $name" |
| VpcDeleting | vpcId: String | "Deleting VPC: $vpcId" |
| VpcDeleted | vpcId: String | "VPC $vpcId deleted successfully" |
| SubnetCreating | name: String | "Creating subnet: $name" |
| SubnetUsing | name: String | "Using existing subnet: $name" |
| InternetGatewayCreating | name: String | "Creating internet gateway: $name" |
| InternetGatewayUsing | name: String | "Using existing internet gateway: $name" |
| InternetGatewayDetaching | — | "Detaching internet gateway..." |
| InternetGatewayDeleting | igwId: String | "Deleting internet gateway: $igwId" |
| SecurityGroupCreating | name: String | "Creating security group: $name" |
| SecurityGroupUsing | name: String | "Using existing security group: $name" |
| SecurityGroupRuleConfigured | portDesc: String | "Configured security group ingress rule for $portDesc" |
| SecurityGroupRulesRevoking | count: Int | "Revoking rules from $count security groups..." |
| SecurityGroupDeleting | sgId: String | "Deleting security group: $sgId" |
| RoutingConfigured | — | "Configured routing to internet gateway" |
| NatGatewayDeleting | natId: String | "Deleting NAT gateway: $natId" |
| NatGatewaysWaiting | — | "Waiting for NAT gateways to be deleted..." |
| NatGatewaysDeleted | — | "All NAT gateways deleted" |
| RouteTableDeleting | rtId: String | "Deleting route table: $rtId" |
| SubnetDeleting | subnetId: String | "Deleting subnet: $subnetId" |
| NetworkInterfacesClearing | count: Int | "Waiting for $count network interfaces to clear..." |
| NetworkInterfacesCleared | — | "All network interfaces cleared" |
| InfrastructureEnsuring | vpcName: String | "Ensuring infrastructure exists for: $vpcName" |
| InfrastructureReady | vpcName: String | "Infrastructure ready for: $vpcName" |
| VpcNetworkingSetup | clusterName: String | "Setting up VPC networking for: $clusterName" |
| VpcNetworkingReady | clusterName: String | "VPC networking ready for: $clusterName" |
| ResourceDiscovering | vpcId: String | "Discovering resources in VPC: $vpcId" |
| VpcTeardownSearching | tagKey: String, tagValue: String | "Finding all VPCs tagged with..." |
| VpcTeardownNoneFound | tagKey: String, tagValue: String | "No VPCs found with tag..." |
| VpcTeardownFound | count: Int | "Found $count VPCs to tear down" |
| PackerVpcSkipping | vpcId: String | "Skipping packer VPC: $vpcId..." |
| PackerVpcSearching | — | "Finding packer infrastructure VPC..." |
| PackerVpcNotFound | name: String | "No packer VPC found ($name)" |

### Event.Ec2 — EC2 Instance Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| InstancesTerminating | count: Int | "Terminating $count EC2 instances..." |
| InstancesTerminateWaiting | — | "Waiting for instances to terminate..." |
| InstancesTerminated | — | "All instances terminated" |
| InstancesStartWaiting | — | "Waiting for instances to start..." |
| InstancesRunning | — | "All instances running" |
| StatusCheckWaiting | — | "Waiting for instance status checks to pass..." |
| StatusCheckPassed | — | "Instance status checks passed" |
| ExistingInstancesFound | serverType: String, count: Int | "Found $count existing $serverType instances..." |

### Event.K3s — K3s Cluster Management

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| ClusterStarting | — | "Starting K3s cluster..." |
| ServerStarting | controlHost: String | "Starting K3s server on control node $controlHost..." |
| ServerStartFailed | error: String | "Failed to start K3s server: $error" |
| NodeTokenFailed | error: String | "Failed to retrieve K3s node token: $error" |
| KubeconfigFailed | error: String | "Failed to download kubeconfig: $error" |
| KubeconfigWritten | path: String | "Kubeconfig written to $path" |
| KubeconfigInstruction | — | "Use 'source env.sh' to configure kubectl..." |
| ClusterStarted | — | "K3s cluster started successfully" |
| AgentConfiguring | host: String, labels: String | "Configuring K3s agent on $host with labels: $labels..." |
| AgentConfigFailed | host: String, error: String | "Failed to configure K3s agent on $host: $error" |
| AgentStartFailed | host: String, error: String | "Failed to start K3s agent on $host: $error" |

### Event.Cassandra — Cassandra Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| Starting | host: String | "Starting Cassandra on $host..." |
| StartedWaitingReady | host: String | "Cassandra started, waiting for $host to become UP/NORMAL..." |
| Restarting | host: String | "Restarting Cassandra on $host..." |
| WaitingReady | host: String | "Waiting for $host to become UP/NORMAL..." |

### Event.Emr — EMR & Spark Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| ClusterCreating | clusterName: String | "Creating EMR cluster: $clusterName..." |
| ClusterInitiated | clusterId: String | "EMR cluster initiated: $clusterId" |
| ClusterWaiting | — | "Waiting for EMR cluster to start..." |
| ClusterReady | — | "EMR cluster is ready" |
| ClusterTerminating | clusterId: String | "Terminating EMR cluster: $clusterId..." |
| ClusterTerminateWaiting | — | "Waiting for EMR cluster to terminate..." |
| ClusterTerminated | — | "EMR cluster terminated" |
| ClustersTerminating | count: Int | "Terminating $count EMR clusters..." |
| ClustersTerminateWaiting | — | "Waiting for EMR clusters to terminate..." |
| ClustersTerminated | — | "All EMR clusters terminated" |
| ClusterAlreadyExists | — | "EMR cluster already exists, skipping creation" |
| SparkJobWaiting | — | "Waiting for job completion..." |
| SparkJobStateUpdate | state: String | "Job state: $state" |
| SparkJobCompleted | — | "Job completed successfully" |
| SparkStepDetails | stepId: String, name: String, state: String, creationTime: String | Step details display |
| SparkStepError | stepId: String, error: String | Step error message |
| SparkLogHeader | — | "=== Step Logs ===" |
| SparkLogLine | line: String | Individual log line |
| SparkLogCount | count: Int | "Found $count log entries." |
| SparkLogError | error: String | "Could not query logs: $error" |
| SparkLogInstruction | stepId: String | "Try: easy-db-lab spark logs --step-id $stepId" |
| SparkLogDownloadStart | path: String | "Downloading logs from: $path" |
| SparkLogDownloadSaveTo | localPath: String | "Saving to: $localPath" |
| SparkLogDownloadComplete | logType: String | "Downloaded: $logType" |
| SparkLogDownloadUnavailable | logType: String | "Could not download $logType..." |
| SparkLogDownloadFailed | error: String | "Could not download logs: $error" |
| SparkDebugInstructions | clusterId: String, stepId: String | Manual debug command instructions |
| SparkStderrHeader | lineCount: Int | "=== stderr (last $lineCount lines) ===" |
| SparkStderrLine | line: String | Individual stderr line |
| SparkStderrFooter | — | "=== end stderr ===" |
| EmrLogsDownloading | path: String | "Downloading all EMR logs from: $path" |
| EmrLogsSaveTo | localDir: String | "Saving to: $localDir" |
| StepLogsDownloading | localDir: String | "Downloading step logs to: $localDir" |

### Event.OpenSearch — OpenSearch Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| Creating | domainName: String | "Creating OpenSearch domain: $domainName..." |
| Initiated | domainName: String | "OpenSearch domain initiated: $domainName" |
| Waiting | — | "Waiting for OpenSearch domain to become active..." |
| Ready | — | "OpenSearch domain is ready" |
| WaitProgress | domainName: String, currentStatus: String, elapsedMinutes: Long | "$currentStatus... ($elapsedMinutes minutes elapsed)" |
| Deleting | domainName: String | "Deleting OpenSearch domain: $domainName..." |
| DeleteWaiting | domainName: String | "Waiting for OpenSearch domain $domainName to be deleted..." |
| Deleted | domainName: String | "OpenSearch domain $domainName deleted" |

### Event.S3 — S3 Object Store Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| Uploading | fileName: String, remotePath: String | "Uploading $fileName to $remotePath..." |
| UploadComplete | remotePath: String | "Upload complete: $remotePath" |
| Downloading | remotePath: String, localPath: String | "Downloading $remotePath to $localPath..." |
| DownloadComplete | localPath: String | "Download complete: $localPath" |
| Deleting | remotePath: String | "Deleting $remotePath..." |
| Deleted | remotePath: String | "Deleted: $remotePath" |
| DirectoryDownloadFound | count: Int | "Found $count files to download" |
| DirectoryDownloadComplete | count: Int, localDir: String | "Downloaded $count files to $localDir" |
| DirectoryUploadFound | count: Int, remotePath: String | "Found $count files to upload to $remotePath" |
| DirectoryUploadComplete | count: Int, remotePath: String | "Uploaded $count files to $remotePath" |
| NotificationsConfiguring | — | "Configuring S3 bucket notifications for EMR logs..." |
| NotificationsConfigured | — | "S3 bucket notifications configured for EMR logs" |
| BucketConfigured | bucket: String, prefix: String | "S3 bucket configured: $bucket (prefix: $prefix)" |
| MetricsEnabled | prefix: String | "S3 request metrics enabled for cluster prefix: $prefix" |
| BucketUsing | bucket: String | "Using account S3 bucket: $bucket" |

### Event.Sqs — SQS Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| QueueCreating | queueName: String | "Creating SQS queue: $queueName" |
| QueueCreated | queueUrl: String | "SQS queue created: $queueUrl" |
| QueueDeleting | queueUrl: String | "Deleting SQS queue: $queueUrl" |
| QueueDeleted | — | "SQS queue deleted" |
| QueueConfigured | queueUrl: String | "SQS queue configured for log ingestion: $queueUrl" |

### Event.K8s — Kubernetes Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| ManifestsApplying | — | "Applying K8s manifests..." |
| ManifestsApplied | — | "K8s manifests applied successfully" |
| ManifestApplied | resourcePath: String | "Manifest applied: $resourcePath" |
| NamespaceDeleting | namespace: String | "Deleting $namespace namespace..." |
| NamespaceDeleted | namespace: String | "Namespace $namespace deleted" |
| PodsWaiting | namespace: String | "Waiting for pods in $namespace to be ready..." |
| PodsReady | namespace: String | "All pods in $namespace are ready" |
| ObservabilityPodsWaiting | — | "Waiting for observability pods to be ready..." |
| ObservabilityPodsReady | — | "All observability pods are ready" |
| ObservabilityNamespaceDeleting | — | "Deleting observability namespace..." |
| ObservabilityNamespaceDeleted | — | "Observability namespace deleted" |
| ResourcesDeleting | labelKey: String | "Deleting resources with label $labelKey..." |
| ResourcesDeleted | — | "Resources deleted successfully" |
| ConfigMapCreated | name: String | "Created ConfigMap: $name" |
| ConfigMapDeleted | name: String | "Deleted ConfigMap: $name" |
| ClickHouseS3ConfigMapCreated | — | "Created ClickHouse S3 ConfigMap" |
| StatefulSetScaled | name: String, replicas: Int | "Scaled $name to $replicas replicas" |
| JobDeleted | jobName: String | "Deleted job: $jobName" |
| LocalPvsCreated | count: Int, dbName: String | "Created $count Local PVs for $dbName" |
| StorageClassCreated | — | "Created local-storage StorageClass" |

### Event.Grafana — Grafana Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| DatasourcesCreating | — | "Creating Grafana datasources ConfigMap..." |
| ResourcesApplying | count: Int | "Applying $count Grafana resources..." |
| ResourceApplying | kind: String, name: String | "Applying $kind/$name..." |
| ResourcesApplied | — | "All Grafana resources applied successfully!" |

### Event.Backup — Backup & Restore Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| VictoriaMetricsStarting | s3Path: String | "Creating VictoriaMetrics backup to $s3Path..." |
| VictoriaMetricsJobStarted | jobName: String | "Backup job started: $jobName" |
| VictoriaMetricsComplete | s3Path: String | "VictoriaMetrics backup completed: $s3Path" |
| VictoriaLogsStarting | s3Path: String | "Creating VictoriaLogs backup to $s3Path..." |
| VictoriaLogsJobStarted | jobName: String | "Backup job started: $jobName" |
| VictoriaLogsComplete | s3Path: String | "VictoriaLogs backup completed: $s3Path" |
| Waiting | — | "Waiting for backup to complete..." |
| ConfigBackedUp | displayName: String, s3Path: String | "$displayName backed up to S3: $s3Path" |
| ConfigRestored | displayName: String, s3Path: String | "$displayName restored from S3: $s3Path" |
| KubeconfigBackedUp | s3Path: String | "Kubeconfig backed up to S3: $s3Path" |
| KubeconfigRestored | s3Path: String | "Kubeconfig restored from S3: $s3Path" |
| K8sManifestsBackedUp | s3Path: String | "K8s manifests backed up to S3: $s3Path" |
| K8sManifestsRestored | s3Path: String | "K8s manifests restored from S3: $s3Path" |
| CassandraPatchBackedUp | s3Path: String | "Cassandra patch backed up to S3: $s3Path" |
| CassandraPatchRestored | s3Path: String | "Cassandra patch restored from S3: $s3Path" |
| ClusterLookup | vpcId: String | "Looking up cluster info from VPC: $vpcId" |
| ClusterFound | clusterName: String, s3Bucket: String | "Found cluster '$clusterName' with S3 bucket: $s3Bucket" |
| RestoreStarting | — | "Restoring cluster configuration from S3..." |
| RestoreComplete | items: List | "Configuration restored from S3: ..." |
| RestoreEmpty | — | "No configuration files found in S3 to restore" |
| StateRestored | clusterName: String, clusterId: String, hostCount: Int | "State restored successfully: ..." |
| IncrementalBackupComplete | filesUploaded: Int | "Backed up $filesUploaded changed configuration files to S3" |
| IncrementalBackupFailed | error: String | "Warning: Incremental backup failed: $error" |

### Event.Registry — Container Registry Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| CertGenerating | controlHost: String | "Generating TLS certificate for registry on $controlHost..." |
| CertUploaded | — | "Uploaded registry certificate to S3" |
| TlsConfiguring | host: String | "Configuring registry TLS on $host..." |
| TlsConfigured | — | "Registry TLS configured on all nodes" |

### Event.Tailscale — Tailscale VPN Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| DaemonStarting | host: String | "Starting Tailscale daemon on $host..." |
| Authenticating | host: String | "Authenticating Tailscale on $host..." |
| Connected | host: String | "Tailscale connected on $host" |
| Stopping | host: String | "Stopping Tailscale on $host..." |
| Stopped | host: String | "Tailscale stopped on $host" |

### Event.AwsSetup — AWS Resource Setup

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| Starting | resourceType: String | "Setting up AWS $resourceType..." |
| RepairWarning | resource: String, error: String | Validation failure warning for specific resource |
| CredentialError | error: String | Credential validation failure |
| Ec2RoleReady | roleName: String | "EC2 role ready: $roleName" |
| EmrServiceRoleReady | roleName: String | "EMR service role ready: $roleName" |
| EmrEc2RoleReady | roleName: String | "EMR EC2 role ready: $roleName" |
| IamPermissionError | operation: String, error: String | IAM permissions error for specific operation |
| IamValidationError | resource: String, error: String | IAM validation error for specific resource |
| IamUnexpectedError | operation: String, error: String | Unexpected IAM error during specific operation |
| ValidationFailed | resource: String, error: String | Validation failure for specific resource |
| Complete | rolesConfigured: Int | "AWS setup complete: $rolesConfigured roles configured" |

### Event.Stress — Stress Testing Operations

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| JobStarting | jobName: String | "Starting stress job: $jobName" |
| PodStatus | podName: String, status: String | "Pod $podName is $status" |

### Event.Service — SystemD Service Management

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| Starting | serviceName: String, host: String | "Starting $serviceName on $host..." |
| Stopping | serviceName: String, host: String | "Stopping $serviceName on $host..." |
| Restarting | serviceName: String, host: String | "Restarting $serviceName on $host..." |

### Event.Provision — Cluster Provisioning

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| IamUpdating | — | "Ensuring IAM policies are up to date..." |
| InfrastructureStarting | — | "Provisioning infrastructure..." |
| EmrReady | masterDns: String | "EMR cluster ready: $masterDns" |
| OpenSearchReady | endpoint: String | "OpenSearch domain ready: https://$endpoint" |
| OpenSearchDashboards | url: String | Dashboard URL |
| InfrastructureFailureHeader | — | "Infrastructure creation had failures:" |
| InfrastructureFailure | resource: String, error: String | "  - $resource: $error" |
| ClusterStateUpdated | hostCount: Int | "Cluster state updated: $hostCount hosts tracked" |
| InstancesDiscovered | cassandra: Int, stress: Int, control: Int | "Discovered existing instances: ..." |
| ProvisioningComplete | clusterName: String, nodeCount: Int | "Cluster $clusterName provisioned: $nodeCount nodes" |
| ProvisioningInstructions | clusterName: String, sshCommand: String, grafanaUrl: String | Post-provisioning access instructions |
| SshWaiting | — | "Waiting for SSH to come up.." |
| SshRetrying | attempt: Int | "SSH still not up yet, waiting... (attempt $attempt)" |
| NodeSetupInstructions | nodeCount: Int, controlNodeIp: String | Setup instructions for nodes |
| AxonOpsSetup | org: String | "Setting up axonops for $org" |
| TailscaleStarting | — | "Starting Tailscale VPN..." |
| TailscaleWarning | error: String | "Warning: Failed to start Tailscale: $error" |
| TailscaleManualInstruction | — | Manual startup instruction |
| NoControlNodes | — | "No control nodes found, skipping K3s setup" |
| NodeLabeling | count: Int | "Labeling $count db nodes with ordinals..." |
| NodeLabelingComplete | — | "Node labeling complete" |
| LogPipelineValidating | — | "Validating log ingestion pipeline..." |
| LogPipelineValid | — | "S3 → SQS notifications configured correctly" |
| VpcCreating | clusterName: String | "Creating VPC for cluster: $clusterName" |
| VpcCreated | vpcId: String | "VPC created: $vpcId" |
| OpenSearchCreating | — | "Creating OpenSearch domain..." |
| AxonOpsConfigWritten | configFile: String | "AxonOps configuration written to $configFile" |

### Event.Command — Command Execution & Errors

| Event | Key Fields | Current Message Pattern |
|-------|-----------|------------------------|
| ExecutionError | commandName: String, error: String | Command execution error |
| RetryInstruction | — | "You can now run the command again." |
| DockerNotAvailable | — | "Docker is not available or not running." |
| DockerSetupInstruction | platform: String | Docker setup instructions for specific platform |
| SshKeyMissing | path: String | "SSH key not found at $path" |

## Serialization Wire Format

The `EventEnvelope` is serialized to JSON. The envelope provides metadata; the nested event provides domain data:

```json
{
  "timestamp": "2026-02-23T10:15:30.123Z",
  "commandName": "cassandra start",
  "event": {
    "type": "Cassandra.Starting",
    "host": "cassandra0"
  }
}
```

- `timestamp` and `commandName` come from the envelope (injected by EventBus)
- `type` is the polymorphic class discriminator for the event
- Domain-specific fields are nested under `event`
- `toDisplayString()` output is NOT serialized — it is a Kotlin-side rendering method for console display only. Consumers use the structured data fields directly or reconstruct display text from them. There is no `message`, `text`, or `displayString` field in the wire format

## State Transitions

Events are stateless — they represent point-in-time occurrences. There is no event state machine. Consumers that need state tracking (e.g., "operation X started but never completed") maintain their own state.
