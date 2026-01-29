# easy-db-lab

This is the manual for easy-db-lab, a provisioning tool for Apache Cassandra designed for developers looking to benchmark and test Apache Cassandra. It assists with builds and starting instances on AWS.

If you are looking for a tool to aid in benchmarking these clusters please see the companion project [cassandra-easy-stress](https://github.com/apache/cassandra-easy-stress).

If you're looking for tools to help manage Cassandra in *production* environments please see [Reaper](http://cassandra-reaper.io/), [cstar](https://github.com/spotify/cstar), and [K8ssandra](https://docs.k8ssandra.io/).

## Quick Start

1. [Install easy-db-lab](getting-started/installation.md)
2. [Set up your profile](getting-started/setup.md) - Run `easy-db-lab setup-profile`
3. [Follow the tutorial](user-guide/tutorial.md) - Initialize, launch, and configure Cassandra

## Features

### Cassandra Support

- **Multiple Versions**: Support for Cassandra 3.0, 3.11, 4.0, 4.1, 5.0, and trunk builds
- **Custom Builds**: Build and deploy custom Cassandra versions from source
- **Cassandra Sidecar**: Automatic installation and management of Apache Cassandra Sidecar
- **Stress Testing**: Integration with cassandra-easy-stress for benchmarking

### AWS Integration

- **EC2 Provisioning**: Seamlessly provision EC2 instances optimized for Cassandra
- **EBS Storage**: Optional EBS volumes for persistent storage
- **S3 Backup**: Automatic backup of configurations and state to S3
- **IAM Integration**: Managed IAM policies for secure operations

### Kubernetes (K3s)

- **Lightweight K3s**: Automatic K3s cluster deployment across all nodes
- **kubectl/k9s**: Pre-configured access with SOCKS5 proxy support
- **Private Registry**: HTTPS Docker registry for custom images
- **Jib Integration**: Push custom containers directly from Gradle

### Analytics and Data Services

- **Apache Spark**: EMR-based Spark clusters for analytics workloads
- **[ClickHouse](user-guide/clickhouse.md)**: Deploy ClickHouse clusters with S3-tiered storage
- **OpenSearch**: Optional OpenSearch domains for search and analytics

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
