## Overrides existing behavior

- An STS, IAM or S3 403 caused by bad credentials was reported as a permissions error by the dead `Main.kt` handler and by `AWSResourceSetupService`. It is now a credentials error.
- `spark status` shows `UNKNOWN` instead of `"null"` for an EMR state the SDK does not know.
- Otherwise this change only adds requirements.

## Conflicts with other in-flight changes

- `issue-1034` (ClusterTooling, unmerged branch) touches `services/` and `ServicesModule.kt`, but not the AWS bindings or `EcrPullSecretService`'s binding line. A textual merge conflict in `ServicesModule.kt` is possible; there is no behavioural conflict.
- `issue-1038` (Cassandra kit, spec only): no overlap.
