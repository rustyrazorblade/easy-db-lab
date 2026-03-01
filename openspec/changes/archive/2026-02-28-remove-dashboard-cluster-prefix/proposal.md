## Why

Dashboard titles currently include a `__CLUSTER_NAME__` prefix (e.g., "test - System Overview"), which adds visual noise and is incompatible with future multi-cluster dashboard work where the cluster name will be used differently.

## What Changes

- Remove the `__CLUSTER_NAME__ - ` prefix from all Grafana dashboard JSON titles
- Dashboard titles will be simple descriptive names (e.g., "System Overview", "EMR Overview", "Profiling")

## Capabilities

### New Capabilities

_None_

### Modified Capabilities

- `observability`: Dashboard titles no longer include cluster name prefix

## Impact

- 10 dashboard JSON files in `src/main/resources/.../configuration/grafana/dashboards/`
- Existing clusters will see updated titles after running `grafana update-config`
- No behavioral changes — only cosmetic title change
