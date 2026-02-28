## 1. OTel Collector Config

- [x] 1.1 Add k8sattributes processor to otel-collector-config.yaml extracting node label `type` as `node_role`
- [x] 1.2 Add k8sattributes to `metrics/local` pipeline processors (after resourcedetection, before batch)
- [x] 1.3 Add k8sattributes to `logs/local` pipeline processors (after resourcedetection, before batch)

## 2. OTel Manifest Builder RBAC

- [x] 2.1 Add ServiceAccount, ClusterRole, ClusterRoleBinding builder methods to OtelManifestBuilder
- [x] 2.2 Set serviceAccountName on the DaemonSet pod spec
- [x] 2.3 Add new resources to buildAllResources() return list

## 3. Control Node Labeling

- [x] 3.1 Add `type=control` label to control node in Up.labelDbNodesWithOrdinals()

## 4. Testing

- [x] 4.1 Verify OtelManifestBuilder RBAC resources apply successfully in K8sServiceIntegrationTest
- [x] 4.2 Verify no resource limits on new RBAC resources (existing collectAllResources test)
- [x] 4.3 Run full test suite to confirm no regressions

## 5. Documentation

- [x] 5.1 Update docs/reference/opentelemetry.md with node_role resource attribute documentation
- [x] 5.2 Update CLAUDE.md observability section if needed
