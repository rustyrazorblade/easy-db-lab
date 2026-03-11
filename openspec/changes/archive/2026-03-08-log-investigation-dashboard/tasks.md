## 1. Dashboard JSON

- [x] 1.1 Create `log-investigation.json` dashboard resource file with template variables (node_role, host, source, level, search), log volume histogram panel, and filtered log viewer panel
- [ ] 1.2 Verify LogsQL query composition works with variable interpolation in Grafana Explore (manual testing against a running cluster)

## 2. Dashboard Registration

- [x] 2.1 Add `LOG_INVESTIGATION` entry to `GrafanaDashboard` enum with correct metadata (configMapName, volumeName, mountPath, jsonFileName, resourcePath)
- [x] 2.2 Verify the dashboard deploys correctly via `K8sServiceIntegrationTest` (existing test infrastructure covers all enum entries automatically)

## 3. Testing

- [x] 3.1 Run `./gradlew :test` to confirm no regressions from the new enum entry
- [x] 3.2 Run `./gradlew ktlintCheck` and `./gradlew detekt` to confirm code quality

## 4. Documentation

- [x] 4.1 Update user documentation to describe the Log Investigation dashboard, its filters, and investigation workflow
