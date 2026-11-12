| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC1 | ArchUnit SDK rule, zero violations, no allowlist | `provider-boundary: The rule passes on the current tree` | ✅ |
| AC2 | A new SDK import outside the adapters fails and names the class and type | `provider-boundary: A new SDK import outside the adapters fails the build`; `An SDK exception caught outside the adapters fails the build` | ✅ |
| AC3 | Main classes only | `provider-boundary: Test classes are not scanned` | ✅ |
| AC4 | providers↛services, configuration↛providers | `provider-boundary: providers does not depend on services`; `configuration does not depend on providers` | ✅ |
| AC5 | Expired credentials give a login remedy and a non-zero exit | `provider-errors: Expired credentials` | ✅ |
| AC6 | 403 from STS/S3/EC2/other gives permission-denied and names the policy | `provider-errors: Permission denied from each AWS service` | ✅ |
| AC7 | `SdkClientException` with no credentials gives a credentials message | `provider-errors: No credentials found` | ✅ |
| AC8 | An unowned exception still emits `ExecutionError` | `provider-errors: Exception owned by no provider` | ✅ |
| AC9 | A wrapped exception is found through the cause chain | `provider-errors: Wrapped provider exception` | ✅ |
| AC10 | `spark status`: every StepState unchanged | `spark-emr: Every EMR step state maps unchanged` | ✅ |
| AC11 | `opensearch status` not-found output unchanged | `opensearch: Status of a domain that does not exist` | ✅ |
| AC12 | Invalid region rejected as today; key pair created in services/aws | `setup: Invalid region is rejected as today`; `Valid region creates the key pair` | ✅ |
| AC13 | Compactor registered request identical | `account-compactor: Registered request is unchanged` | ✅ |
| AC14 | `Main.kt` holds no SDK import or catch | `provider-errors: Entry point is provider-free` | ✅ |
| AC15 | `check` passes on JDK 21 with no `@Suppress`, threshold or baseline change | — | ⚠️ Excluded as a scenario: a build gate, enforced by task 6.3 |
| Risk | An STS 403 from bad credentials is misread as permission-denied | `provider-errors: Bad credentials that arrive as a 403` | ✅ |
| Risk | Failures before the lifecycle starts still print a raw stack trace (Picocli `handleUnhandled`) | `provider-errors: Failure before the command lifecycle starts` | ✅ |
| Risk | Duplicate error blocks | `provider-errors: Already-explained failures print one error block` | ✅ |
| Risk | Network errors misclassified as credentials errors | `provider-errors: Network failure is not misclassified` | ✅ |
| Risk | A cause loop hangs the reporter | `provider-errors: Cause loop terminates` | ✅ |
| Risk | The Koin split fails only at runtime; a handler needs a profile | `provider-boundary: Dependency wiring still resolves after the split` | ✅ |
| Risk | `tempo-backend.yaml` left behind | `account-compactor: Registered request is unchanged` | ✅ |
| Risk | Unknown EMR state shows "null" or throws an NPE | `spark-emr: Unknown EMR state` | ✅ |
| Risk | Expired SSO in setup shows the permission guidance | `setup: Setup with expired SSO credentials` | ✅ |
| Risk | `Up.kt:225` retry stops if adapters translate exceptions | — | ⚠️ Excluded: no translation in this slice; recorded in `providers/CLAUDE.md` (task 6.2) |
| Risk | Packer reuses stale SSO credentials in REPL/Server | — | ✅ `AwsModulesResolutionTest` asserts two `get<AWSCredentialsManager>()` calls return different instances, so a change to `single` fails |
