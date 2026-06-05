## Kit

<!-- Kit name (kebab-case), one-line description, node type, collision-check -->

- **Name**: `<kit-name>`
- **Description**: <what this kit installs and why it's useful>
- **Node type**: `db` or `app`
- **Collision check**: `true` (kit owns persistent volumes) or `false`

## Workload

<!-- What system does this kit install? Link to upstream documentation. -->

## Runtime

<!-- How does the workload run?
     helm: chart name, repo URL, release name
     pods: label selector, namespace -->

## Endpoints

<!-- Ports and protocols the workload exposes. Add a row per endpoint. -->

| Name | Node type | Port | Type | Notes |
|------|-----------|------|------|-------|
| <!-- e.g. HTTP UI --> | <!-- db or app --> | <!-- 8080 --> | <!-- http --> | |
| <!-- e.g. JDBC --> | <!-- db or app --> | <!-- 9000 --> | <!-- jdbc --> | scheme: |

## Args

<!-- Configuration flags the kit will accept. Add a row per arg. -->

| Flag | Variable | Description | Default | Type |
|------|----------|-------------|---------|------|
| `--<flag>` | `<VARIABLE>` | <description> | `<default>` | string\|int\|float\|boolean |

## Integrations

<!-- Query engine connector support. Fill in what is known; mark unknowns for investigation. -->

- **Presto**: <!-- connector jar available? catalog template needed? -->
- **ClickHouse federated**: <!-- supported? -->
- **Spark JDBC**: <!-- driver available? -->
- **Other**: <!-- any other query engines that can connect to this workload? -->
