# OpenSearch

[AWS OpenSearch](https://aws.amazon.com/opensearch-service/) can be provisioned as a managed domain for full-text search and log analytics.

## Commands

| Command | Description |
|---------|-------------|
| `opensearch start` | Create an OpenSearch domain |
| `opensearch status` | Check domain status |
| `opensearch stop` | Delete the OpenSearch domain |

## Starting OpenSearch

```bash
easy-db-lab opensearch start
```

This creates an AWS-managed OpenSearch domain linked to your cluster's VPC. The domain takes several minutes to provision.

## Checking Status

```bash
easy-db-lab opensearch status
```

## Stopping OpenSearch

```bash
easy-db-lab opensearch stop
```

This deletes the OpenSearch domain. Data stored in the domain will be lost.
