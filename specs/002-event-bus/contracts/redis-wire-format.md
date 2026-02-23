# Redis Wire Format Contract

**Version**: 1.0
**Deliverable**: `docs/reference/event-bus-asyncapi.yaml` (AsyncAPI 3.0 specification)

## Overview

The wire format is formally specified as an AsyncAPI 3.0 document. This is a machine-readable YAML file that third-party consumers and LLMs can download and use to understand the event bus protocol without reading source code.

The AsyncAPI spec will be authored in `docs/reference/event-bus-asyncapi.yaml` and kept in sync with the Kotlin sealed class hierarchy.

## Connection

- **Protocol**: Redis pub/sub
- **Configuration**: `EASY_DB_LAB_REDIS_URL` environment variable
- **Format**: `redis://host:port/channel-name`
- **Example**: `redis://localhost:6379/easydblab-events`

## Envelope Structure

Every message on the channel is a JSON `EventEnvelope`:

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

### Envelope Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| timestamp | string (ISO-8601) | yes | When the event was emitted |
| commandName | string | no | Innermost executing CLI command (null for system events) |
| event | object | yes | Domain event with `type` discriminator + domain-specific fields |

### Context Behavior

`commandName` reflects the **innermost** executing command. When `init` calls `up`, events emitted during `up`'s execution have `"commandName": "up"`. Managed via stack-based context — callers never set it manually.

## Event Type Hierarchy

Event types follow the pattern `<Domain>.<Action>`:

- `Cassandra.*` — Cassandra database operations
- `K3s.*` — K3s cluster management
- `K8s.*` — Kubernetes operations
- `Infra.*` — AWS infrastructure (VPC, subnet, security group, etc.)
- `Ec2.*` — EC2 instance operations
- `Emr.*` — EMR and Spark operations
- `OpenSearch.*` — OpenSearch domain operations
- `S3.*` — S3 object store operations
- `Sqs.*` — SQS queue operations
- `Grafana.*` — Grafana dashboard operations
- `Backup.*` — Backup and restore operations
- `Registry.*` — Container registry operations
- `Tailscale.*` — Tailscale VPN operations
- `AwsSetup.*` — AWS resource setup operations
- `Stress.*` — Stress testing operations
- `Service.*` — SystemD service management
- `Provision.*` — Cluster provisioning operations
- `Command.*` — Command execution events

See [data-model.md](../data-model.md) for complete field listings per event type.

## Consumer Guidelines

1. **Filtering**: Match on `event.type` prefix for domain filtering (e.g., all Cassandra events start with `Cassandra.`)
2. **Forward compatibility**: Ignore unknown fields. New event types may be added in future versions.
3. **Deserialization**: Use the `event.type` field as the polymorphic discriminator for typed deserialization.
4. **Missing fields**: Domain-specific fields may be null if context was unavailable at emission time.

## AsyncAPI Spec Skeleton

The delivered `docs/reference/event-bus-asyncapi.yaml` will follow this structure:

```yaml
asyncapi: 3.0.0
info:
  title: easy-db-lab Event Bus
  version: 1.0.0
  description: >
    Structured event stream for monitoring easy-db-lab cluster operations.
    Events are published to a Redis pub/sub channel as JSON envelopes.

channels:
  easydblab-events:
    address: "{channel}"
    messages:
      eventEnvelope:
        $ref: '#/components/messages/EventEnvelope'
    bindings:
      redis:
        method: subscribe

components:
  messages:
    EventEnvelope:
      payload:
        $ref: '#/components/schemas/EventEnvelope'

  schemas:
    EventEnvelope:
      type: object
      required: [timestamp, event]
      properties:
        timestamp:
          type: string
          format: date-time
        commandName:
          type: string
          nullable: true
        event:
          $ref: '#/components/schemas/Event'

    Event:
      discriminator: type
      oneOf:
        - $ref: '#/components/schemas/CassandraStarting'
        - $ref: '#/components/schemas/CassandraStartedWaitingReady'
        # ... all ~120 event types

    CassandraStarting:
      type: object
      required: [type, host]
      properties:
        type:
          const: Cassandra.Starting
        host:
          type: string
          description: The Cassandra node alias being started
    # ... etc.
```
