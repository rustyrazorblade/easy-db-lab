# easy-db-lab

easy-db-lab creates lab environments for database evaluations in AWS. It provisions infrastructure, deploys databases, and sets up a full observability stack so you can focus on testing, benchmarking, and learning.

Supported databases include [Apache Cassandra](user-guide/installing-cassandra.md), [ClickHouse](user-guide/clickhouse.md), and [OpenSearch](user-guide/opensearch.md), with [Apache Spark](user-guide/spark.md) available for analytics workloads.

If you are looking for a tool to aid in stress testing Cassandra clusters, see the companion project [cassandra-easy-stress](https://github.com/apache/cassandra-easy-stress).

If you're looking for tools to help manage Cassandra in *production* environments please see [Reaper](http://cassandra-reaper.io/), [cstar](https://github.com/spotify/cstar), and [K8ssandra](https://docs.k8ssandra.io/).

## Quick Start

1. [Install easy-db-lab](getting-started/installation.md)
2. [Set up your profile](getting-started/setup.md) - Run `easy-db-lab setup-profile`
3. [Follow the tutorial](user-guide/tutorial.md)

## Features

### Database Support

- **[Apache Cassandra](user-guide/installing-cassandra.md)**: Versions 3.0, 3.11, 4.0, 4.1, 5.0, and trunk builds. Includes custom build support, Cassandra Sidecar, and integration with cassandra-easy-stress for benchmarking.
- **[ClickHouse](user-guide/clickhouse.md)**: Sharded clusters with configurable replication, distributed tables, and S3-tiered storage.
- **[OpenSearch](user-guide/opensearch.md)**: AWS OpenSearch domains for search and analytics.
- **[Apache Spark](user-guide/spark.md)**: EMR-based Spark clusters for analytics workloads.

### AWS Integration

- **EC2 Provisioning**: Automated provisioning with configurable instance types
- **EBS Storage**: Optional EBS volumes for persistent storage
- **S3 Backup**: Automatic backup of configurations and state to S3
- **IAM Integration**: Managed IAM policies for secure operations

### Kubernetes (K3s)

- **Lightweight K3s**: Automatic K3s cluster deployment across all nodes
- **kubectl/k9s**: Pre-configured access with SOCKS5 proxy support
- **Private Registry**: HTTPS Docker registry for custom images
- **Jib Integration**: Push custom containers directly from Gradle

### Monitoring and Observability

- **VictoriaMetrics**: Time-series database for metrics storage
- **VictoriaLogs**: Centralized log aggregation
- **Grafana**: Pre-configured dashboards for Cassandra, ClickHouse, and system metrics
- **OpenTelemetry**: Distributed tracing and metrics collection
- **[AxonOps](https://axonops.com/)**: Optional integration with AxonOps for Cassandra monitoring and management

### Developer Experience

- **Shell Aliases**: Convenient shortcuts for cluster management (`c0`, `c-all`, `c-status`, etc.)
- **MCP Server**: Integration with Claude Code for AI-assisted operations
- **Restore Support**: Recover cluster state from VPC ID or S3 backup
- **SOCKS5 Proxy**: Secure access to private cluster resources

### Stress Testing

- **cassandra-easy-stress**: Native integration with Apache stress testing tool
- **Kubernetes Jobs**: Run stress tests as K8s jobs for scalability
- **Artifact Collection**: Automatic collection of metrics and diagnostics
